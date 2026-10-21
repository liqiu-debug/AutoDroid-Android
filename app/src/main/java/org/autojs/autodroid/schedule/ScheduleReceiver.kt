package org.autojs.autodroid.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import org.autojs.autodroid.data.Session
import org.autojs.autodroid.run.RunCoordinator
import java.util.UUID

/**
 * 定时任务触发：拉起执行服务，并计算/写入下一次触发时间（单次任务执行后停用）。
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val runId = intent.getLongExtra(ScheduleManager.EXTRA_SCHEDULE_ID, -1L)
        if (runId < 0) return
        val pending = goAsync()
        Thread {
            var reservation: String? = null
            try {
                Session.init(context)
                val dao = AppDatabase.get(context).scheduledRuns()
                val run = dao.byId(runId) ?: return@Thread
                if (!run.enabled) return@Thread
                if (Session.token.isNullOrBlank() || !run.belongsTo(Session.serverUrl, Session.username.orEmpty())) {
                    dao.update(run.copy(enabled = false, nextRunAt = 0L))
                    ScheduleManager.cancel(context, run.id)
                    return@Thread
                }
                val executionId = UUID.randomUUID().toString()
                if (!RunCoordinator.reserve(executionId)) {
                    val delayed = run.copy(nextRunAt = System.currentTimeMillis() + 30_000)
                    dao.update(delayed)
                    ScheduleManager.schedule(context, delayed, delayed.nextRunAt)
                    return@Thread
                }
                reservation = executionId

                // 先续排下一次（单次任务执行后停用），再触发本次执行
                if (run.repeatType == "ONCE") {
                    dao.update(run.copy(enabled = false, nextRunAt = 0L))
                } else {
                    val next = ScheduleManager.computeNextRun(run, System.currentTimeMillis())
                    dao.update(run.copy(nextRunAt = next))
                    ScheduleManager.schedule(context, run, next)
                }

                Log.i(TAG, "定时任务触发 #${run.id}（${run.name}）")
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ScheduledRunService::class.java)
                        .putExtra(ScheduleManager.EXTRA_SCHEDULE_ID, runId)
                        .putExtra(ScheduleManager.EXTRA_EXECUTION_ID, executionId),
                )
                reservation = null // The service now owns the reservation.
            } catch (e: Exception) {
                reservation?.let { RunCoordinator.release(it) }
                Log.e(TAG, "定时任务触发失败 #$runId", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "AutoDroidRunner"
    }
}

/**
 * 开机自恢复：重排所有启用任务的闹钟。
 */
class ScheduleBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        Thread {
            try {
                Session.init(context)
                val dao = AppDatabase.get(context).scheduledRuns()
                for (run in dao.enabledRuns()) {
                    if (Session.token.isNullOrBlank() || !run.belongsTo(Session.serverUrl, Session.username.orEmpty())) {
                        dao.update(run.copy(enabled = false, nextRunAt = 0L))
                        continue
                    }
                    val next = ScheduleManager.computeNextRun(run, System.currentTimeMillis())
                    if (next > 0L) {
                        dao.update(run.copy(nextRunAt = next))
                        ScheduleManager.schedule(context, run, next)
                    } else {
                        dao.update(run.copy(enabled = false, nextRunAt = 0L))
                    }
                }
                Log.i(TAG, "开机重排定时任务完成")
            } catch (e: Exception) {
                Log.e(TAG, "开机重排定时任务失败", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "AutoDroidRunner"
    }
}
