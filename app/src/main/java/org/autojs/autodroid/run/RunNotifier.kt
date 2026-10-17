package org.autojs.autodroid.run

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.autojs.autodroid.ui.MainActivity
import java.io.File

/**
 * 运行结果通知：轮询运行目录的 report.json（与运行页轮询同源），
 * 完成后发送系统通知。独立线程实现，不依赖运行页存活，用例与场景均适用。
 */
object RunNotifier {

    private const val CHANNEL_ID = "autodroid_run_results"
    private const val CHANNEL_NAME = "运行结果"
    private const val POLL_INTERVAL_MS = 2000L
    private const val WATCH_TIMEOUT_MS = 60 * 60 * 1000L

    /** 监视一次运行，结束后发送系统通知（成功/失败 + 用例或场景名 + 平台执行记录）。 */
    fun watchResultAndNotify(context: Context, runDir: File, kind: String, title: String) {
        val appContext = context.applicationContext
        val report = File(runDir, "report.json")
        Thread({
            val deadline = System.currentTimeMillis() + WATCH_TIMEOUT_MS
            while (!report.isFile && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
            if (!report.isFile) return@Thread
            // finish() 在写 report.json 后会二次写入 upload_response（上报响应），稍候再读
            try {
                Thread.sleep(1500)
            } catch (_: InterruptedException) {
            }
            var result: RunBridge.RunResult? = null
            var attempts = 0
            while (result == null && attempts < 5) {
                try {
                    result = RunBridge.parseReport(report)
                } catch (e: Exception) {
                    attempts++
                    try {
                        Thread.sleep(1000)
                    } catch (ie: InterruptedException) {
                        return@Thread
                    }
                }
            }
            result?.let { notifyResult(appContext, kind, title, it) }
        }, "RunResultNotifier").start()
    }

    private fun notifyResult(context: Context, kind: String, title: String, result: RunBridge.RunResult) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val kindLabel = if (kind == "scenario") "场景" else "用例"
        val contentTitle = if (result.success) {
            "${kindLabel}执行完成：全部通过 ✓"
        } else {
            "${kindLabel}执行完成：存在失败步骤 ✗"
        }
        val failedCount = result.steps.count { it.status == "FAIL" }
        val body = buildString {
            append("「$title」")
            if (result.success) {
                append("共 ${result.steps.size} 步全部通过")
            } else {
                append("${failedCount}/${result.steps.size} 步失败")
            }
            val executionId = result.executionId
            if (executionId != null) {
                append(" · 平台记录 #$executionId")
            } else if (!result.uploadOk) {
                append(" · 未上报平台")
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val notification = builder
            .setContentTitle(contentTitle)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(title.hashCode(), notification)
    }
}
