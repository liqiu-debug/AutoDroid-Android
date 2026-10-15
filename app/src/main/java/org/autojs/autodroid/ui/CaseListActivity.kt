package org.autojs.autodroid.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.autojs.autodroid.data.Session
import org.autojs.autodroid.net.AutoDroidApi
import org.autojs.autodroid.net.CaseItem
import org.autojs.autodroid.net.FolderNode
import org.autojs.autodroid.net.FolderTreeResponse
import org.autojs.autodroid.net.PaginatedCases
import org.autojs.autojs6.R
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * 用例列表：文件夹筛选 + 关键字搜索 + 列表（点击运行，运行桥下个增量接入）。
 */
class CaseListActivity : AppCompatActivity() {

    private lateinit var adapter: CaseAdapter
    private lateinit var statusText: TextView
    private lateinit var spinner: Spinner
    private lateinit var keywordInput: EditText
    private var folderChoices: MutableList<Pair<String, Int?>> = mutableListOf("全部用例" to null)
    private var selectedFolderId: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_case_list)

        statusText = findViewById(R.id.text_status)
        spinner = findViewById(R.id.spinner_folder)
        keywordInput = findViewById(R.id.input_keyword)

        adapter = CaseAdapter(this)
        val listView = findViewById<ListView>(R.id.list_cases)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position)
            startActivity(
                Intent(this, RunActivity::class.java)
                    .putExtra(RunActivity.EXTRA_CASE_ID, item.id)
                    .putExtra(RunActivity.EXTRA_CASE_NAME, item.name)
            )
        }

        findViewById<Button>(R.id.btn_search).setOnClickListener { loadCases() }
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_scenarios).setOnClickListener {
            Toast.makeText(this, "场景列表下个增量接入", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_reports).setOnClickListener {
            startActivity(Intent(this, ReportListActivity::class.java))
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedFolderId = folderChoices.getOrNull(position)?.second
                loadCases()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        loadFolders()
        loadCases()
    }

    private fun api() = AutoDroidApi.service(Session.serverUrl) { Session.token }

    private fun loadFolders() {
        api().folderTree().enqueue(object : Callback<FolderTreeResponse> {
            override fun onResponse(call: Call<FolderTreeResponse>, response: Response<FolderTreeResponse>) {
                val tree = response.body()?.tree ?: return
                val choices = mutableListOf<Pair<String, Int?>>("全部用例" to null)
                flattenFolders(tree, 0, choices)
                folderChoices = choices
                spinner.adapter = ArrayAdapter(
                    this@CaseListActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    choices.map { it.first },
                )
            }

            override fun onFailure(call: Call<FolderTreeResponse>, t: Throwable) {
                statusText.text = "目录加载失败：${t.message}"
            }
        })
    }

    private fun flattenFolders(nodes: List<FolderNode>, depth: Int, out: MutableList<Pair<String, Int?>>) {
        for (node in nodes) {
            if (node.type == "folder") {
                val rawId = node.id?.removePrefix("folder-")?.toIntOrNull()
                val indent = "　".repeat(depth)
                out.add("$indent${node.name ?: "未命名目录"}" to rawId)
                node.children?.let { flattenFolders(it, depth + 1, out) }
            }
        }
    }

    private fun loadCases() {
        statusText.text = "加载中…"
        val keyword = keywordInput.text.toString().trim().takeIf { it.isNotEmpty() }
        api().listCases(keyword = keyword, folderId = selectedFolderId)
            .enqueue(object : Callback<PaginatedCases> {
                override fun onResponse(call: Call<PaginatedCases>, response: Response<PaginatedCases>) {
                    val body = response.body()
                    if (body == null) {
                        statusText.text = "加载失败（HTTP ${response.code()}）"
                        return
                    }
                    adapter.setItems(body.items)
                    statusText.text = "共 ${body.total} 条用例"
                }

                override fun onFailure(call: Call<PaginatedCases>, t: Throwable) {
                    statusText.text = "加载失败：${t.message}"
                }
            })
    }
}

/** 用例列表适配器（名称 + 状态/描述副标题）。 */
class CaseAdapter(private val context: Context) : BaseAdapter() {

    private var items: List<CaseItem> = emptyList()

    fun setItems(newItems: List<CaseItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): CaseItem = items[position]
    override fun getItemId(position: Int): Long = items[position].id.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_case, parent, false)
        val item = items[position]
        view.findViewById<TextView>(R.id.text_name).text = item.name
        val subtitle = buildString {
            append("id=${item.id}")
            item.last_run_status?.takeIf { it.isNotBlank() }?.let { append(" · 最近: $it") }
            item.description?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
        }
        view.findViewById<TextView>(R.id.text_sub).text = subtitle
        return view
    }
}
