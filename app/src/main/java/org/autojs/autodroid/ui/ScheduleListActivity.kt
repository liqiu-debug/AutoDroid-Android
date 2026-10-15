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
import org.autojs.autodroid.schedule.AppDatabase
import org.autojs.autodroid.schedule.ScheduleManager
import org.autojs.autodroid.schedule.ScheduledRun
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule_list)

        statusText = findViewById(R.id.text_status)
        exactHint = findViewById(R.id.btn_exact_alarm)

        adapter = ScheduleAdapter { run, enabled ->
            AppDatabase.io.execute {
                val updated = run.copy(
                    enabled = enabled,
                    nextRunAt = if (enabled) {
                        ScheduleManager.computeNextRun(run, System.currentTimeMillis())
                    } else {
                        0L
                    },
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
        listView.setOnItemClickListener { _, _, position, _ ->
            val run = adapter.getItem(position)
            startActivity(
                Intent(this, ScheduleEditActivity::class.java)
                    .putExtra(ScheduleEditActivity.EXTRA_SCHEDULE_ID, run.id)
            )
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val run = adapter.getItem(position)
            AlertDialog.Builder(this)
                .setTitle("删除定时任务")
                .setMessage("确定删除「${run.name}」？")
                .setPositiveButton("删除") { _, _ ->
                    AppDatabase.io.execute {
                        ScheduleManager.cancel(this, run.id)
                        AppDatabase.get(this).scheduledRuns().delete(run)
                        runOnUiThread { reload() }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }

        findViewById<Button>(R.id.btn_add).setOnClickListener {
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
    }

    override fun onResume() {
        super.onResume()
        reload()
        val exact = ScheduleManager.canScheduleExact(this)
        exactHint.visibility = if (exact) View.GONE else View.VISIBLE
    }

    private fun reload() {
        AppDatabase.io.execute {
            val runs = AppDatabase.get(this).scheduledRuns().all()
            runOnUiThread {
                adapter.setItems(runs)
                statusText.text = "共 ${runs.size} 个定时任务（长按删除）"
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
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = run.enabled
        switch.setOnCheckedChangeListener { _, checked -> onToggle(run, checked) }
        return view
    }
}
