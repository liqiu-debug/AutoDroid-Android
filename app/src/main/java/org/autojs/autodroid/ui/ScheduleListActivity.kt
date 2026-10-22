package org.autojs.autodroid.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.autojs.autodroid.schedule.AppDatabase
import org.autojs.autodroid.schedule.ScheduleManager
import org.autojs.autodroid.schedule.ScheduledRun
import org.autojs.autodroid.data.Session
import org.autojs.autojs6.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 定时任务列表：本地调度（AlarmManager），触发后由前台服务执行并回传平台。
 */
class ScheduleListActivity : AppCompatActivity() {

    private lateinit var adapter: ScheduleAdapter
    private lateinit var statusText: TextView
    private lateinit var exactHint: Button
    private lateinit var swipeRefresh: SwipeRefreshLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule_list)

        AdHeader.bind(this, "定时任务", showBack = false)
        AdBottomNav.bind(this, "schedules")
        statusText = findViewById(R.id.text_status)
        swipeRefresh = findViewById(R.id.swipe_refresh)
        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.ad_primary))
        swipeRefresh.setOnRefreshListener { reload() }

        adapter = ScheduleAdapter { run, enabled ->
            if (enabled && !run.belongsTo(Session.serverUrl, Session.username.orEmpty())) {
                toast("此任务需先编辑并保存，确认所属平台和账号")
                reload()
                return@ScheduleAdapter
            }
            val next = if (enabled) ScheduleManager.computeNextRun(run, System.currentTimeMillis()) else 0L
            if (enabled && next <= 0) {
                toast("单次执行时间已过，请编辑任务时间")
                reload()
                return@ScheduleAdapter
            }
            AppDatabase.io.execute {
                val updated = run.copy(
                    enabled = enabled,
                    nextRunAt = next,
                )
                AppDatabase.get(this).scheduledRuns().update(updated)
                if (enabled) {
                    ScheduleManager.schedule(this, updated, updated.nextRunAt)
                } else {
                    ScheduleManager.cancel(this, run.id)
                }
                runOnUiThread { reload() }
            }
        }

        val listView = findViewById<ListView>(R.id.list_schedules)
        listView.adapter = adapter
        emptyStateView = AdEmptyState.bind(listView, title = "暂无定时任务", hint = "点上方「新建定时任务」创建", iconRes = R.drawable.ic_ad_schedules)
        PullDownMoveHelper.attach(swipeRefresh, listView)

        // 顶部区（新建入口）作为列表 header：下拉刷新与列表滚动时一起移动
        val header = layoutInflater.inflate(R.layout.view_schedule_list_header, listView, false)
        listView.addHeaderView(header, null, false)
        exactHint = header.findViewById(R.id.btn_exact_alarm)
        header.findViewById<Button>(R.id.btn_add).setOnClickListener {
            startActivity(Intent(this, ScheduleEditActivity::class.java))
        }
        exactHint.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    )
                } catch (e: Exception) {
                    toast("请在系统设置 → 应用 → 特殊权限中允许精确闹钟")
                }
            }
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val run = adapter.getItem(position - listView.headerViewsCount)
            startActivity(
                Intent(this, ScheduleEditActivity::class.java)
                    .putExtra(ScheduleEditActivity.EXTRA_SCHEDULE_ID, run.id)
            )
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val run = adapter.getItem(position - listView.headerViewsCount)
            AdGuideDialog.show(
                this,
                title = "删除定时任务",
                message = "确定删除「${run.name}」？",
                positiveText = "删除",
                negativeText = "取消",
                positiveColorRes = R.color.ad_fail,
            ) {
                AppDatabase.io.execute {
                    ScheduleManager.cancel(this, run.id)
                    AppDatabase.get(this).scheduledRuns().delete(run)
                    runOnUiThread { reload() }
                }
            }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
        val exact = ScheduleManager.canScheduleExact(this)
        exactHint.visibility = if (exact) View.GONE else View.VISIBLE
    }

    private var emptyStateView: View? = null

    private fun reload() {
        AppDatabase.io.execute {
            val runs = AppDatabase.get(this).scheduledRuns().forAccount(Session.serverUrl, Session.username.orEmpty())
            runOnUiThread {
                if (swipeRefresh.isRefreshing) swipeRefresh.isRefreshing = false
                adapter.setItems(runs)
                statusText.text = if (runs.isEmpty()) "" else "共 ${runs.size} 个定时任务（长按删除）"
                emptyStateView?.visibility = if (runs.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

class ScheduleAdapter(
    private val onToggle: (ScheduledRun, Boolean) -> Unit,
) : BaseAdapter() {

    private var items: List<ScheduledRun> = emptyList()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun setItems(newItems: List<ScheduledRun>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): ScheduledRun = items[position]
    override fun getItemId(position: Int): Long = items[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(parent?.context)
            .inflate(R.layout.item_schedule, parent, false)
        val run = items[position]
        view.findViewById<TextView>(R.id.text_name).text = run.name
        val repeatText = when (run.repeatType) {
            "DAILY" -> "每天 %02d:%02d".format(run.timeOfDayMinutes / 60, run.timeOfDayMinutes % 60)
            "WEEKLY" -> "每周${"一二三四五六日"[run.weekday.coerceIn(1, 7) - 1]} %02d:%02d".format(
                run.timeOfDayMinutes / 60, run.timeOfDayMinutes % 60
            )
            "INTERVAL" -> "每 ${run.intervalMinutes} 分钟"
            "ONCE" -> "单次 ${timeFormat.format(Date(run.onceAtMillis))}"
            else -> run.repeatType
        }
        val nextText = if (run.enabled && run.nextRunAt > 0) {
            " · 下次 ${timeFormat.format(Date(run.nextRunAt))}"
        } else if (!run.enabled) {
            " · 已停用"
        } else {
            ""
        }
        view.findViewById<TextView>(R.id.text_sub).text =
            "${if (run.kind == "scenario") "场景" else "用例"}：${run.targetName} · $repeatText$nextText"

        val switch = view.findViewById<Switch>(R.id.switch_enabled)
        AdSwitchTint.apply(switch)
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = run.enabled
        switch.setOnCheckedChangeListener { _, checked -> onToggle(run, checked) }
        return view
    }
}
