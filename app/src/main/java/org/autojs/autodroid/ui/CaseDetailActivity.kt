package org.autojs.autodroid.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.autojs.autodroid.data.Session
import org.autojs.autodroid.net.AutoDroidApi
import org.autojs.autodroid.net.CaseStepItem
import org.autojs.autojs6.R
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * 用例详情：展示标准步骤（只读）+ 底部「运行此用例」。
 */
class CaseDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_case_detail)

        val caseId = intent.getIntExtra(EXTRA_CASE_ID, -1)
        val caseName = intent.getStringExtra(EXTRA_CASE_NAME) ?: "用例"
        AdHeader.bind(this, caseName)

        val metaText = findViewById<TextView>(R.id.text_meta)
        val adapter = StepAdapter()
        findViewById<ListView>(R.id.list_steps).adapter = adapter

        findViewById<Button>(R.id.btn_run).setOnClickListener {
            startActivity(
                Intent(this, RunActivity::class.java)
                    .putExtra(RunActivity.EXTRA_KIND, "case")
                    .putExtra(RunActivity.EXTRA_CASE_ID, caseId)
                    .putExtra(RunActivity.EXTRA_CASE_NAME, caseName)
            )
        }

        if (caseId < 0) {
            metaText.text = "缺少用例 ID"
            return
        }
        metaText.text = "用例 ID: $caseId · 加载步骤中…"
        AutoDroidApi.service(Session.serverUrl)
            .caseSteps(caseId)
            .enqueue(object : Callback<List<CaseStepItem>> {
                override fun onResponse(
                    call: Call<List<CaseStepItem>>,
                    response: Response<List<CaseStepItem>>,
                ) {
                    val steps = response.body()
                    if (steps == null) {
                        metaText.text = "步骤加载失败（HTTP ${response.code()}）"
                        return
                    }
                    adapter.setSteps(steps)
                    val platforms = steps.flatMap { it.execute_on ?: emptyList() }.distinct()
                    metaText.text = "用例 ID: $caseId · 共 ${steps.size} 步" +
                        if (platforms.isNotEmpty()) " · 适用平台: ${platforms.joinToString("/")}" else ""
                }

                override fun onFailure(call: Call<List<CaseStepItem>>, t: Throwable) {
                    metaText.text = "步骤加载失败：${t.message}"
                }
            })
    }

    companion object {
        const val EXTRA_CASE_ID = "case_id"
        const val EXTRA_CASE_NAME = "case_name"
    }
}

/** 步骤列表适配器：主行 = 动作摘要，副行 = 容错/超时/重试。 */
class StepAdapter : BaseAdapter() {

    private var steps: List<CaseStepItem> = emptyList()

    fun setSteps(newSteps: List<CaseStepItem>) {
        steps = newSteps
        notifyDataSetChanged()
    }

    override fun getCount(): Int = steps.size
    override fun getItem(position: Int): CaseStepItem = steps[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(parent?.context)
            .inflate(R.layout.item_step, parent, false)
        val step = steps[position]
        view.findViewById<TextView>(R.id.text_index).text = "${step.order}"
        view.findViewById<TextView>(R.id.text_step_main).text = stepSummary(step)
        val meta = buildString {
            step.description?.takeIf { it.isNotBlank() }?.let { append(it).append(" · ") }
            append("容错: ${step.error_strategy}")
            append(" · 超时: ${step.timeout}s")
            if (step.retry_count > 0) append(" · 重试: ${step.retry_count}")
        }
        view.findViewById<TextView>(R.id.text_step_meta).text = meta
        return view
    }

    companion object {
        private val ACTION_LABELS = mapOf(
            "click" to "点击",
            "input" to "输入",
            "wait_until_exists" to "等待元素",
            "assert_text" to "文本断言",
            "assert_image" to "图像断言",
            "click_image" to "图像点击",
            "extract_by_ocr" to "OCR 提取",
            "sleep" to "等待",
            "swipe" to "滑动",
            "back" to "返回",
            "home" to "主页",
            "start_app" to "启动应用",
            "stop_app" to "停止应用",
        )

        fun stepSummary(step: CaseStepItem): String {
            val label = ACTION_LABELS[step.action] ?: step.action
            val args = step.args
            val detail = when (step.action) {
                "click", "wait_until_exists", "input" -> locatorText(step)
                "assert_text" -> args?.get("expected_text")?.takeIf { !it.isJsonNull }?.asString
                    ?.let { "${assertTextMode(args)}「$it」" } ?: ""
                "assert_image" -> args?.get("image")?.takeIf { !it.isJsonNull }?.asString
                    ?.let { "${assertImageMode(args)} · 模板 $it" } ?: ""
                "click_image" -> args?.get("image")?.takeIf { !it.isJsonNull }?.asString
                    ?.let { "模板 $it" } ?: ""
                "extract_by_ocr" -> buildString {
                    args?.get("region")?.takeIf { !it.isJsonNull }?.let { append("区域 ${it}") }
                    args?.get("output_var")?.takeIf { !it.isJsonNull }?.asString?.let { append(" → $it") }
                }
                "sleep" -> args?.get("seconds")?.takeIf { !it.isJsonNull }?.asString?.let { "${it}s" } ?: ""
                "swipe" -> args?.get("direction")?.takeIf { !it.isJsonNull }?.asString ?: ""
                "start_app", "stop_app" -> args?.get("package")?.takeIf { !it.isJsonNull }?.asString
                    ?: args?.get("app_key")?.takeIf { !it.isJsonNull }?.asString ?: ""
                else -> ""
            }
            return if (detail.isNullOrBlank()) label else "$label · $detail"
        }

        /** 文本断言匹配模式：args.match_mode == "not_contains" → 不包含，否则包含。 */
        private fun assertTextMode(args: com.google.gson.JsonObject?): String {
            val mode = args?.get("match_mode")?.takeIf { !it.isJsonNull }?.asString
            return if (mode == "not_contains") "不包含" else "包含"
        }

        /** 图像断言匹配模式：args.match_mode == "not_exists" → 不存在，否则存在。 */
        private fun assertImageMode(args: com.google.gson.JsonObject?): String {
            val mode = args?.get("match_mode")?.takeIf { !it.isJsonNull }?.asString
            return if (mode == "not_exists") "不存在" else "存在"
        }

        private fun locatorText(step: CaseStepItem): String {
            val override = step.platform_overrides?.getAsJsonObject("android")
            val selector = override?.get("selector")?.takeIf { !it.isJsonNull }?.asString
            val by = override?.get("by")?.takeIf { !it.isJsonNull }?.asString
            if (!selector.isNullOrBlank()) {
                return if (by.isNullOrBlank()) selector else "$by=$selector"
            }
            val text = step.args?.get("text")?.takeIf { !it.isJsonNull }?.asString
            return text ?: ""
        }
    }
}
