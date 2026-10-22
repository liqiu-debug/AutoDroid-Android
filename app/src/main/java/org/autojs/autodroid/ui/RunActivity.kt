package org.autojs.autodroid.ui

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.autojs.autodroid.data.Session
import org.autojs.autodroid.net.AutoDroidApi
import org.autojs.autodroid.net.ScriptBundle
import org.autojs.autodroid.run.RunBridge
import org.autojs.autodroid.run.RunNotifier
import org.autojs.autodroid.run.RunCoordinator
import org.autojs.autojs6.R
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.util.UUID

/**
 * 运行页：拉取脚本包 → 写入运行目录 → AutoJs6 引擎执行 → 轮询 report.json 回显结果。
 */
class RunActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var notifySwitch: Switch
    private lateinit var adapter: RunStepAdapter
    private lateinit var progressBar: android.widget.ProgressBar
    private var runDir: File? = null
    private var pollStartedAt = 0L
    private var scriptCall: Call<ScriptBundle>? = null
    private var preparingRunID: String? = null

    private val pollRunnable = object : Runnable {
        override fun run() = pollOnce()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_run)

        val caseId = intent.getIntExtra(EXTRA_CASE_ID, -1)
        val caseName = intent.getStringExtra(EXTRA_CASE_NAME) ?: "用例"
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "case"

        AdHeader.bind(this, caseName)
        statusText = findViewById(R.id.text_status)
        resultText = findViewById(R.id.text_result)
        notifySwitch = findViewById(R.id.switch_notify)
        AdSwitchTint.apply(notifySwitch)
        notifySwitch.isChecked = Session.notifyDefault

        progressBar = findViewById(R.id.progress_run)
        adapter = RunStepAdapter(this)
        findViewById<ListView>(R.id.list_steps).adapter = adapter

        findViewById<Button>(R.id.btn_run).setOnClickListener { startRun(kind, caseId, caseName) }

        val restoredID = savedInstanceState?.getString("run_id")
        val restoredDir = restoredID?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
            ?.let { File(filesDir, "runs/$it") }?.takeIf { it.isDirectory }
        if (restoredDir != null) {
            runDir = restoredDir
            pollStartedAt = savedInstanceState.getLong("poll_started", System.currentTimeMillis())
            findViewById<Button>(R.id.btn_run).isEnabled = !RunCoordinator.owns(restoredDir.name)
            progressBar.visibility = View.VISIBLE
            handler.post(pollRunnable)
        } else if (caseId >= 0 && savedInstanceState == null) {
            startRun(kind, caseId, caseName)
        } else if (caseId >= 0) {
            statusText.text = "准备已中断，可点击重新运行"
        } else {
            statusText.text = "缺少用例 ID"
        }
    }

    private fun startRun(kind: String, targetId: Int, targetName: String) {
        if (targetId < 0) {
            statusText.text = "缺少目标 ID"
            return
        }
        val server = Session.serverUrl
        val token = Session.token
        val account = Session.username
        if (token.isNullOrBlank()) {
            statusText.text = "登录已过期，请重新登录"
            return
        }
        val runId = UUID.randomUUID().toString()
        if (!RunCoordinator.reserve(runId)) {
            statusText.text = "本机已有任务正在准备或执行，请等待结束"
            return
        }
        preparingRunID = runId
        findViewById<Button>(R.id.btn_run).isEnabled = false
        handler.removeCallbacks(pollRunnable)
        adapter.setSteps(emptyList())
        resultText.text = ""
        statusText.text = "正在拉取脚本包…"

        val api = AutoDroidApi.service(Session.serverUrl)
        val call = if (kind == "scenario") {
            api.scenarioScript(targetId)
        } else {
            api.caseScript(targetId)
        }
        scriptCall = call
        call.enqueue(object : Callback<ScriptBundle> {
                override fun onResponse(call: Call<ScriptBundle>, response: Response<ScriptBundle>) {
                    if (isDestroyed || isFinishing) {
                        releasePreparation(runId)
                        return
                    }
                    if (Session.token != token || Session.serverUrl != server || Session.username != account) {
                        releasePreparation(runId)
                        statusText.text = "登录状态已变化，请重新登录后运行"
                        return
                    }
                    if (!response.isSuccessful) {
                        val detail = try {
                            response.errorBody()?.string()?.take(500)
                        } catch (e: Exception) {
                            null
                        }
                        statusText.text = "脚本下发失败（HTTP ${response.code()}）\n${detail ?: ""}"
                        releasePreparation(runId)
                        return
                    }
                    val bundle = response.body()
                    if (bundle == null) {
                        statusText.text = "脚本包为空"
                        releasePreparation(runId)
                        return
                    }
                    try {
                        val dir = RunBridge.prepareRunDir(
                            this@RunActivity, bundle, runId, notifySwitch.isChecked, server, token
                        )
                        runDir = dir
                        RunBridge.pruneRuns(this@RunActivity, currentDir = dir)
                        statusText.text = "执行中…（$targetName）"
                        progressBar.visibility = View.VISIBLE
                        RunCoordinator.start(dir, runId) {
                            if (File(dir, "report.json").isFile) {
                                RunNotifier.watchResultAndNotify(applicationContext, dir, kind, targetName)
                            }
                            handler.post {
                                if (!isDestroyed && !isFinishing && runDir == dir) {
                                    findViewById<Button>(R.id.btn_run).isEnabled = true
                                    handler.removeCallbacks(pollRunnable)
                                    pollOnce()
                                }
                            }
                        }
                        preparingRunID = null
                        pollStartedAt = System.currentTimeMillis()
                        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
                    } catch (e: Exception) {
                        statusText.text = "运行准备失败：${e.message}"
                        releasePreparation(runId)
                        progressBar.visibility = View.GONE
                    }
                }

                override fun onFailure(call: Call<ScriptBundle>, t: Throwable) {
                    releasePreparation(runId)
                    if (!isDestroyed && !call.isCanceled) statusText.text = "拉取脚本包失败：${t.message}"
                }
            })
    }

    private fun releasePreparation(id: String) {
        RunCoordinator.release(id)
        if (preparingRunID == id) preparingRunID = null
        if (!isDestroyed) findViewById<Button>(R.id.btn_run).isEnabled = true
    }

    private fun pollOnce() {
        val dir = runDir ?: return
        val report = File(dir, "report.json")
        val running = RunCoordinator.owns(dir.name)
        if (report.exists() && !running) {
            try {
                showResult(RunBridge.parseReport(report))
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                statusText.text = "执行完成，但结果解析失败：${e.message}"
            }
            return
        }
        val engineError = File(dir, "engine-error.txt")
        if (!running && engineError.isFile) {
            progressBar.visibility = View.GONE
            statusText.text = "执行失败：${engineError.readText()}"
            findViewById<Button>(R.id.btn_run).isEnabled = true
            return
        }
        val elapsed = System.currentTimeMillis() - pollStartedAt
        if (elapsed > POLL_TIMEOUT_MS) {
            progressBar.visibility = View.GONE
            statusText.text = "等待执行结果超时（脚本可能仍在后台运行，稍后可在报告中心查看）"
            return
        }
        statusText.text = if (elapsed > 120_000) {
            "执行中…（已等待 ${elapsed / 1000} 秒），请等待当前任务结束"
        } else {
            "执行中…（已等待 ${elapsed / 1000} 秒）"
        }
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        runDir?.let { outState.putString("run_id", it.name) }
        outState.putLong("poll_started", pollStartedAt)
        super.onSaveInstanceState(outState)
    }

    private fun showResult(result: RunBridge.RunResult) {
        progressBar.visibility = View.GONE
        statusText.text = if (result.success) "执行完成：全部通过 ✓" else "执行完成：存在失败步骤 ✗"
        adapter.setSteps(result.steps)
        resultText.text = buildString {
            append("上报平台：")
            append(if (result.uploadOk) "成功" else "失败（结果已保存在本机 report.json）")
            result.executionId?.let { append("\n平台执行记录：#$it") }
            result.reportId?.let { append("\n报告文件：$it") }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pollRunnable)
        scriptCall?.cancel()
        preparingRunID?.let { RunCoordinator.release(it) }
    }

    companion object {
        const val EXTRA_CASE_ID = "case_id"
        const val EXTRA_CASE_NAME = "case_name"
        const val EXTRA_KIND = "target_kind" // case | scenario
        private const val POLL_INTERVAL_MS = 1500L
        private const val POLL_TIMEOUT_MS = 15 * 60 * 1000L
    }
}

/** 步骤结果列表适配器。 */
class RunStepAdapter(private val context: Context) : BaseAdapter() {

    private var steps: List<RunBridge.RunStep> = emptyList()

    fun setSteps(newSteps: List<RunBridge.RunStep>) {
        steps = newSteps
        notifyDataSetChanged()
    }

    override fun getCount(): Int = steps.size
    override fun getItem(position: Int): RunBridge.RunStep = steps[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_run_step, parent, false)
        val step = steps[position]
        val (mark, colorRes) = when (step.status) {
            "PASS" -> "✓" to R.color.ad_pass
            "SKIP" -> "⏭" to R.color.ad_idle
            "WARNING" -> "⚠" to R.color.ad_warn
            else -> "✗" to R.color.ad_fail
        }
        view.findViewById<TextView>(R.id.text_mark).apply {
            text = mark
            setTextColor(androidx.core.content.ContextCompat.getColor(context, colorRes))
        }
        view.findViewById<TextView>(R.id.text_step).text =
            "${step.order}. ${step.action} · ${step.status} · ${"%.2f".format(step.duration)}s"
        val errorView = view.findViewById<TextView>(R.id.text_step_error)
        if (step.error.isNullOrBlank()) {
            errorView.visibility = View.GONE
        } else {
            errorView.visibility = View.VISIBLE
            errorView.text = step.error
        }
        return view
    }
}
