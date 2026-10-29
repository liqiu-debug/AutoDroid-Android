package org.autojs.autodroid.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.autojs.autodroid.data.Session
import org.autojs.autodroid.net.AutoDroidApi
import org.autojs.autodroid.net.PaginatedScenarios
import org.autojs.autodroid.net.ScenarioItem
import org.autojs.autodroid.net.PageLoader
import org.autojs.autojs6.R

/**
 * 场景列表：搜索 + 点击运行（场景脚本包含全部子用例与串联入口）。
 */
class ScenarioListActivity : AppCompatActivity() {

    private lateinit var adapter: ScenarioAdapter
    private lateinit var statusText: TextView
    private lateinit var keywordInput: EditText
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var pager: PageLoader<PaginatedScenarios, ScenarioItem>
    private var loadedKeyword: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scenario_list)

        AdHeader.bind(this, "场景", showBack = false)
        AdBottomNav.bind(this, "scenarios")
        statusText = findViewById(R.id.text_status)
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)
        swipeRefresh = swipe
        swipe.setColorSchemeColors(ContextCompat.getColor(this, R.color.ad_primary))
        swipe.setOnRefreshListener { load() }
        adapter = ScenarioAdapter(this) { item ->
            startActivity(
                Intent(this, RunActivity::class.java)
                    .putExtra(RunActivity.EXTRA_KIND, "scenario")
                    .putExtra(RunActivity.EXTRA_CASE_ID, item.id)
                    .putExtra(RunActivity.EXTRA_CASE_NAME, item.name)
            )
        }

        val listView = findViewById<ListView>(R.id.list_scenarios)
        listView.adapter = adapter

        // 搜索区作为列表 header：下拉刷新与列表滚动时一起移动
        val header = layoutInflater.inflate(R.layout.view_scenario_list_header, listView, false)
        listView.addHeaderView(header, null, false)
        keywordInput = header.findViewById(R.id.input_keyword)
        header.findViewById<Button>(R.id.btn_search).setOnClickListener { load() }

        pager = PageLoader(
            request = { skip -> AutoDroidApi.service(Session.serverUrl).listScenarios(skip = skip, keyword = loadedKeyword) },
            unpack = { (it.items ?: emptyList()) to it.total },
        )
        bindPagedList(listView, pager, statusText, swipeRefresh, "暂无场景", { "共 $it 个场景" }, adapter::setItems, emptyHint = "新建场景后会出现在这里", emptyIcon = R.drawable.ic_ad_scenarios)

        listView.setOnItemClickListener { _, _, position, _ ->
            val index = position - listView.headerViewsCount
            if (index !in 0 until adapter.count) return@setOnItemClickListener
            val item = adapter.getItem(index)
            startActivity(
                Intent(this, ScenarioDetailActivity::class.java)
                    .putExtra(ScenarioDetailActivity.EXTRA_SCENARIO_ID, item.id)
                    .putExtra(ScenarioDetailActivity.EXTRA_SCENARIO_NAME, item.name)
            )
        }

        PullDownMoveHelper.attach(swipe, listView)
        load()
    }

    private fun load() {
        loadedKeyword = keywordInput.text.toString().trim().takeIf { it.isNotEmpty() }
        pager.refresh()
    }

    override fun onDestroy() {
        pager.cancel()
        super.onDestroy()
    }
}

class ScenarioAdapter(
    private val context: Context,
    private val onRun: ((ScenarioItem) -> Unit)? = null,
) : BaseAdapter() {

    private var items: List<ScenarioItem> = emptyList()

    fun setItems(newItems: List<ScenarioItem>) {
        items = newItems
        notifyDataSetChanged()
    }
    private fun applyStatusBadge(view: View, status: String?) {
        val badge = view.findViewById<TextView>(R.id.text_badge) ?: return
        if (status.isNullOrBlank()) {
            badge.visibility = View.GONE
            return
        }
        badge.visibility = View.VISIBLE
        val (bgRes, colorRes) = when (status.uppercase()) {
            "PASS" -> R.drawable.ad_chip_pass to R.color.ad_pass
            "FAIL", "FAILED", "ERROR" -> R.drawable.ad_chip_fail to R.color.ad_fail
            "WARNING" -> R.drawable.ad_chip_warn to R.color.ad_warn
            "RUNNING" -> R.drawable.ad_chip_idle to R.color.ad_primary
            else -> R.drawable.ad_chip_idle to R.color.ad_idle
        }
        badge.setBackgroundResource(bgRes)
        badge.setTextColor(androidx.core.content.ContextCompat.getColor(view.context, colorRes))
        badge.text = status
    }


    override fun getCount(): Int = items.size
    override fun getItem(position: Int): ScenarioItem = items[position]
    override fun getItemId(position: Int): Long = items[position].id.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_case, parent, false)
        val item = items[position]
        view.findViewById<TextView>(R.id.text_name).text = item.name ?: "未命名"
        val subtitle = buildString {
            append("id=${item.id}")
            item.last_run_status?.takeIf { it.isNotBlank() }?.let { append(" · 最近: $it") }
            item.description?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
        }
        view.findViewById<TextView>(R.id.text_sub).text = subtitle
        applyStatusBadge(view, item.last_run_status)
        val runButton = view.findViewById<Button>(R.id.btn_run_row)
        if (onRun != null) {
            runButton.visibility = View.VISIBLE
            runButton.setOnClickListener { onRun.invoke(item) }
        } else {
            runButton.visibility = View.GONE
        }
        return view
    }
}
