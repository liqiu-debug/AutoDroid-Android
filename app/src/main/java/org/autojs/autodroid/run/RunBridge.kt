package org.autojs.autodroid.run

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import org.autojs.autodroid.data.Session
import org.autojs.autodroid.net.ScriptBundle
import org.autojs.autodroid.util.asArrayOrNull
import org.autojs.autodroid.util.asBooleanOrNull
import org.autojs.autodroid.util.asDoubleOrNull
import org.autojs.autodroid.util.asIntOrNull
import org.autojs.autodroid.util.asObjectOrNull
import org.autojs.autodroid.util.asStringOrNull
import java.io.File

/**
 * 运行桥：把平台下发的脚本包落盘为可执行运行目录。
 *
 * 目录结构（与 zip 脚本包一致，由 AutoJs6 引擎直接执行 main.js）：
 *   runs/<runId>/ 下含 manifest.json、runtime.js、main.js、cases 目录、assets 目录、task_config.json
 */
object RunBridge {

    private val gson = Gson()

    /** 写入运行目录并返回目录。runId 由调用方生成（上报幂等标识）。 */
    fun prepareRunDir(
        context: Context,
        bundle: ScriptBundle,
        runId: String,
        notify: Boolean,
        server: String = Session.serverUrl,
        token: String? = Session.token,
    ): File {
        require(bundle.format_version == 1 && bundle.manifest?.get("platform").asStringOrNull() == "android" &&
            bundle.precheck?.get("ok").asBooleanOrNull() == true) { "脚本包不是预检通过的 Android 格式" }
        val runtimeJs = bundle.runtime_js?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("脚本包缺少 runtime.js")
        val mainJs = bundle.main_js?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("脚本包缺少 main.js")

        val dir = File(context.filesDir, "runs/$runId")
        if (dir.exists()) dir.deleteRecursively()
        check(dir.mkdirs()) { "无法创建运行目录: $dir" }

        File(dir, "manifest.json").writeText(gson.toJson(bundle.manifest))
        File(dir, "runtime.js").writeText(runtimeJs)
        File(dir, "main.js").writeText(mainJs)

        for (entry in bundle.case_files ?: emptyList()) {
            val path = entry.path.orEmpty()
            validatePath(path)
            val file = File(dir, path)
            file.parentFile?.mkdirs()
            file.writeText(entry.content.orEmpty())
        }

        val assets = bundle.assets ?: emptyMap()
        if (assets.isNotEmpty()) {
            val assetsDir = File(dir, "assets").apply { mkdirs() }
            for ((name, base64) in assets) {
                validatePath(name)
                val asset = File(assetsDir, name)
                asset.parentFile?.mkdirs()
                asset.writeBytes(Base64.decode(base64, Base64.DEFAULT))
            }
        }

        // 运行配置：runtime.js 读取该文件获得平台地址/登录态/runId/通知开关
        File(dir, "task_config.json").writeText(
            gson.toJson(
                mapOf(
                    "server" to server,
                    "token" to token,
                    "runId" to runId,
                    "notify" to notify,
                )
            )
        )
        return dir
    }

    private fun validatePath(path: String) {
        require(path.isNotBlank() && !File(path).isAbsolute && '\\' !in path && '\u0000' !in path &&
            path.split('/').none { it == ".." || it == "." }) { "脚本包包含非法文件路径" }
    }

    /**
     * 清理历史运行目录（保留最近 keep 个，按目录修改时间排序）。
     *
     * 注意：runId 是随机 UUID，绝不能按目录名排序——否则刚创建的运行目录
     * 可能被当作"最旧"的删掉，导致 main.js 缺失、脚本无法启动。
     * currentDir 为当前运行目录，显式排除以免被误删。
     */
    fun pruneRuns(context: Context, keep: Int = 10, currentDir: File? = null) {
        try {
            val runsDir = File(context.filesDir, "runs")
            val dirs = runsDir.listFiles()
                ?.filter { it.isDirectory && it.absolutePath != currentDir?.absolutePath }
                ?.sortedByDescending { it.lastModified() }
                ?: return
            dirs.drop(keep).forEach { it.deleteRecursively() }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    //  report.json 解析（执行结果回显）
    // ------------------------------------------------------------------

    data class RunStep(
        val order: Int,
        val action: String,
        val status: String,
        val duration: Double,
        val error: String?,
    )

    data class RunResult(
        val success: Boolean,
        val steps: List<RunStep>,
        val uploadOk: Boolean,
        val executionId: Int?,
        val reportId: String?,
    )

    fun parseReport(reportFile: File): RunResult {
        val root = com.google.gson.JsonParser.parseString(reportFile.readText()).asObjectOrNull()
            ?: return RunResult(success = false, steps = emptyList(), uploadOk = false, executionId = null, reportId = null)
        val success = root.get("success").asBooleanOrNull() ?: false

        val steps = mutableListOf<RunStep>()
        root.get("cases").asArrayOrNull()?.forEach { caseElement ->
            caseElement.asObjectOrNull()?.get("steps").asArrayOrNull()?.forEach { stepElement ->
                val step = stepElement.asObjectOrNull() ?: return@forEach
                steps.add(
                    RunStep(
                        order = step.get("order").asIntOrNull() ?: 0,
                        action = step.get("action").asStringOrNull() ?: "",
                        status = step.get("status").asStringOrNull() ?: "",
                        duration = step.get("duration").asDoubleOrNull() ?: 0.0,
                        error = step.get("error").asStringOrNull(),
                    )
                )
            }
        }

        // upload_response 常为 null（未上报/上报未完成）：getAsJsonObject 会把 JsonNull
        // 强转 JsonObject 直接崩，这里按类型取。
        val upload = root.get("upload_response").asObjectOrNull()
        return RunResult(
            success = success,
            steps = steps,
            uploadOk = upload != null,
            executionId = upload?.get("execution_id").asIntOrNull(),
            reportId = upload?.get("report_id").asStringOrNull(),
        )
    }
}
