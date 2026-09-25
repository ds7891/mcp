package com.mcp.injector.data

import android.content.Context
import com.mcp.injector.ai.InjectionPlan
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 注入工程模型与仓库（任务 C 支撑文件）。
 *
 * 对齐反编译产物 Projects.kt：Project 为 @Serializable 数据类，字段/默认值/Status 常量
 * 与反编译产物一致；ProjectsStore 以 filesDir/projects.json 持久化。
 */
@Serializable
data class Project(
    val id: String = UUID.randomUUID().toString(),
    val appName: String,
    val packageName: String,
    val versionName: String = "",
    val iconFile: String? = null,
    val status: String = Project.Status.IMPORTED,
    val createdAt: Long = System.currentTimeMillis(),
    val outputRelative: String? = null,
    val error: String? = null,
    val plan: InjectionPlan? = null,
) {
    /** 状态常量与忙碌状态集合（对齐反编译 Project$Status）。 */
    object Status {
        const val IMPORTED = "imported"
        const val ANALYZING = "analyzing"
        const val PLANNING = "planning"
        const val INJECTING = "injecting"
        const val DONE = "done"
        const val FAILED = "failed"

        /** 进行中状态：这些状态下不允许并发导入。 */
        val BUSY: Set<String> = setOf(ANALYZING, PLANNING, INJECTING)
    }
}

/**
 * 工程持久化仓库：filesDir/projects.json。
 */
class ProjectsStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private fun file(): File =
        File(context.filesDir, "projects.json").apply { parentFile?.mkdirs() }

    /** 全部工程（文件缺失或损坏时返回空列表）。 */
    fun loadAll(): List<Project> {
        if (!file().exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<Project>>(file().readText())
        }.getOrDefault(emptyList())
    }

    fun saveAll(projects: List<Project>) {
        val target = file()
        // 原子写：先写同目录临时文件再 rename 覆盖目标。直接 writeText 覆盖时若中途被杀/异常
        // 会留下半截或空文件，随后被 loadAll 的 runCatching 静默吞成 emptyList，导致全部工程记录丢失。
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(json.encodeToString(projects))
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /** 按 id 去重插入，按创建时间倒序保存，返回保存后的完整列表。 */
    fun upsert(project: Project): List<Project> {
        val all = loadAll().filter { it.id != project.id } + project
        saveAll(all.sortedByDescending { it.createdAt })
        return all
    }

    fun remove(id: String): List<Project> {
        val all = loadAll().filter { it.id != id }
        saveAll(all)
        return all
    }

    fun iconFile(project: Project): File? =
        project.iconFile?.let { File(context.filesDir, it) }

    /** 注入产物输出目录（filesDir/outputs）。 */
    fun outputDir(): File =
        File(context.filesDir, "outputs").apply { mkdirs() }
}
