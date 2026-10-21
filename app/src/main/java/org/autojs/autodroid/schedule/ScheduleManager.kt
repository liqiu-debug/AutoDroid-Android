package org.autojs.autodroid.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.autojs.autodroid.data.Session
import java.util.Calendar

/**
 * 定时任务调度：下次触发时间计算 + AlarmManager 注册/取消。
 *
 * 精确闹钟在 Android 12+ 需要 SCHEDULE_EXACT_ALARM 特殊权限；
 * 未授权时回退为不精确闹钟（可能延迟数分钟）。
 */
object ScheduleManager {

    const val EXTRA_SCHEDULE_ID = "schedule_id"
    const val EXTRA_EXECUTION_ID = "execution_id"

    fun computeNextRun(run: ScheduledRun, fromMillis: Long): Long {
        val cal = Calendar.getInstance()
        when (run.repeatType) {
            "DAILY" -> {
                cal.timeInMillis = fromMillis
                cal.set(Calendar.HOUR_OF_DAY, run.timeOfDayMinutes / 60)
                cal.set(Calendar.MINUTE, run.timeOfDayMinutes % 60)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                if (cal.timeInMillis <= fromMillis) cal.add(Calendar.DAY_OF_YEAR, 1)
                return cal.timeInMillis
            }
            "WEEKLY" -> {
                // run.weekday: 1=周一 ... 7=周日；Calendar: SUNDAY=1, MONDAY=2 ... SATURDAY=7
                val calendarWeekday = if (run.weekday == 7) Calendar.SUNDAY else run.weekday + 1
                cal.timeInMillis = fromMillis
                cal.set(Calendar.HOUR_OF_DAY, run.timeOfDayMinutes / 60)
                cal.set(Calendar.MINUTE, run.timeOfDayMinutes % 60)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                while (cal.get(Calendar.DAY_OF_WEEK) != calendarWeekday || cal.timeInMillis <= fromMillis) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                return cal.timeInMillis
            }
            "INTERVAL" -> return fromMillis + run.intervalMinutes.coerceAtLeast(1) * 60_000L
            "ONCE" -> return run.onceAtMillis.takeIf { it > fromMillis } ?: 0L
        }
        return 0L
    }

    fun schedule(context: Context, run: ScheduledRun, nextAt: Long) {
        Session.init(context)
        if (!run.enabled || nextAt <= 0L || Session.token.isNullOrBlank() ||
            !run.belongsTo(Session.serverUrl, Session.username.orEmpty())) {
            cancel(context, run.id)
            return
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, run.id)
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAt, pi)
        } catch (e: SecurityException) {
            // 未授予精确闹钟权限：回退不精确闹钟
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAt, pi)
        }
    }

    fun cancel(context: Context, runId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(context, runId))
    }

    fun suspendAll(context: Context) {
        val app = context.applicationContext
        AppDatabase.io.execute {
            val dao = AppDatabase.get(app).scheduledRuns()
            for (run in dao.enabledRuns()) {
                cancel(app, run.id)
                dao.update(run.copy(enabled = false, nextRunAt = 0L))
            }
        }
    }

    fun canScheduleExact(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }

    private fun pendingIntent(context: Context, runId: Long): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java)
            .putExtra(EXTRA_SCHEDULE_ID, runId)
        return PendingIntent.getBroadcast(
            context,
            runId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
