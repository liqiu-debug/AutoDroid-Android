package org.autojs.autodroid.schedule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.autojs.autodroid.data.Session
import org.autojs.autodroid.net.AutoDroidApi
import org.autojs.autodroid.run.RunBridge
import org.autojs.autodroid.run.RunNotifier
import org.autojs.autodroid.run.RunCoordinator
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 定时任务执行服务（前台服务）：
 * 拉取脚本包 → 写入运行目录 → AutoJs6 引擎执行 → 结果由脚本自行上报（含飞书开关）。
 */
class ScheduledRunService : Service() {
    private val activeStarts = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("正在准备定时任务…"))
        activeStarts.incrementAndGet()
        latestStartId = startId
        val runId = intent?.getLongExtra(ScheduleManager.EXTRA_SCHEDULE_ID, -1L) ?: -1L
        val executionId = intent?.getStringExtra(ScheduleManager.EXTRA_EXECUTION_ID)
        Thread {
            try {
                execute(runId, executionId)
            } catch (e: Exception) {
                Log.e(TAG, "定时任务执行失败 #$runId", e)
            } finally {
                mainHandler.post {
                    if (activeStarts.decrementAndGet() == 0) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelfResult(latestStartId)
                    }
                }
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun execute(scheduleId: Long, executionId: String?) {
        val runUuid = executionId ?: UUID.randomUUID().toString()
        if (!RunCoordinator.owns(runUuid) && !RunCoordinator.reserve(runUuid)) {
            Log.w(TAG, "本机已有任务，跳过本次执行 #$scheduleId")
            return
        }
        try {
            Session.init(this)
            val run = AppDatabase.get(this).scheduledRuns().byId(scheduleId)
            if (run == null) {
                Log.w(TAG, "定时任务不存在 #$scheduleId")
                return
            }
            val server = Session.serverUrl
            val account = Session.username.orEmpty()
            val token = Session.token
            if (token.isNullOrBlank() || !run.belongsTo(server, account) || (!run.enabled && run.repeatType != "ONCE")) {
                Log.w(TAG, "未登录，跳过定时任务 #${run.id}（${run.name}）")
                return
            }

            val api = AutoDroidApi.service(server)
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
            if (Session.token != token || Session.serverUrl != server || Session.username != account) {
                Log.w(TAG, "登录状态已变化，取消定时任务 #${run.id}")
                return
            }

            val dir = RunBridge.prepareRunDir(this, bundle, runUuid, run.notify, server, token)
            RunBridge.pruneRuns(this, currentDir = dir)
            val mainJs = File(dir, "main.js")
            if (!mainJs.isFile) {
                Log.w(TAG, "定时任务 #${run.id}（${run.name}）启动失败：main.js 未就绪")
                return
            }
            Log.i(TAG, "定时任务 #${run.id}（${run.name}）开始执行：$runUuid")
            val finished = CountDownLatch(1)
            RunCoordinator.start(dir, runUuid) {
                try {
                    if (File(dir, "report.json").isFile) RunNotifier.watchResultAndNotify(this, dir, run.kind, run.name)
                } finally { finished.countDown() }
            }
            // Keep the foreground service alive until the engine and report upload
            // finish, rather than dropping it as soon as the script is submitted.
            finished.await()
        } finally {
            RunCoordinator.release(runUuid)
        }
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
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("AutoDroid")
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
