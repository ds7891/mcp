package com.mcp.injector.ui.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcp.injector.InjectorApp
import com.mcp.injector.agent.AgentProbe
import com.mcp.injector.agent.InjectedApp
import com.mcp.injector.agent.InjectedAppScanner
import com.mcp.injector.agent.InjectedAppsStore
import com.mcp.injector.ai.AiPlanner
import com.mcp.injector.ai.ChatTurn
import com.mcp.injector.ai.CurrentStrategy
import com.mcp.injector.ai.StrategyPatch
import com.mcp.injector.apk.ApkInjector
import com.mcp.injector.apk.ApkParser
import com.mcp.injector.apk.DexOps
import com.mcp.injector.data.AiRepository
import com.mcp.injector.data.ApiKeyMissingException
import com.mcp.injector.data.Project
import com.mcp.injector.data.ProjectsStore
import com.mcp.injector.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 主页 ViewModel（任务 C 核心）。
 *
 * 对齐反编译 HomeViewModel 的公开契约（AndroidViewModel + state/events + refresh/importApk/
 * deleteProject/outputFor/iconOf），并新增任务 C 的「已注入应用」：
 * - [UiState.injectedApps] = 注入历史（[InjectedAppsStore]）+ 同签名扫描（[InjectedAppScanner]）；
 * - [importApk] 全流程：复制 → 解析 → 预检规划 → 一次注入 → 更新工程状态 → 刷新已注入列表。
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val store: ProjectsStore = (app as InjectorApp).projectsStore
    private val settingsRepo: SettingsRepository = (app as InjectorApp).settingsRepository
    private val injectedStore: InjectedAppsStore = (app as InjectorApp).injectedAppsStore

    private val ai = AiRepository()
    private val planner = AiPlanner(ai)
    private val injector = ApkInjector(app)

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    /** 主页 UI 状态：原工程列表 + 已注入应用（历史+扫描）+ 当前模型 + 忙碌标记 + 策略对话。 */
    data class UiState(
        val projects: List<Project> = emptyList(),
        val injectedApps: List<InjectedApp> = emptyList(),
        val model: String = "",
        val busy: Boolean = false,
        /** 非 null 表示策略对话弹窗打开中。 */
        val strategyChat: StrategyChatState? = null,
    )

    /**
     * AI 策略对话状态（长按工程/已注入卡片打开）。
     *
     * 用户描述问题 → [sendChatProblem] 让 AI 产出策略补丁 → [applyChatPatchAndReinject]
     * 把补丁合并进方案并重新注入。目标侧诊断/崩溃栈在 [openStrategyChat] 时并行取回，
     * 作为 AI 判断依据（目标未响应时仅凭用户描述）。
     */
    data class StrategyChatState(
        val packageName: String,
        val appName: String,
        val turns: List<ChatTurn> = emptyList(),
        val input: String = "",
        /** AI 请求进行中。 */
        val thinking: Boolean = false,
        /** 重新注入进行中。 */
        val applying: Boolean = false,
        /** 已累积的策略补丁（多次对话的并集，新值覆盖旧值）。 */
        val pendingPatch: StrategyPatch = StrategyPatch(),
        /** 待应用的改动点（中文），空表示 AI 未给出可执行改动。 */
        val changes: List<String> = emptyList(),
        /** 目标侧上下文说明（诊断/崩溃取回情况）。 */
        val contextNote: String = "",
        val error: String? = null,
        /** cmd=diagnose 回执（供 AI 提示词）；null=未响应。 */
        val diagnoseJson: JSONObject? = null,
        /** cmd=crash 回执中的堆栈文本；null=未取到。 */
        val crashLog: String? = null,
    )

    init {
        refresh()
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                _state.update { it.copy(model = s.model) }
            }
        }
    }

    /** 刷新工程列表与已注入应用（历史 + 同签名扫描）。 */
    fun refresh() {
        val app = getApplication<Application>()
        val scanned = InjectedAppScanner.scan(app, injectedStore)
        _state.update {
            it.copy(
                projects = store.loadAll(),
                injectedApps = injectedStore.loadAll() + scanned,
            )
        }
    }

    /** 导入并注入 APK：复制 → 解析 → 扫描提示 → AI 规划 → 一次注入 → 记录历史。 */
    fun importApk(uri: Uri) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            var proj: Project? = null
            try {
                val app = getApplication<Application>()

                val copied = withContext(Dispatchers.IO) { copyToImports(app, uri) }
                val info = withContext(Dispatchers.IO) { ApkParser.parse(copied) }
                val iconRel = withContext(Dispatchers.IO) { extractIcon(copied) }

                proj = Project(
                    appName = info.appLabel ?: info.packageName,
                    packageName = info.packageName,
                    versionName = info.versionName,
                    iconFile = iconRel,
                )
                store.upsert(proj)
                refresh()

                proj = proj.copy(status = Project.Status.ANALYZING)
                store.upsert(proj)
                refresh()
                // 先取设置：dex 扫描规模由模型上下文窗口决定（小窗口别把提示词撑爆）
                val settings = settingsRepo.current()
                val hints = withContext(Dispatchers.IO) {
                    DexOps.scanHints(copied, DexOps.DexHintBudget.forContext(settings.contextTokens))
                }

                proj = proj.copy(status = Project.Status.PLANNING)
                store.upsert(proj)
                refresh()
                val plan = planner.plan(info, hints, settings)

                proj = proj.copy(status = Project.Status.INJECTING)
                store.upsert(proj)
                refresh()
                val output = File(store.outputDir(), sanitize(info.packageName) + "-mcp.apk")
                withContext(Dispatchers.IO) { injector.inject(copied, info, plan, output) }

                proj = proj.copy(
                    status = Project.Status.DONE,
                    outputRelative = output.relativeTo(app.filesDir).path,
                    plan = plan,
                )
                store.upsert(proj)
                refresh()
                _events.tryEmit("注入完成：${proj.appName}")
            } catch (t: Throwable) {
                // 注入失败的异常往往是"裸 message"，缺环因；这里把完整堆栈落盘到崩溃日志，
                // 供诊断。同时拼接 cause 摘要展示给用户。
                runCatching {
                    com.mcp.injector.crash.CrashLogger.save(
                        getApplication(), Thread.currentThread(), t,
                    )
                }
                val detail = buildString {
                    append(t.message ?: t.javaClass.simpleName)
                    var c = t.cause
                    var depth = 0
                    while (c != null && depth < 4) {
                        append(" <- ")
                        append(c.message ?: c.javaClass.simpleName)
                        c = c.cause
                        depth++
                    }
                }
                if (proj != null) {
                    store.upsert(proj.copy(status = Project.Status.FAILED, error = detail))
                    refresh()
                }
                _events.tryEmit("注入失败：$detail")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun deleteProject(id: String) {
        val project = store.loadAll().firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.remove(id) }
            refresh()
            _events.tryEmit("已删除 ${project.appName}")
        }
    }

    /** 注入产物 APK 文件（filesDir 相对路径解析）。 */
    fun outputFor(project: Project): File? =
        project.outputRelative?.let { File(getApplication<Application>().filesDir, it) }

    /** 工程图标文件。 */
    fun iconOf(project: Project): File? =
        project.iconFile?.let { File(getApplication<Application>().filesDir, it) }

    // ---------------------------------------------------------------------
    // AI 策略对话（长按工程 / 已注入卡片打开）
    // ---------------------------------------------------------------------

    /** 打开策略对话并异步取回目标侧诊断/崩溃栈，作为 AI 判断现状的依据。 */
    fun openStrategyChat(packageName: String) {
        if (packageName.isEmpty()) return
        val record = injectedStore.find(packageName)
        val project = store.loadAll().firstOrNull { it.packageName == packageName }
        val appName = record?.appName ?: project?.appName ?: packageName
        _state.update {
            it.copy(
                strategyChat = StrategyChatState(
                    packageName = packageName,
                    appName = appName,
                    contextNote = "正在取回目标侧诊断与崩溃栈…",
                ),
            )
        }
        viewModelScope.launch {
            val appCtx = getApplication<Application>()
            val diagnose = probeJson(appCtx, packageName, "diagnose")
            val crashLog = probeJson(appCtx, packageName, "crash")
                ?.optString("log")?.takeIf { s -> s.isNotBlank() }
            val note = buildContextNote(diagnose, crashLog)
            _state.update { state ->
                val chat = state.strategyChat ?: return@update state
                if (chat.packageName != packageName) return@update state
                state.copy(
                    strategyChat = chat.copy(
                        contextNote = note,
                        diagnoseJson = diagnose,
                        crashLog = crashLog,
                    ),
                )
            }
        }
    }

    fun dismissStrategyChat() {
        _state.update { it.copy(strategyChat = null) }
    }

    /** 对话输入框内容更新（UI 直接回写）。 */
    fun updateChatInput(value: String) {
        _state.update { it.copy(strategyChat = it.strategyChat?.copy(input = value)) }
    }

    /**
     * 发送问题：追加用户消息 → AiPlanner.reviseStrategy → 追加 AI 回复并累积策略补丁。
     * 未配置 API Key（[ApiKeyMissingException]）时给出引导到设置页的提示。
     */
    fun sendChatProblem() {
        val chat = _state.value.strategyChat ?: return
        val problem = chat.input.trim()
        if (problem.isEmpty() || chat.thinking || chat.applying) return
        val packageName = chat.packageName
        // 先留存历史（不含本条），问题本身由 reviseStrategy 的 problem 参数单独回灌，
        // 避免同一句话在提示词里出现两次。
        val history = chat.turns
        val pendingPatch = chat.pendingPatch
        _state.update {
            it.copy(
                strategyChat = it.strategyChat?.copy(
                    input = "",
                    thinking = true,
                    error = null,
                    turns = it.strategyChat.turns + ChatTurn(ROLE_USER, problem),
                ),
            )
        }
        viewModelScope.launch {
            try {
                val record = injectedStore.find(packageName)
                val project = store.loadAll().firstOrNull { it.packageName == packageName }
                val current = buildCurrentStrategy(record, project)
                val revision = planner.reviseStrategy(
                    packageName = packageName,
                    appVersion = record?.versionName ?: project?.versionName,
                    current = current,
                    problem = problem,
                    crashLog = _state.value.strategyChat?.crashLog,
                    diagnoseJson = _state.value.strategyChat?.diagnoseJson,
                    history = history,
                    settings = settingsRepo.current(),
                )
                val merged = mergePatch(pendingPatch, revision.patch)
                _state.update { state ->
                    val c = state.strategyChat ?: return@update state
                    if (c.packageName != packageName) return@update state
                    state.copy(
                        strategyChat = c.copy(
                            thinking = false,
                            pendingPatch = merged,
                            changes = merged.describeChanges(current),
                            turns = c.turns + ChatTurn(ROLE_ASSISTANT, revision.reply),
                        ),
                    )
                }
            } catch (e: ApiKeyMissingException) {
                failChat(packageName, e.message ?: "未配置 API Key，请先到设置页填写")
            } catch (t: Throwable) {
                failChat(packageName, t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * 应用补丁并重新注入：优先复用工程已存方案，方案缺失时才重新调用 AI 规划。
     * 注入完成后更新工程记录（状态/产物/方案）并刷新列表。
     */
    fun applyChatPatchAndReinject() {
        val chat = _state.value.strategyChat ?: return
        if (chat.thinking || chat.applying) return
        if (chat.pendingPatch.isEmpty()) {
            _state.update {
                it.copy(strategyChat = it.strategyChat?.copy(error = "AI 未给出可执行的策略改动"))
            }
            return
        }
        val packageName = chat.packageName
        val patch = chat.pendingPatch
        _state.update { it.copy(strategyChat = it.strategyChat?.copy(applying = true, error = null)) }
        viewModelScope.launch {
            try {
                val appCtx = getApplication<Application>()
                val project = store.loadAll().firstOrNull { it.packageName == packageName }
                val source = resolveSourceApk(appCtx, packageName)
                    ?: throw IllegalStateException("缺少源 APK / 注入产物，无法重新注入")
                val info = withContext(Dispatchers.IO) { ApkParser.parse(source) }
                val settings = settingsRepo.current()
                val basePlan = project?.plan ?: run {
                    val hints = withContext(Dispatchers.IO) {
                        DexOps.scanHints(source, DexOps.DexHintBudget.forContext(settings.contextTokens))
                    }
                    planner.plan(info, hints, settings)
                }
                val patched = patch.applyTo(basePlan)
                val output = File(store.outputDir(), sanitize(info.packageName) + "-mcp.apk")
                withContext(Dispatchers.IO) { injector.inject(source, info, patched, output) }
                if (project != null) {
                    store.upsert(
                        project.copy(
                            status = Project.Status.DONE,
                            outputRelative = output.relativeTo(appCtx.filesDir).path,
                            plan = patched,
                            error = null,
                        ),
                    )
                }
                refresh()
                _state.update { state ->
                    val c = state.strategyChat ?: return@update state
                    if (c.packageName != packageName) return@update state
                    state.copy(
                        strategyChat = c.copy(
                            applying = false,
                            pendingPatch = StrategyPatch(),
                            changes = emptyList(),
                            turns = c.turns + ChatTurn(
                                ROLE_ASSISTANT,
                                "已按调整重新注入：策略=${patched.strategy}，入口=${patched.entryPoint}，" +
                                    "连接=${patched.connectionMode}，端口=${patched.port}。" +
                                    "请重新打开目标应用验证；若仍有问题可继续反馈。",
                            ),
                        ),
                    )
                }
                _events.tryEmit("已应用策略调整并重新注入：${info.appLabel ?: info.packageName}")
            } catch (t: Throwable) {
                failChat(packageName, "重新注入失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    // ---------------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------------

    /** 广播探测并解析回执 JSON；目标未响应/回执损坏返回 null。 */
    private suspend fun probeJson(appCtx: Application, packageName: String, cmd: String): JSONObject? {
        val raw = withContext(Dispatchers.IO) {
            runCatching { AgentProbe.probe(appCtx, packageName, cmd) }.getOrNull()
        } ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    /** 目标侧上下文说明：诊断/崩溃取回情况，供弹窗顶部提示。 */
    private fun buildContextNote(diagnose: JSONObject?, crashLog: String?): String {
        val parts = ArrayList<String>()
        if (diagnose != null) {
            val errs = diagnose.optJSONArray("errors")?.length() ?: 0
            val recs = diagnose.optJSONArray("recommendations")?.length() ?: 0
            parts.add("已取回目标诊断（$errs 项问题 / $recs 条建议）")
        } else {
            parts.add("目标未响应，AI 将仅依据你的描述调整")
        }
        parts.add(
            if (!crashLog.isNullOrBlank()) {
                "已取回目标崩溃栈（${crashLog.length} 字符）"
            } else {
                "未取到目标崩溃栈"
            },
        )
        return parts.joinToString("；")
    }

    /** 还原目标当前策略：注入历史优先，工程方案兜底。 */
    private fun buildCurrentStrategy(record: InjectedApp?, project: Project?): CurrentStrategy {
        val plan = project?.plan
        return CurrentStrategy.from(
            entryPoint = record?.entryPoint ?: plan?.entryPoint,
            toastOnBoot = record?.toastOnBoot ?: plan?.toastOnBoot ?: true,
            strategy = record?.strategy ?: plan?.strategy,
            connectionMode = plan?.connectionMode,
            port = record?.port?.takeIf { p -> p > 0 } ?: plan?.port ?: DEFAULT_PORT,
        )
    }

    /** 重新注入源 APK：原始导入副本优先，注入产物兜底。 */
    private fun resolveSourceApk(appCtx: Application, packageName: String): File? {
        val record = injectedStore.find(packageName)
        record?.sourceRelative?.let { File(appCtx.filesDir, it) }
            ?.takeIf { it.exists() }?.let { return it }
        return record?.outputRelative?.let { File(appCtx.filesDir, it) }?.takeIf { it.exists() }
    }

    /** 补丁并集：新值为 null 时保留旧值，非 null 覆盖旧值。 */
    private fun mergePatch(base: StrategyPatch, next: StrategyPatch): StrategyPatch = base.copy(
        entryPoint = next.entryPoint ?: base.entryPoint,
        toastOnBoot = next.toastOnBoot ?: base.toastOnBoot,
        strategy = next.strategy ?: base.strategy,
        connectionMode = next.connectionMode ?: base.connectionMode,
        port = next.port ?: base.port,
        explanation = next.explanation.ifBlank { base.explanation },
    )

    /** 标记对话出错：停止忙碌态并记录可读原因。 */
    private fun failChat(packageName: String, message: String) {
        _state.update { state ->
            val c = state.strategyChat ?: return@update state
            if (c.packageName != packageName) return@update state
            state.copy(strategyChat = c.copy(thinking = false, applying = false, error = message))
        }
    }

    /** 导入源复制到 filesDir/imports（持久保存，供 Manager 重新注入复用）。 */
    private fun copyToImports(app: Application, uri: Uri): File {
        val dir = File(app.filesDir, "imports").apply { mkdirs() }
        val file = File(dir, "import-${System.currentTimeMillis()}.apk")
        val input = app.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法读取所选文件")
        input.use { it.copyTo(file.outputStream()) }
        return file
    }

    /** 提取 APK 图标为 filesDir/icons/<name>.png，返回相对路径；失败返回 null。 */
    private fun extractIcon(apk: File): String? = runCatching {
        val app = getApplication<Application>()
        val pm = app.packageManager
        val info = pm.getPackageArchiveInfo(apk.absolutePath, 0) ?: return null
        val ai = info.applicationInfo ?: return null
        ai.sourceDir = apk.absolutePath
        ai.publicSourceDir = apk.absolutePath
        val drawable = ai.loadIcon(pm) ?: return null
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, 96, 96)
        drawable.draw(canvas)
        val dir = File(app.filesDir, "icons").apply { mkdirs() }
        val name = apk.nameWithoutExtension + ".png"
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        "icons/$name"
    }.getOrNull()

    private fun sanitize(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9_\\-]"), "_")

    private companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        /** 与 AiPlanner 缺省端口对齐（注入历史无端口时展示/回灌用）。 */
        const val DEFAULT_PORT = 1732
    }
}
