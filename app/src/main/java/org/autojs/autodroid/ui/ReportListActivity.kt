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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report_list)

        statusText = findViewById(R.id.text_status)
        adapter = ExecutionAdapter()
        val listView = findViewById<ListView>(R.id.list_reports)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position)
            startActivity(
                Intent(this, ReportDetailActivity::class.java)
                    .putExtra(ReportDetailActivity.EXTRA_EXECUTION_ID, item.id)
                    .putExtra(ReportDetailActivity.EXTRA_TITLE, item.scenario_name ?: "执行报告")
            )
        }

        findViewById<Button>(R.id.btn_refresh).setOnClickListener { load() }
        load()
    }

    private fun load() {
        statusText.text = "加载中…"
        AutoDroidApi.service(Session.serverUrl) { Session.token }
            .listExecutions()
            .enqueue(object : Callback<PaginatedExecutions> {
                override fun onResponse(call: Call<PaginatedExecutions>, response: Response<PaginatedExecutions>) {
                    val body = response.body()
                    if (body == null) {
                        statusText.text = "加载失败（HTTP ${response.code()}）"
                        return
                    }
                    adapter.setItems(body.items)
                    statusText.text = "共 ${body.total} 条执行记录"
                }

                override fun onFailure(call: Call<PaginatedExecutions>, t: Throwable) {
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
            item.device_serial?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
            item.start_time?.takeIf { it.length >= 19 }?.let { append(" · ${it.substring(0, 19).replace('T', ' ')}") }
            item.duration?.let { append(" · ${"%.1f".format(it)}s") }
        }
        view.findViewById<TextView>(R.id.text_sub).text = subtitle
        return view
    }
}
