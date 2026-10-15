package org.autojs.autodroid.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.autojs.autodroid.data.Session
import org.autojs.autodroid.net.AutoDroidApi
import org.autojs.autodroid.schedule.AppDatabase
import org.autojs.autodroid.schedule.ScheduleManager
import org.autojs.autodroid.schedule.ScheduledRun
import org.autojs.autojs6.R
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule_edit)

        editingId = intent.getLongExtra(EXTRA_SCHEDULE_ID, 0L)

        val nameInput = findViewById<EditText>(R.id.input_name)
        val kindGroup = findViewById<RadioGroup>(R.id.radio_kind)
        val targetButton = findViewById<Button>(R.id.btn_target)
        val repeatSpinner = findViewById<Spinner>(R.id.spinner_repeat)
        val timeButton = findViewById<Button>(R.id.btn_time)
        val weekdaySpinner = findViewById<Spinner>(R.id.spinner_weekday)
        val intervalInput = findViewById<EditText>(R.id.input_interval)
        val onceButton = findViewById<Button>(R.id.btn_once)
        val notifySwitch = findViewById<Switch>(R.id.switch_notify)

        val repeatLabels = listOf("每天", "每周", "每隔 N 分钟", "单次")
        val repeatValues = listOf("DAILY", "WEEKLY", "INTERVAL", "ONCE")
        repeatSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, repeatLabels)
        weekdaySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日"),
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
        refreshRepeatRows()

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

        kindGroup.setOnCheckedChangeListener { _, checkedId ->
            kind = if (checkedId == R.id.radio_scenario) "scenario" else "case"
            targetId = -1
            targetName = ""
            refreshLabels()
        }

        targetButton.setOnClickListener { pickTarget { id, name -> targetId = id; targetName = name; refreshLabels() } }

        repeatSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                repeatType = repeatValues[position]
                refreshRepeatRows()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        timeButton.setOnClickListener {
            TimePickerDialog(
                this,
                { _, hour, minute ->
                    timeOfDayMinutes = hour * 60 + minute
                    refreshLabels()
                },
                timeOfDayMinutes / 60,
                timeOfDayMinutes % 60,
                true,
            ).show()
        }

        weekdaySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                weekday = position + 1
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        onceButton.setOnClickListener {
            val cal = Calendar.getInstance()
            if (onceAtMillis > 0) cal.timeInMillis = onceAtMillis
            DatePickerDialog(
                this,
                { _, year, month, day ->
                    val picked = Calendar.getInstance().apply {
                        set(year, month, day, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    TimePickerDialog(
                        this,
                        { _, hour, minute ->
                            picked.set(Calendar.HOUR_OF_DAY, hour)
                            picked.set(Calendar.MINUTE, minute)
                            onceAtMillis = picked.timeInMillis
                            refreshLabels()
                        },
                        cal.get(Calendar.HOUR_OF_DAY),
                        cal.get(Calendar.MINUTE),
                        true,
                    ).show()
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH),
            ).show()
        }

        // 编辑模式：回填
        if (editingId > 0) {
            AppDatabase.io.execute {
                val run = AppDatabase.get(this).scheduledRuns().byId(editingId) ?: return@execute
                runOnUiThread {
                    nameInput.setText(run.name)
                    kind = run.kind
                    kindGroup.check(if (run.kind == "scenario") R.id.radio_scenario else R.id.radio_case)
                    targetId = run.targetId
                    targetName = run.targetName
                    repeatType = run.repeatType
                    timeOfDayMinutes = run.timeOfDayMinutes
                    weekday = run.weekday
                    onceAtMillis = run.onceAtMillis
                    notifySwitch.isChecked = run.notify
                    intervalInput.setText(run.intervalMinutes.toString())
                    repeatSpinner.setSelection(repeatValues.indexOf(run.repeatType).coerceAtLeast(0))
                    weekdaySpinner.setSelection((run.weekday - 1).coerceIn(0, 6))
                    refreshRepeatRows()
                    refreshLabels()
                }
            }
        } else {
            notifySwitch.isChecked = Session.notifyDefault
            intervalInput.setText("60")
        }

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            val name = nameInput.text.toString().trim().ifBlank {
                if (targetName.isNotBlank()) targetName else "定时任务"
            }
            if (targetId <= 0) {
                toast("请先选择目标用例/场景")
                return@setOnClickListener
            }
            val interval = intervalInput.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 60
            if (repeatType == "ONCE" && onceAtMillis <= 0) {
                toast("请选择单次执行时间")
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
                enabled = true,
            )
            AppDatabase.io.execute {
                val dao = AppDatabase.get(this).scheduledRuns()
                val next = ScheduleManager.computeNextRun(run, System.currentTimeMillis())
                val saved = run.copy(nextRunAt = next)
                val id = if (editingId > 0) {
                    dao.update(saved)
                    editingId
                } else {
                    dao.insert(saved)
                }
                val finalRun = saved.copy(id = id)
                ScheduleManager.schedule(this, finalRun, next)
                runOnUiThread {
                    toast("已保存，下次执行：${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(next))}")
                    finish()
                }
            }
        }
    }

    private fun pickTarget(onPicked: (Int, String) -> Unit) {
        val api = AutoDroidApi.service(Session.serverUrl)
        val handler = object {
            fun show(names: List<String>, ids: List<Int>) {
                if (names.isEmpty()) {
                    toast("暂无可选目标")
                    return
                }
                AlertDialog.Builder(this@ScheduleEditActivity)
                    .setTitle("选择${if (kind == "scenario") "场景" else "用例"}")
                    .setItems(names.toTypedArray()) { _, which -> onPicked(ids[which], names[which]) }
                    .show()
            }
        }
        if (kind == "scenario") {
            api.listScenarios().enqueue(object : Callback<org.autojs.autodroid.net.PaginatedScenarios> {
                override fun onResponse(
                    call: Call<org.autojs.autodroid.net.PaginatedScenarios>,
                    response: Response<org.autojs.autodroid.net.PaginatedScenarios>,
                ) {
                    val items = response.body()?.items ?: emptyList()
                    handler.show(items.map { "${it.name} (id=${it.id})" }, items.map { it.id })
                }

                override fun onFailure(call: Call<org.autojs.autodroid.net.PaginatedScenarios>, t: Throwable) {
                    toast("场景加载失败：${t.message}")
                }
            })
        } else {
            api.listCases().enqueue(object : Callback<org.autojs.autodroid.net.PaginatedCases> {
                override fun onResponse(
                    call: Call<org.autojs.autodroid.net.PaginatedCases>,
                    response: Response<org.autojs.autodroid.net.PaginatedCases>,
                ) {
                    val items = response.body()?.items ?: emptyList()
                    handler.show(items.map { "${it.name} (id=${it.id})" }, items.map { it.id })
                }

                override fun onFailure(call: Call<org.autojs.autodroid.net.PaginatedCases>, t: Throwable) {
                    toast("用例加载失败：${t.message}")
                }
            })
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_SCHEDULE_ID = "schedule_id"
    }
}
