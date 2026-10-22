package org.autojs.autodroid.update

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * 更新包下载器：HttpURLConnection 流式下载 + 进度回调（自带，不绑任何对话框）。
 *
 * 每次尝试使用**独立临时文件**（ad_update_<随机>.apk），取消/失败只删除本次自己的文件，
 * 不会误删并发重试正在写入的文件；旧临时文件按时间清理。成功返回可安装的 content Uri
 * （FileProvider，authority 用 "${packageName}.fileprovider"，与本 flavor 的
 * manifestPlaceholders 一致）。协程取消 → 断开连接并删除本次半成品。
 */
object AppUpdateDownloader {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val PROGRESS_INTERVAL_MS = 80L
    private const val STALE_FILE_MS = 60 * 60 * 1000L

    suspend fun download(
        context: Context,
        url: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "ad_update")
        dir.mkdirs()
        cleanStaleFiles(dir)
        // 每次尝试独立文件名：取消是协作式的，旧任务的 finally 可能滞后数十秒执行，
        // 固定文件名会导致其误删重试任务正在写的文件。
        val out = File.createTempFile("ad_update_", ".apk", dir)
        var connection: HttpURLConnection? = null
        var success = false
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
            }
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                error("HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            var downloaded = 0L
            var lastTick = 0L
            connection.inputStream.use { input ->
                FileOutputStream(out).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastTick >= PROGRESS_INTERVAL_MS) {
                            lastTick = now
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
            onProgress(downloaded, total)
            success = true
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
        } catch (e: CancellationException) {
            throw e
        } finally {
            connection?.disconnect()
            if (!success) out.delete()
        }
    }

    /** 清理超过 1 小时的历史临时文件（异常退出可能残留）。 */
    private fun cleanStaleFiles(dir: File) {
        val cutoff = System.currentTimeMillis() - STALE_FILE_MS
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }
}
