package com.mcp.injector.agent

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.mcp.injector.bridge.OfficialModule
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 已注入应用记录（任务 C 支撑文件）。
 *
 * 指纹识别三来源之一「注入历史」的宿主侧持久化：
 * - [InjectedAppsStore]：`injected_apps.json` 保存每次注入的历史
 *   （包名、签名指纹、installId、端口、模块——契约字段）；
 * - [InjectedAppScanner]：「与注入器同签名的已安装应用」扫描
 *   （PackageManager 签名比较，即指纹识别第二来源）；
 * - 第三来源「AgentReceiver 广播探测」见 [AgentProbe]。
 */
data class InjectedApp(
    /** 注入指纹：宿主生成的 installId（扫描来源为 "scan:<pkg>" 占位）。 */
    val installId: String,
    val packageName: String,
    val appName: String,
    val versionName: String,
    /** 签名指纹（SHA-256 hex）：与注入器同签名的应用可被扫描识别。 */
    val signatureFingerprint: String,
    /** 注入端口（扫描来源未知时为 0，由广播探测补全）。 */
    val port: Int,
    val modules: List<String>,
    val injectedAt: Long,
    /** "history"（注入历史）、"marker"（目标 APK 内标记识别）或 "scan"（同签名扫描）。 */
    val source: String,
    /** 注入产物 APK 相对 filesDir 的路径（可用于安装/复检）。 */
    val outputRelative: String? = null,
    /** 注入源 APK 相对 filesDir 的路径（重新注入时复用）。 */
    val sourceRelative: String? = null,
    /** 注入策略（hook / service），诊断回传再注入时参考。 */
    val strategy: String? = null,
    /** 注入时是否成功 hook 启动类（clinit 策略才有意义）。 */
    val hookable: Boolean = false,
    /** 注入入口方式：provider（默认，不改目标 dex）/ clinit（<clinit> 插桩）。 */
    val entryPoint: String? = null,
    /** 注入时是否开启了启动提示。 */
    val toastOnBoot: Boolean = true,
)

/** 注入历史仓库：filesDir/injected_apps.json。 */
class InjectedAppsStore(private val context: Context) {

    private fun file(): File =
        File(context.filesDir, "injected_apps.json").apply { parentFile?.mkdirs() }

    @Synchronized
    fun loadAll(): List<InjectedApp> {
        if (!file().exists()) return emptyList()
        return runCatching {
            val root = JSONObject(file().readText())
            val arr = root.optJSONArray("apps") ?: return emptyList()
            (0 until arr.length()).mapNotNull { parse(it, arr.optJSONObject(it)) }
        }.getOrDefault(emptyList()).sortedByDescending { it.injectedAt }
    }

    @Synchronized
    fun saveAll(apps: List<InjectedApp>) {
        val arr = JSONArray()
        for (app in apps) arr.put(toJson(app))
        val target = file()
        // 原子写：先写同目录临时文件，再 rename 覆盖目标。避免直接覆盖时写入
        // 中途被杀/异常留下半截或空文件，进而被 loadAll 的 runCatching 解析失败
        // 静默吞成 emptyList，导致全部注入历史丢失。
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileWriter(tmp).use { it.write(JSONObject().put("apps", arr).toString(2)) }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            // 极少数文件系统 renameTo 可能失败，退化为复制后删除临时文件，避免残留 tmp。
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /** 按包名去重插入（历史优先于扫描，覆盖旧记录）。 */
    @Synchronized
    fun upsert(app: InjectedApp): List<InjectedApp> {
        val all = loadAll().filter { it.packageName != app.packageName } + app
        saveAll(all)
        return all
    }

    @Synchronized
    fun remove(packageName: String): List<InjectedApp> {
        val all = loadAll().filter { it.packageName != packageName }
        saveAll(all)
        return all
    }

    fun find(packageName: String): InjectedApp? =
        loadAll().firstOrNull { it.packageName == packageName }

    /** 宿主自身签名指纹（SHA-256 hex）：同时是注入产物（重签名）与「同签名扫描」的基准。 */
    fun sigFingerprint(): String? = fingerprintOf(context, context.packageName)

    // ---- 序列化 ----

    private fun toJson(app: InjectedApp): JSONObject = JSONObject()
        .put("installId", app.installId)
        .put("packageName", app.packageName)
        .put("appName", app.appName)
        .put("versionName", app.versionName)
        .put("signatureFingerprint", app.signatureFingerprint)
        .put("port", app.port)
        .put("modules", JSONArray(app.modules))
        .put("injectedAt", app.injectedAt)
        .put("source", app.source)
        .put("outputRelative", app.outputRelative ?: JSONObject.NULL)
        .put("sourceRelative", app.sourceRelative ?: JSONObject.NULL)
        .put("strategy", app.strategy ?: JSONObject.NULL)
        .put("hookable", app.hookable)
        .put("entryPoint", app.entryPoint ?: JSONObject.NULL)
        .put("toastOnBoot", app.toastOnBoot)

    private fun parse(idx: Int, obj: JSONObject?): InjectedApp? {
        obj ?: return null
        val pkg = obj.optString("packageName", "")
        if (pkg.isEmpty()) return null
        val arr = obj.optJSONArray("modules")
        val modules = if (arr == null) {
            emptyList()
        } else {
            (0 until arr.length()).map { arr.optString(it) }
        }
        return InjectedApp(
            installId = obj.optString("installId", ""),
            packageName = pkg,
            appName = obj.optString("appName", pkg),
            versionName = obj.optString("versionName", ""),
            signatureFingerprint = obj.optString("signatureFingerprint", ""),
            port = obj.optInt("port", 0),
            modules = modules,
            injectedAt = obj.optLong("injectedAt", 0L),
            source = obj.optString("source", "history"),
            outputRelative = obj.optString("outputRelative").takeIf { it.isNotEmpty() },
            sourceRelative = obj.optString("sourceRelative").takeIf { it.isNotEmpty() },
            strategy = obj.optString("strategy").takeIf { it.isNotEmpty() },
            hookable = obj.optBoolean("hookable", false),
            entryPoint = obj.optString("entryPoint").takeIf { it.isNotEmpty() },
            toastOnBoot = obj.optBoolean("toastOnBoot", true),
        )
    }

    companion object {
        /** 计算某安装包签名证书 SHA-256 hex；无签名/异常返回 null。 */
        @Suppress("DEPRECATION")
        fun fingerprintOf(context: Context, packageName: String): String? = runCatching {
            val pm = context.packageManager
            val signer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // API 28+：使用 GET_SIGNING_CERTIFICATES + signingInfo
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    ?: return null
                val signingInfo = info.signingInfo ?: return null
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners.firstOrNull()
                } else {
                    signingInfo.signingCertificateHistory.firstOrNull()
                }
            } else {
                // API < 28：回退到废弃的 GET_SIGNATURES + PackageInfo.signatures
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                    ?: return null
                info.signatures?.firstOrNull()
            }
            val cert = signer ?: return null
            val digest = MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }
}

/** 已注入应用识别：APK 内标记优先，同签名扫描兜底（指纹识别来源二）。 */
object InjectedAppScanner {

    /** APK 内的注入标记资产名（与 [ApkInjector] 写入、OfficialModule 校验的名字一致）。 */
    private const val MARKER_ENTRY = "assets/" + OfficialModule.MARKER_FILE

    /** 来源：按目标 APK 内的注入标记识别（不依赖注入器自身的密钥与历史文件）。 */
    const val SOURCE_MARKER = "marker"

    /**
     * 扫描已安装应用中「由本注入器注入过」的应用。
     *
     * 两条来源，按可靠性排序：
     * 1. **APK 内注入标记**（[SOURCE_MARKER]）：注入时写进 `assets/.mcp_injector_marker`，
     *    随目标 APK 一直存在。**卸载并重装注入器后**历史文件与重签名密钥都会随 filesDir 清空，
     *    但目标 APK 里的标记还在，因此仍能被识别与管理；
     * 2. **同签名**（source="scan"）：与本注入器同签名的应用（用户用同一密钥自行签名的场景）。
     *
     * 已存在于历史中的包名跳过（由历史记录覆盖展示，信息更全）。
     */
    fun scan(context: Context, store: InjectedAppsStore): List<InjectedApp> {
        val existing = store.loadAll().map { it.packageName }.toSet()
        val pm = context.packageManager
        val out = ArrayList<InjectedApp>()
        for (pi in pm.getInstalledPackages(0)) {
            val pkg = pi.packageName ?: continue
            if (pkg == context.packageName) continue
            if (pkg in existing) continue
            recognize(context, store, pkg, pi)?.let { out.add(it) }
        }
        return out.sortedByDescending { it.injectedAt }
    }

    /** 识别单个已安装应用；不是本注入器注入的返回 null。 */
    fun recognize(context: Context, store: InjectedAppsStore, packageName: String): InjectedApp? {
        if (packageName.isEmpty() || packageName == context.packageName) return null
        val pm = context.packageManager
        val pi = runCatching { pm.getPackageInfo(packageName, 0) }.getOrNull() ?: return null
        return recognize(context, store, packageName, pi)
    }

    private fun recognize(
        context: Context,
        store: InjectedAppsStore,
        pkg: String,
        pi: PackageInfo,
    ): InjectedApp? {
        val label = runCatching { pi.applicationInfo?.loadLabel(context.packageManager)?.toString() }
            .getOrNull() ?: pkg
        val version = pi.versionName ?: ""
        // 来源 1：APK 内注入标记（重装注入器后唯一可靠的依据）
        readMarker(pi.applicationInfo?.sourceDir)?.let { marker ->
            return fromMarker(pkg, label, version, marker)
        }
        // 来源 2：与本注入器同签名
        val hostFp = store.sigFingerprint() ?: return null
        val fp = InjectedAppsStore.fingerprintOf(context, pkg) ?: return null
        if (fp != hostFp) return null
        return InjectedApp(
            installId = "scan:$pkg",
            packageName = pkg,
            appName = label,
            versionName = version,
            signatureFingerprint = fp,
            port = 0,
            modules = emptyList(),
            injectedAt = System.currentTimeMillis(),
            source = "scan",
        )
    }

    /** 读取已安装 APK 的 `assets/.mcp_injector_marker`；不存在或不可读返回 null。 */
    private fun readMarker(apkPath: String?): JSONObject? {
        apkPath ?: return null
        return runCatching {
            ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry(MARKER_ENTRY) ?: return@use null
                val text = zip.getInputStream(entry).use { it.readBytes().decodeToString() }
                JSONObject(text)
            }
        }.getOrNull()
    }

    /** 用标记内容还原注入信息（端口/策略/入口/模块/注入时间，均可直接展示与回灌 AI）。 */
    private fun fromMarker(
        pkg: String,
        label: String,
        version: String,
        marker: JSONObject,
    ): InjectedApp {
        val modules = marker.optJSONArray("modules")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotEmpty() }
            }
        } ?: emptyList()
        return InjectedApp(
            installId = marker.optString("installId").takeIf { it.isNotEmpty() } ?: "marker:$pkg",
            packageName = pkg,
            appName = label,
            versionName = marker.optString("appVersion").takeIf { it.isNotEmpty() } ?: version,
            // 标记里不含目标签名指纹；留空由 UI 显示「未知」，避免把宿主指纹错记到目标上
            signatureFingerprint = "",
            port = marker.optInt("port", 0),
            modules = modules,
            injectedAt = marker.optLong("injectedAt", 0L).takeIf { it > 0L }
                ?: System.currentTimeMillis(),
            source = SOURCE_MARKER,
            strategy = marker.optString("strategy").takeIf { it.isNotEmpty() },
            entryPoint = marker.optString("entryPoint").takeIf { it.isNotEmpty() },
            toastOnBoot = marker.optBoolean("toastOnBoot", true),
        )
    }
}
