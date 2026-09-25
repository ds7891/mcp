package com.mcp.injector.ui.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcp.injector.InjectorApp
import com.mcp.injector.agent.InjectedApp
import com.mcp.injector.agent.InjectedAppScanner
import com.mcp.injector.agent.InjectedAppsStore
import com.mcp.injector.ai.AiPlanner
import com.mcp.injector.apk.ApkInjector
import com.mcp.injector.apk.ApkParser
import com.mcp.injector.apk.DexOps
import com.mcp.injector.data.AiRepository
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

    /** 主页 UI 状态：原工程列表 + 已注入应用（历史+扫描）+ 当前模型 + 忙碌标记。 */
    data class UiState(
        val projects: List<Project> = emptyList(),
        val injectedApps: List<InjectedApp> = emptyList(),
        val model: String = "",
        val busy: Boolean = false,
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
    // 内部
    // ---------------------------------------------------------------------

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
}
