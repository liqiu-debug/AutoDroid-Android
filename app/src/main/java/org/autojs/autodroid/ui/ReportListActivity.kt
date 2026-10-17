package org.autojs.autodroid.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.autojs.autodroid.data.Session
import org.autojs.autodroid.net.AutoDroidApi
import org.autojs.autodroid.net.ExecutionItem
import org.autojs.autodroid.net.PaginatedExecutions
import org.autojs.autojs6.R
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * 报告中心：执行记录列表（点击查看 HTML 报告）。
 */
class ReportListActivity : AppCompatActivity() {

    private lateinit var adapter: ExecutionAdapter
    private lateinit var statusText: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report_list)

        AdHeader.bind(this, "报告中心", showBack = false)
        AdBottomNav.bind(this, "reports")
        statusText = findViewById(R.id.text_status)
        adapter = ExecutionAdapter()
        swipeRefresh = findViewById(R.id.swipe_refresh)
        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.ad_primary))
        swipeRefresh.setOnRefreshListener { load() }
        val listView = findViewById<ListView>(R.id.list_reports)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position)
            startActivity(
                Intent(this, ReportDetailActivity::class.java)
                    .putExtra(ReportDetailActivity.EXTRA_EXECUTION_ID, item.id.toLong())
                    .putExtra(ReportDetailActivity.EXTRA_TITLE, item.scenario_name ?: "执行报告")
            )
        }

        load()
    }

    private fun load() {
        statusText.text = "加载中…"
        AutoDroidApi.service(Session.serverUrl)
            .listExecutions()
            .enqueue(object : Callback<PaginatedExecutions> {
                override fun onResponse(call: Call<PaginatedExecutions>, response: Response<PaginatedExecutions>) {
                    if (swipeRefresh.isRefreshing) swipeRefresh.isRefreshing = false
                    val body = response.body()
                    if (body == null) {
                        statusText.text = "加载失败（HTTP ${response.code()}）"
                        return
                    }
                    adapter.setItems(body.items)
                    statusText.text = if (body.items.isEmpty()) "暂无执行记录" else "共 ${body.total} 条执行记录"
                }

                override fun onFailure(call: Call<PaginatedExecutions>, t: Throwable) {
                    if (swipeRefresh.isRefreshing) swipeRefresh.isRefreshing = false
                    statusText.text = "加载失败：${t.message}"
                }
            })
    }
}

class ExecutionAdapter : BaseAdapter() {

    private var items: List<ExecutionItem> = emptyList()

    fun setItems(newItems: List<ExecutionItem>) {
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
    override fun getItem(position: Int): ExecutionItem = items[position]
    override fun getItemId(position: Int): Long = items[position].id.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: android.view.LayoutInflater.from(parent?.context)
            .inflate(R.layout.item_case, parent, false)
        val item = items[position]
        val mark = when (item.status) {
            "PASS" -> "✓"
            "WARNING" -> "⚠"
            "RUNNING" -> "…"
            else -> "✗"
        }
        view.findViewById<TextView>(R.id.text_name).text = "$mark ${item.scenario_name ?: "执行 #${item.id}"}"
        val subtitle = buildString {
            append("#${item.id}")
            item.batch_name?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
            // 设备序列号（形如 autojs6:<androidId>）只展示标识部分
            item.device_serial?.takeIf { it.isNotBlank() }?.let { append(" · ${it.substringAfter(':')}") }
            item.start_time?.takeIf { it.length >= 19 }?.let { append(" · ${it.substring(0, 19).replace('T', ' ')}") }
            item.duration?.let { append(" · ${"%.1f".format(it)}s") }
        }
        view.findViewById<TextView>(R.id.text_sub).text = subtitle
        // 报告行只用于查看报告，不提供运行入口
        view.findViewById<View>(R.id.btn_run_row)?.visibility = View.GONE
        applyStatusBadge(view, item.status)
        return view
    }
}
