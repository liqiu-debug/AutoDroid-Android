package org.autojs.autodroid.schedule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.autojs.autodroid.data.Session
import org.autojs.autodroid.net.AutoDroidApi
import org.autojs.autodroid.run.RunBridge
import org.autojs.autojs.model.script.ScriptFile
import org.autojs.autojs.model.script.Scripts
import java.io.File
import java.util.UUID

/**
 * 定时任务执行服务（前台服务）：
 * 拉取脚本包 → 写入运行目录 → AutoJs6 引擎执行 → 结果由脚本自行上报（含飞书开关）。
 */
class ScheduledRunService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("正在准备定时任务…"))
        val runId = intent?.getLongExtra(ScheduleManager.EXTRA_SCHEDULE_ID, -1L) ?: -1L
        Thread {
            try {
                execute(runId)
            } catch (e: Exception) {
                Log.e(TAG, "定时任务执行失败 #$runId", e)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun execute(scheduleId: Long) {
        Session.init(this)
        val run = AppDatabase.get(this).scheduledRuns().byId(scheduleId)
        if (run == null) {
            Log.w(TAG, "定时任务不存在 #$scheduleId")
            return
        }
        if (Session.token.isNullOrBlank()) {
            Log.w(TAG, "未登录，跳过定时任务 #${run.id}（${run.name}）")
            return
        }

        val api = AutoDroidApi.service(Session.serverUrl)
        val call = if (run.kind == "scenario") {
            api.scenarioScript(run.targetId)
        } else {
            api.caseScript(run.targetId)
        }
        val response = call.execute()
        if (!response.isSuccessful) {
            Log.w(TAG, "定时任务 #${run.id} 脚本下发失败：HTTP ${response.code()}")
            return
        }
        val bundle = response.body() ?: return

        val runUuid = UUID.randomUUID().toString()
        val dir = RunBridge.prepareRunDir(this, bundle, runUuid, run.notify)
        RunBridge.pruneRuns(this)
        Log.i(TAG, "定时任务 #${run.id}（${run.name}）开始执行：$runUuid")
        Scripts.run(this, ScriptFile(File(dir, "main.js").absolutePath))
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "定时任务执行",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoDroid Runner")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
    }

    companion object {
        private const val TAG = "AutoDroidRunner"
        private const val CHANNEL_ID = "autodroid_scheduled_runs"
        private const val NOTIFICATION_ID = 0xADD1
    }
}
