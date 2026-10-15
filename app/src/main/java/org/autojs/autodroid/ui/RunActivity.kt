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
import org.autojs.autojs.model.script.ScriptFile
import org.autojs.autojs.model.script.Scripts
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
    private var runDir: File? = null
    private var pollStartedAt = 0L

    private val pollRunnable = object : Runnable {
        override fun run() = pollOnce()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_run)

        val caseId = intent.getIntExtra(EXTRA_CASE_ID, -1)
        val caseName = intent.getStringExtra(EXTRA_CASE_NAME) ?: "用例"
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "case"

        findViewById<TextView>(R.id.text_title).text = caseName
        statusText = findViewById(R.id.text_status)
        resultText = findViewById(R.id.text_result)
        notifySwitch = findViewById(R.id.switch_notify)
        notifySwitch.isChecked = Session.notifyDefault

        adapter = RunStepAdapter(this)
        findViewById<ListView>(R.id.list_steps).adapter = adapter

        findViewById<Button>(R.id.btn_run).setOnClickListener { startRun(kind, caseId, caseName) }

        if (caseId >= 0) {
            startRun(kind, caseId, caseName)
        } else {
            statusText.text = "缺少用例 ID"
        }
    }

    private fun startRun(kind: String, targetId: Int, targetName: String) {
        if (targetId < 0) {
            statusText.text = "缺少目标 ID"
            return
        }
        handler.removeCallbacks(pollRunnable)
        adapter.setSteps(emptyList())
        resultText.text = ""
        statusText.text = "正在拉取脚本包…"

        val api = AutoDroidApi.service(Session.serverUrl) { Session.token }
        val call = if (kind == "scenario") {
            api.scenarioScript(targetId)
        } else {
            api.caseScript(targetId)
        }
        call.enqueue(object : Callback<ScriptBundle> {
                override fun onResponse(call: Call<ScriptBundle>, response: Response<ScriptBundle>) {
                    if (!response.isSuccessful) {
                        val detail = try {
                            response.errorBody()?.string()?.take(500)
                        } catch (e: Exception) {
                            null
                        }
                        statusText.text = "脚本下发失败（HTTP ${response.code()}）\n${detail ?: ""}"
                        return
                    }
                    val bundle = response.body()
                    if (bundle == null) {
                        statusText.text = "脚本包为空"
                        return
                    }
                    try {
                        val runId = UUID.randomUUID().toString()
                        val dir = RunBridge.prepareRunDir(
                            this@RunActivity, bundle, runId, notifySwitch.isChecked
                        )
                        runDir = dir
                        RunBridge.pruneRuns(this@RunActivity)
                        statusText.text = "执行中…（$targetName）"
                        Scripts.run(
                            this@RunActivity,
                            ScriptFile(File(dir, "main.js").absolutePath),
                        )
                        pollStartedAt = System.currentTimeMillis()
                        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
                    } catch (e: Exception) {
                        statusText.text = "运行准备失败：${e.message}"
                    }
                }

                override fun onFailure(call: Call<ScriptBundle>, t: Throwable) {
                    statusText.text = "拉取脚本包失败：${t.message}"
                }
            })
    }

    private fun pollOnce() {
        val dir = runDir ?: return
        val report = File(dir, "report.json")
        if (report.exists()) {
            try {
                showResult(RunBridge.parseReport(report))
            } catch (e: Exception) {
                statusText.text = "执行完成，但结果解析失败：${e.message}"
            }
            return
        }
        val elapsed = System.currentTimeMillis() - pollStartedAt
        if (elapsed > POLL_TIMEOUT_MS) {
            statusText.text = "等待执行结果超时（脚本可能仍在后台运行，稍后可在报告中心查看）"
            return
        }
        statusText.text = "执行中…（已等待 ${elapsed / 1000} 秒）"
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun showResult(result: RunBridge.RunResult) {
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
        val mark = when (step.status) {
            "PASS" -> "✓"
            "SKIP" -> "⏭"
            "WARNING" -> "⚠"
            else -> "✗"
        }
        val text = buildString {
            append("$mark ${step.order}. ${step.action} · ${step.status} · ${"%.2f".format(step.duration)}s")
            step.error?.takeIf { it.isNotBlank() }?.let { append("\n    $it") }
        }
        view.findViewById<TextView>(R.id.text_step).text = text
        return view
    }
}
