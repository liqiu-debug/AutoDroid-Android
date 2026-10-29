package org.autojs.autodroid.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import org.autojs.autodroid.net.CaseItem
import org.autojs.autodroid.net.FolderNode
import org.autojs.autodroid.net.FolderTreeResponse
import org.autojs.autodroid.net.PaginatedCases
import org.autojs.autodroid.net.PageLoader
import org.autojs.autojs6.R
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * 用例列表：目录选择（弹窗目录树）+ 关键字搜索 + 下拉刷新；
 * 行内「运行」按钮直接执行，点击行进入用例详情。
 */
class CaseListActivity : AppCompatActivity() {

    private lateinit var adapter: CaseAdapter
    private lateinit var statusText: TextView
    private lateinit var folderText: TextView
    private lateinit var keywordInput: EditText
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var folderChoices: List<FolderChoice> = emptyList()
    private var selectedFolderId: Int? = null
    private lateinit var pager: PageLoader<PaginatedCases, CaseItem>
    private var loadedKeyword: String? = null
    private var loadedFolderId: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_case_list)

        AdHeader.bind(this, "用例", showBack = false)
        AdBottomNav.bind(this, "cases")
        requestNotificationPermissionIfNeeded()

        statusText = findViewById(R.id.text_status)
        swipeRefresh = findViewById(R.id.swipe_refresh)
        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.ad_primary))

        adapter = CaseAdapter(this, showRunButton = true) { item ->
            startActivity(
                Intent(this, RunActivity::class.java)
                    .putExtra(RunActivity.EXTRA_KIND, "case")
                    .putExtra(RunActivity.EXTRA_CASE_ID, item.id)
                    .putExtra(RunActivity.EXTRA_CASE_NAME, item.name)
            )
        }
        val listView = findViewById<ListView>(R.id.list_cases)
        listView.adapter = adapter

        // 顶部区（搜索 + 目录）作为列表 header：下拉刷新与列表滚动时一起移动
        val header = layoutInflater.inflate(R.layout.view_case_list_header, listView, false)
        listView.addHeaderView(header, null, false)
        keywordInput = header.findViewById(R.id.input_keyword)
        folderText = header.findViewById(R.id.text_folder)
        header.findViewById<Button>(R.id.btn_search).setOnClickListener { loadCases() }
        header.findViewById<View>(R.id.row_folder).setOnClickListener { showFolderDialog() }

        pager = PageLoader(
            request = { skip -> api().listCases(skip = skip, keyword = loadedKeyword, folderId = loadedFolderId) },
            unpack = { (it.items ?: emptyList()) to it.total },
        )
        bindPagedList(listView, pager, statusText, swipeRefresh, "暂无用例", { "共 $it 条用例" }, adapter::setItems, emptyHint = "新建用例后会出现在这里", emptyIcon = R.drawable.ic_ad_cases)

        listView.setOnItemClickListener { _, _, position, _ ->
            val index = position - listView.headerViewsCount
            if (index !in 0 until adapter.count) return@setOnItemClickListener
            val item = adapter.getItem(index)
            startActivity(
                Intent(this, CaseDetailActivity::class.java)
                    .putExtra(CaseDetailActivity.EXTRA_CASE_ID, item.id)
                    .putExtra(CaseDetailActivity.EXTRA_CASE_NAME, item.name)
            )
        }

        swipeRefresh.setOnRefreshListener {
            loadFolders()
            loadCases()
        }
        PullDownMoveHelper.attach(swipeRefresh, listView)

        loadFolders()
        loadCases()
        window.decorView.post { checkAccessibilityGuide() }
    }

    /** 每次打开 App 检查无障碍服务；未开启（或已开启但未运行）时弹窗引导。 */
    private fun checkAccessibilityGuide() {
        if (isFinishing || isDestroyed) return
        val tool = org.autojs.autojs.core.accessibility.AccessibilityTool(this)
        if (runCatching { tool.isRunning() }.getOrDefault(false)) return
        val hasService = runCatching { tool.hasService() }.getOrDefault(false)
        AdGuideDialog.show(
            this,
            title = "无障碍服务未开启",
            message = if (hasService) {
                "检测到无障碍服务已开启但未运行（部分机型常见）。请到「系统设置 → 无障碍」里关闭再打开 AutoDroid 的开关；若仍无效，重启一次设备。"
            } else {
                "AutoDroid 需要无障碍服务才能执行用例 / 场景。请到「系统设置 → 无障碍」中找到 AutoDroid 并开启。"
            },
            positiveText = "去开启",
        ) {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }

    private fun api() = AutoDroidApi.service(Session.serverUrl)

    // ------------------------------------------------------------------ //
    //  目录选择
    // ------------------------------------------------------------------ //
    private data class FolderChoice(val label: String, val folderId: Int?, val count: Int)

    private fun loadFolders() {
        api().folderTree().enqueue(object : Callback<FolderTreeResponse> {
            override fun onResponse(call: Call<FolderTreeResponse>, response: Response<FolderTreeResponse>) {
                val body = response.body() ?: return
                val choices = mutableListOf<FolderChoice>()
                flattenFolders(body.tree ?: emptyList(), 0, choices)
                // 注意: 树内只包含已归目录的用例, "全部用例" 不带计数以免误导
                folderChoices = listOf(FolderChoice("全部用例", null, 0)) + choices
            }

            override fun onFailure(call: Call<FolderTreeResponse>, t: Throwable) {
            }
        })
    }

    /** 递归展平目录树（父目录先出现、子目录缩进），返回该子树下的用例数。 */
    private fun flattenFolders(nodes: List<FolderNode>, depth: Int, out: MutableList<FolderChoice>): Int {
        var total = 0
        for (node in nodes) {
            if (node.type == "folder") {
                val index = out.size
                out.add(FolderChoice("", node.id?.removePrefix("folder-")?.toIntOrNull(), 0))
                val childCount = node.children?.let { flattenFolders(it, depth + 1, out) } ?: 0
                out[index] = out[index].copy(
                    label = "　".repeat(depth) + (node.name ?: "未命名目录"),
                    count = childCount,
                )
                total += childCount
            } else if (node.is_leaf == true) {
                total += 1
            }
        }
        return total
    }

    /** 目录选择：与定时任务的目标选择同一套弹窗（圆角卡片 + 搜索 + 当前选中高亮）。 */
    private fun showFolderDialog() {
        PickerDialog.show(
            this,
            title = "选择目录",
            emptyText = if (folderChoices.isEmpty()) "目录加载失败，可下拉刷新重试" else "没有匹配的目录",
            options = folderChoices.map { choice ->
                val selected = choice.folderId == selectedFolderId
                PickerDialog.Option(
                    key = choice.folderId,
                    name = choice.label.trim(),
                    badge = when {
                        selected -> "当前"
                        choice.count > 0 -> "${choice.count} 个"
                        else -> null
                    },
                    highlight = selected,
                    searchKey = choice.folderId?.toString().orEmpty(),
                )
            },
        ) { picked ->
            selectedFolderId = picked.key
            folderText.text = "目录：${picked.name}"
            loadCases()
        }
    }

    // ------------------------------------------------------------------ //
    //  用例列表
    // ------------------------------------------------------------------ //
    private fun loadCases() {
        loadedKeyword = keywordInput.text.toString().trim().takeIf { it.isNotEmpty() }
        loadedFolderId = selectedFolderId
        pager.refresh()
    }

    override fun onDestroy() {
        pager.cancel()
        super.onDestroy()
    }

    /** Android 13+ 需运行时通知权限（运行完成通知、定时任务通知依赖）。 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_CODE_NOTIFICATIONS,
            )
        }
    }

    companion object {
        private const val REQUEST_CODE_NOTIFICATIONS = 2001
    }
}

/** 用例列表适配器（名称 + 状态徽标 + 行内运行按钮）。 */
class CaseAdapter(
    private val context: Context,
    private val showRunButton: Boolean = true,
    private val onRun: ((CaseItem) -> Unit)? = null,
) : BaseAdapter() {

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
        view.findViewById<TextView>(R.id.text_name).text = item.name ?: "未命名"
        val subtitle = buildString {
            append("id=${item.id}")
            item.last_run_status?.takeIf { it.isNotBlank() }?.let { append(" · 最近: $it") }
            item.description?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
        }
        view.findViewById<TextView>(R.id.text_sub).text = subtitle
        applyStatusBadge(view, item.last_run_status)
        val runButton = view.findViewById<Button>(R.id.btn_run_row)
        if (showRunButton && onRun != null) {
            runButton.visibility = View.VISIBLE
            runButton.setOnClickListener { onRun.invoke(item) }
        } else {
            runButton.visibility = View.GONE
        }
        return view
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
        badge.setTextColor(ContextCompat.getColor(view.context, colorRes))
        badge.text = status
    }
}
