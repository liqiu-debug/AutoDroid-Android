package org.autojs.autodroid.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.autojs.autodroid.data.Session
import org.autojs.autodroid.net.AutoDroidApi
import org.autojs.autodroid.net.CaseItem
import org.autojs.autodroid.net.PaginatedCases
import org.autojs.autodroid.net.ScenarioStepItem
import org.autojs.autojs6.R
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * 场景详情：按顺序展示子用例（别名 + 用例名）+ 底部「运行此场景」。
 */
class ScenarioDetailActivity : AppCompatActivity() {

    private lateinit var adapter: CaseAdapter
    private lateinit var metaText: TextView
    private var caseNames: Map<Int, String> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scenario_detail)

        val scenarioId = intent.getIntExtra(EXTRA_SCENARIO_ID, -1)
        val scenarioName = intent.getStringExtra(EXTRA_SCENARIO_NAME) ?: "场景"
        AdHeader.bind(this, scenarioName)
        metaText = findViewById(R.id.text_meta)

        adapter = CaseAdapter(this)
        findViewById<ListView>(R.id.list_cases).adapter = adapter

        findViewById<Button>(R.id.btn_run).setOnClickListener {
            startActivity(
                Intent(this, RunActivity::class.java)
                    .putExtra(RunActivity.EXTRA_KIND, "scenario")
                    .putExtra(RunActivity.EXTRA_CASE_ID, scenarioId)
                    .putExtra(RunActivity.EXTRA_CASE_NAME, scenarioName)
            )
        }

        if (scenarioId < 0) {
            metaText.text = "缺少场景 ID"
            return
        }
        metaText.text = "场景 ID: $scenarioId · 加载中…"
        loadCaseNames {
            loadSteps(scenarioId)
        }
    }

    /** 先拉用例列表用于 id→名称 映射（失败也不阻塞，退化为显示 ID）。 */
    private fun loadCaseNames(onDone: () -> Unit) {
        AutoDroidApi.service(Session.serverUrl)
            .listCases(limit = 500)
            .enqueue(object : Callback<PaginatedCases> {
                override fun onResponse(call: Call<PaginatedCases>, response: Response<PaginatedCases>) {
                    caseNames = (response.body()?.items ?: emptyList()).associate { it.id to (it.name ?: "未命名") }
                    onDone()
                }

                override fun onFailure(call: Call<PaginatedCases>, t: Throwable) = onDone()
            })
    }

    private fun loadSteps(scenarioId: Int) {
        AutoDroidApi.service(Session.serverUrl)
            .scenarioSteps(scenarioId)
            .enqueue(object : Callback<List<ScenarioStepItem>> {
                override fun onResponse(
                    call: Call<List<ScenarioStepItem>>,
                    response: Response<List<ScenarioStepItem>>,
                ) {
                    val steps = response.body()
                    if (steps == null) {
                        metaText.text = "加载失败（HTTP ${response.code()}）"
                        return
                    }
                    val items = steps.sortedBy { it.order }.map { step ->
                        CaseItem(
                            id = step.case_id,
                            name = (step.alias?.takeIf { it.isNotBlank() }?.let { "$it · " } ?: "") +
                                (caseNames[step.case_id] ?: "用例 #${step.case_id}"),
                        )
                    }
                    adapter.setItems(items)
                    metaText.text = "场景 ID: $scenarioId · 共 ${items.size} 个子用例"
                }

                override fun onFailure(call: Call<List<ScenarioStepItem>>, t: Throwable) {
                    metaText.text = "加载失败：${t.message}"
                }
            })
    }

    companion object {
        const val EXTRA_SCENARIO_ID = "scenario_id"
        const val EXTRA_SCENARIO_NAME = "scenario_name"
    }
}
