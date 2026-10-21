package org.autojs.autodroid.run

import org.autojs.autodroid.net.AppPresence
import org.autojs.autojs.AutoJs
import org.autojs.autojs.execution.ExecutionConfig
import org.autojs.autojs.execution.ScriptExecution
import org.autojs.autojs.execution.SimpleScriptExecutionListener
import org.autojs.autojs.model.script.ScriptFile
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** One device can execute only one UI script, including script preparation. */
object RunCoordinator {
    private var activeID: String? = null

    @Synchronized
    fun isBusy(): Boolean = activeID != null || AutoJs.instance.scriptEngineService.engines.isNotEmpty()

    @Synchronized
    fun reserve(id: String): Boolean {
        if (isBusy()) return false
        activeID = id
        return true
    }

    @Synchronized
    fun owns(id: String): Boolean = activeID == id

    @Synchronized
    fun release(id: String) { if (activeID == id) activeID = null }

    fun start(dir: File, id: String, onFinished: () -> Unit = {}): ScriptExecution {
        check(owns(id)) { "此运行未持有设备执行权" }
        val main = File(dir, "main.js")
        check(main.isFile) { "脚本包缺少 main.js" }
        val completed = AtomicBoolean(false)
        fun finish(error: Throwable?) {
            if (!completed.compareAndSet(false, true)) return
            try {
                if (error != null || !File(dir, "report.json").isFile) {
                    File(dir, "engine-error.txt").writeText(error?.message ?: "脚本结束但未生成执行结果")
                }
            } finally {
                release(id)
                AppPresence.refreshNow()
                onFinished()
            }
        }
        return try {
            AutoJs.instance.scriptEngineService.execute(
                ScriptFile(main.absolutePath).toSource(),
                object : SimpleScriptExecutionListener() {
                    override fun onSuccess(execution: ScriptExecution, result: Any?) { finish(null) }
                    override fun onException(execution: ScriptExecution, e: Throwable) { finish(e) }
                },
                ExecutionConfig(workingDirectory = dir.absolutePath),
            ).also { AppPresence.refreshNow() }
        } catch (error: Exception) {
            finish(error)
            throw error
        }
    }
}
