package org.autojs.autodroid.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.autojs.autodroid.data.Session
import org.autojs.autodroid.net.AutoDroidApi
import org.autojs.autodroid.net.PaginatedCases
import org.autojs.autodroid.net.PaginatedScenarios
import org.autojs.autodroid.net.PageLoader
import org.autojs.autodroid.schedule.AppDatabase
import org.autojs.autodroid.schedule.ScheduleManager
import org.autojs.autodroid.schedule.ScheduledRun
import org.autojs.autojs6.R
import retrofit2.Call
import java.util.Calendar

/**
 * 定时任务编辑：目标（用例/场景）+ 重复策略 + 通知开关。
 */
class ScheduleEditActivity : AppCompatActivity() {

    private var editingId: Long = 0L
    private var kind = "case"
    private var targetId = -1
    private var targetName = ""
    private var repeatType = "DAILY"
    private var timeOfDayMinutes = 9 * 60
    private var weekday = 1
    private var onceAtMillis = 0L
    private var enabled = true
    private var ownerServer = ""
    private var ownerAccount = ""
    private var ownerToken: String? = null
    private var cancelTargetLoad: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule_edit)
        Session.init(this)
        ownerServer = Session.serverUrl; ownerAccount = Session.username.orEmpty(); ownerToken = Session.token

        editingId = intent.getLongExtra(EXTRA_SCHEDULE_ID, 0L)
        AdHeader.bind(this, if (editingId > 0) "编辑定时任务" else "新建定时任务")

        val nameInput = findViewById<EditText>(R.id.input_name)
        val kindChipCase = findViewById<TextView>(R.id.chip_kind_case)
        val kindChipScenario = findViewById<TextView>(R.id.chip_kind_scenario)
        fun renderKindChips() {
            kindChipCase.isSelected = kind == "case"
            kindChipScenario.isSelected = kind == "scenario"
        }
        renderKindChips()
        val targetButton = findViewById<Button>(R.id.btn_target)
        val timeButton = findViewById<Button>(R.id.btn_time)
        val intervalInput = findViewById<EditText>(R.id.input_interval)
        val onceButton = findViewById<Button>(R.id.btn_once)
        val notifySwitch = findViewById<Switch>(R.id.switch_notify)
        AdSwitchTint.apply(notifySwitch)
        val saveButton = findViewById<Button>(R.id.btn_save)

        val repeatChips = listOf(
            "DAILY" to findViewById<TextView>(R.id.chip_daily),
            "WEEKLY" to findViewById<TextView>(R.id.chip_weekly),
            "INTERVAL" to findViewById<TextView>(R.id.chip_interval),
            "ONCE" to findViewById<TextView>(R.id.chip_once),
        )
        val weekdayChips = listOf(
            findViewById<TextView>(R.id.chip_wd_1),
            findViewById<TextView>(R.id.chip_wd_2),
            findViewById<TextView>(R.id.chip_wd_3),
            findViewById<TextView>(R.id.chip_wd_4),
            findViewById<TextView>(R.id.chip_wd_5),
            findViewById<TextView>(R.id.chip_wd_6),
            findViewById<TextView>(R.id.chip_wd_7),
        )

        val rowTime = findViewById<View>(R.id.row_time)
        val rowWeekday = findViewById<View>(R.id.row_weekday)
        val rowInterval = findViewById<View>(R.id.row_interval)
        val rowOnce = findViewById<View>(R.id.row_once)

        fun refreshRepeatRows() {
            rowTime.visibility = if (repeatType == "DAILY" || repeatType == "WEEKLY") View.VISIBLE else View.GONE
            rowWeekday.visibility = if (repeatType == "WEEKLY") View.VISIBLE else View.GONE
            rowInterval.visibility = if (repeatType == "INTERVAL") View.VISIBLE else View.GONE
            rowOnce.visibility = if (repeatType == "ONCE") View.VISIBLE else View.GONE
        }

        fun refreshRepeatChips() {
            repeatChips.forEach { (value, chip) -> chip.isSelected = value == repeatType }
            refreshRepeatRows()
        }

        fun refreshWeekdayChips() {
            weekdayChips.forEachIndexed { index, chip -> chip.isSelected = index == weekday - 1 }
        }

        repeatChips.forEach { (value, chip) ->
            chip.setOnClickListener {
                repeatType = value
                refreshRepeatChips()
            }
        }
        weekdayChips.forEachIndexed { index, chip ->
            chip.setOnClickListener {
                weekday = index + 1
                refreshWeekdayChips()
            }
        }
        refreshRepeatChips()
        refreshWeekdayChips()

        fun refreshLabels() {
            timeButton.text = "时间：%02d:%02d".format(timeOfDayMinutes / 60, timeOfDayMinutes % 60)
            onceButton.text = if (onceAtMillis > 0) {
                "时间：" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(onceAtMillis))
            } else {
                "选择执行时间"
            }
            targetButton.text = if (targetId > 0) {
                "目标：${if (kind == "scenario") "场景" else "用例"} · $targetName (id=$targetId)"
            } else {
                "选择目标（必选）"
            }
        }
        refreshLabels()

        fun onKindClick(picked: String) {
            if (kind == picked) return // 重击已选中项不应清空已选目标（对齐原 RadioGroup 行为）
            cancelTargetLoad?.invoke(); cancelTargetLoad = null; targetButton.isEnabled = true
            kind = picked
            targetId = -1
            targetName = ""
            renderKindChips()
            refreshLabels()
        }
        kindChipCase.setOnClickListener { onKindClick("case") }
        kindChipScenario.setOnClickListener { onKindClick("scenario") }

        targetButton.setOnClickListener { pickTarget { id, name -> targetId = id; targetName = name; refreshLabels() } }

        timeButton.setOnClickListener {
            showTimePickerDialog(timeOfDayMinutes) { minutes ->
                timeOfDayMinutes = minutes
                refreshLabels()
            }
        }

        onceButton.setOnClickListener {
            val base = if (onceAtMillis > 0) onceAtMillis else System.currentTimeMillis() + 60 * 60 * 1000
            val cal = Calendar.getInstance().apply { timeInMillis = base }
            showDatePickerDialog(base) { year, month, day ->
                val picked = Calendar.getInstance().apply {
                    set(year, month, day, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), 0)
                    set(Calendar.MILLISECOND, 0)
                }
                showTimePickerDialog(cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)) { minutes ->
                    picked.set(Calendar.HOUR_OF_DAY, minutes / 60)
                    picked.set(Calendar.MINUTE, minutes % 60)
                    onceAtMillis = picked.timeInMillis
                    refreshLabels()
                }
            }
        }

        // 编辑模式：回填
        if (editingId > 0) {
            saveButton.isEnabled = false
            AppDatabase.io.execute {
                val run = AppDatabase.get(this).scheduledRuns().byId(editingId)
                runOnUiThread {
                    if (run == null || !(run.belongsTo(ownerServer, ownerAccount) ||
                            run.serverUrl.isEmpty() && run.username.isEmpty())) {
                        toast("任务不存在或属于其他账号"); finish(); return@runOnUiThread
                    }
                    nameInput.setText(run.name)
                    kind = run.kind
                    renderKindChips()
                    targetId = run.targetId
                    targetName = run.targetName
                    repeatType = run.repeatType
                    timeOfDayMinutes = run.timeOfDayMinutes
                    weekday = run.weekday
                    onceAtMillis = run.onceAtMillis
                    enabled = run.enabled
                    notifySwitch.isChecked = run.notify
                    intervalInput.setText(run.intervalMinutes.toString())
                    refreshRepeatChips()
                    refreshWeekdayChips()
                    refreshLabels()
                    saveButton.isEnabled = true
                }
            }
        } else {
            notifySwitch.isChecked = Session.notifyDefault
            intervalInput.setText("60")
        }

        saveButton.setOnClickListener {
            if (ownerToken.isNullOrBlank() || Session.token != ownerToken ||
                Session.serverUrl != ownerServer || Session.username != ownerAccount) {
                toast("登录状态已变化，请重新打开任务"); finish(); return@setOnClickListener
            }
            val name = nameInput.text.toString().trim().ifBlank {
                if (targetName.isNotBlank()) targetName else "定时任务"
            }
            if (targetId <= 0) {
                toast("请先选择目标用例/场景")
                return@setOnClickListener
            }
            val interval = intervalInput.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 60
            if (enabled && repeatType == "ONCE" && onceAtMillis <= System.currentTimeMillis()) {
                toast("单次执行时间必须晚于当前时间")
                return@setOnClickListener
            }
            val run = ScheduledRun(
                id = editingId,
                name = name,
                kind = kind,
                targetId = targetId,
                targetName = targetName,
                repeatType = repeatType,
                timeOfDayMinutes = timeOfDayMinutes,
                weekday = weekday,
                intervalMinutes = interval,
                onceAtMillis = onceAtMillis,
                notify = notifySwitch.isChecked,
                enabled = enabled,
                serverUrl = ownerServer,
                username = ownerAccount,
            )
            saveButton.isEnabled = false
            AppDatabase.io.execute {
                try {
                    check(Session.token == ownerToken && Session.serverUrl == ownerServer && Session.username == ownerAccount) {
                        "登录状态已变化，请重新打开任务"
                    }
                    val dao = AppDatabase.get(this).scheduledRuns()
                    val next = if (run.enabled) ScheduleManager.computeNextRun(run, System.currentTimeMillis()) else 0L
                    val saved = run.copy(nextRunAt = next)
                    val id = if (editingId > 0) {
                        check(dao.byId(editingId) != null) { "任务已被删除" }
                        dao.update(saved)
                        editingId
                    } else {
                        dao.insert(saved)
                    }
                    val finalRun = saved.copy(id = id)
                    ScheduleManager.schedule(this, finalRun, next)
                    runOnUiThread {
                        toast(if (next > 0) "已保存，下次执行：${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(next))}" else "已保存，任务未启用")
                        finish()
                    }
                } catch (error: Exception) {
                    runOnUiThread { saveButton.isEnabled = true; toast("保存失败：${error.message}") }
                }
            }
        }
    }

    private fun pickTarget(onPicked: (Int, String) -> Unit) {
        val api = AutoDroidApi.service(ownerServer)
        if (kind == "scenario") {
            loadTargetPages({ skip -> api.listScenarios(skip = skip) },
                { page: PaginatedScenarios -> page.items.map { it.id to it.name } to page.total }, onPicked)
        } else {
            loadTargetPages({ skip -> api.listCases(skip = skip) },
                { page: PaginatedCases -> page.items.map { it.id to it.name } to page.total }, onPicked)
        }
    }

    private fun <P> loadTargetPages(request: (Int) -> Call<P>, unpack: (P) -> Pair<List<Pair<Int, String>>, Int>,
                                    onPicked: (Int, String) -> Unit) {
        cancelTargetLoad?.invoke()
        val button = findViewById<Button>(R.id.btn_target)
        val label = button.text
        val pager = PageLoader(request, unpack)
        cancelTargetLoad = { pager.cancel() }
        pager.onChanged = {
            if (!isFinishing && !isDestroyed) {
                button.isEnabled = !pager.loading && !pager.hasMore
                if (pager.loading) button.text = "加载目标…（已加载 ${pager.items.size}）"
                else if (pager.error != null) {
                    button.isEnabled = true; button.text = label; toast(pager.error!!)
                } else if (pager.hasMore) pager.loadMore()
                else {
                    button.text = label; cancelTargetLoad = null
                    showTargetDialog(pager.items, onPicked)
                }
            }
        }
        pager.refresh()
    }

    override fun onDestroy() {
        cancelTargetLoad?.invoke()
        super.onDestroy()
    }

    /** 目标选择弹窗：圆角卡片 + 搜索框 + 列表（选中回填），替代系统 setItems 列表。 */
    private fun showTargetDialog(options: List<Pair<Int, String>>, onPicked: (Int, String) -> Unit) {
        if (options.isEmpty()) {
            toast("暂无可选目标")
            return
        }
        val label = if (kind == "scenario") "场景" else "用例"
        PickerDialog.show(
            this,
            title = "选择$label",
            emptyText = "没有匹配的$label",
            options = options.map { (id, name) ->
                PickerDialog.Option(key = id, name = name, badge = "id=$id", searchKey = id.toString())
            },
        ) { picked ->
            onPicked(picked.key ?: return@show, picked.name)
        }
    }

    /** 时间选择：iOS 闹钟风格滚轮（小时/分钟两列，见 WheelDialog）。 */
    private fun showTimePickerDialog(initialMinutes: Int, onPicked: (Int) -> Unit) {
        WheelDialog.showTime(this, initialMinutes, onPicked)
    }

    /** 日期选择：同款滚轮（年/月/日三列，天数随年月联动）。 */
    private fun showDatePickerDialog(initialMillis: Long, onPicked: (Int, Int, Int) -> Unit) {
        val cal = Calendar.getInstance().apply { if (initialMillis > 0) timeInMillis = initialMillis }
        WheelDialog.showDate(
            this,
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH),
            onPicked,
        )
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_SCHEDULE_ID = "schedule_id"
    }
}
