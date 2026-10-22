package org.autojs.autodroid.update

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.autojs.autodroid.data.Session
import org.autojs.autodroid.net.AutoDroidApi
import org.autojs.autodroid.net.PackageItem
import org.autojs.autodroid.net.ServerAddress
import org.autojs.autodroid.ui.AdGuideDialog
import org.autojs.autojs.core.plugin.center.PluginInstaller
import org.autojs.autojs6.BuildConfig
import java.net.URLEncoder

/**
 * 检查更新流程（手动触发，单弹窗无闪烁）：
 * 设置页「检查更新」→ 平台安装包库查本应用最新包（package_name=applicationId）→
 * 与 BuildConfig.VERSION_CODE 比较 → 同一弹窗内下载进度 → 系统安装器。
 *
 * 弹窗三相位（检查中 / 结果 / 下载进度）由 [UpdateDialog] 就地切换；
 * 下载器 [AppUpdateDownloader] 自带进度回调；下载 URL 带 ?token= 查询参数
 * （下载端点支持 query-token，下载器不带认证头）。发布时把客户端 APK
 * 上传到「App 包管理」即可。安装复用 PluginInstaller.installFromFileUri。
 */
object AppUpdateFlow {

    private var activeDialog: UpdateDialog? = null
    private var downloadJob: Job? = null

    /** 拼接下载地址（纯函数，便于单测）。 */
    fun buildDownloadUrl(baseUrl: String, packageId: Int, token: String): String {
        val root = ServerAddress.normalize(baseUrl).trimEnd('/')
        return "$root/packages/$packageId/download?token=" + URLEncoder.encode(token, "UTF-8")
    }

    suspend fun checkAndPrompt(activity: AppCompatActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        // 防重入：同一时刻只允许一个弹窗。宿主已销毁的遗留弹窗（窗口泄漏状态，
        // isShowing 仍为 true）必须先清理，否则会永久短路后续检查。
        activeDialog?.let { active ->
            if (active.isShowing() && !active.isStale()) return
            runCatching { active.dismiss() }
            activeDialog = null
        }

        val dialog = UpdateDialog(activity)
        dialog.onDismiss = { if (activeDialog === dialog) activeDialog = null }
        activeDialog = dialog
        runCheck(activity, dialog)
    }

    private suspend fun runCheck(activity: AppCompatActivity, dialog: UpdateDialog) {
        val token = Session.token
        if (token.isNullOrBlank()) {
            dialog.showResult(
                "尚未登录，请先登录后再检查更新。",
                UpdateDialog.Action("知道了") { dialog.dismiss() },
            )
            return
        }

        dialog.showChecking()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val response = AutoDroidApi.service(Session.serverUrl)
                    .listPackages(
                        packageName = activity.packageName,
                        platform = "android",
                        latestOnly = true,
                    )
                    .execute()
                if (!response.isSuccessful) {
                    error("HTTP ${response.code()}")
                }
                response.body()?.items?.firstOrNull()
            }
        }
        if (!dialog.isShowing() || activity.isFinishing || activity.isDestroyed) return

        result.fold(
            onSuccess = { item -> showVerdict(activity, dialog, item) },
            onFailure = { e ->
                showError(activity, dialog, "检查更新失败：${e.message ?: e}") {
                    activity.lifecycleScope.launch { runCheck(activity, dialog) }
                }
            },
        )
    }

    private fun showVerdict(activity: AppCompatActivity, dialog: UpdateDialog, item: PackageItem?) {
        if (item == null) {
            dialog.showResult(
                "平台安装包库中暂无本应用安装包（package_name=${activity.packageName}）。",
                UpdateDialog.Action("知道了") { dialog.dismiss() },
            )
            return
        }
        val remote = formatVersion(item.version_name, item.version_code)
        val local = formatVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString())
        when (AppUpdatePolicy.verdict(item.version_code, BuildConfig.VERSION_CODE)) {
            UpdateVerdict.UP_TO_DATE -> dialog.showResult(
                "当前已是最新版本\n$local",
                UpdateDialog.Action("知道了") { dialog.dismiss() },
            )

            UpdateVerdict.UNKNOWN_VERSION -> dialog.showResult(
                "服务器版本号无法解析，无法自动比较。\n远端：$remote\n本地：$local",
                UpdateDialog.Action("仍要下载安装") { startDownload(activity, dialog, item) },
                UpdateDialog.Action("取消") { dialog.dismiss() },
            )

            UpdateVerdict.UPDATE_AVAILABLE -> {
                val detail = buildList {
                    add("发现新版本 $remote")
                    if (item.file_size > 0) add(String.format("大小：%.1f MB", item.file_size))
                    item.upload_time?.takeIf { it.isNotBlank() }?.let {
                        add("上传于 ${it.replace("T", " ").take(16)}")
                    }
                    add("当前版本：$local")
                }.joinToString("\n")
                dialog.showResult(
                    detail,
                    UpdateDialog.Action("下载安装") { startDownload(activity, dialog, item) },
                    UpdateDialog.Action("稍后再说") { dialog.dismiss() },
                )
            }
        }
    }

    private fun startDownload(activity: AppCompatActivity, dialog: UpdateDialog, item: PackageItem) {
        if (activity.isFinishing || activity.isDestroyed) return
        val token = Session.token
        if (token.isNullOrBlank()) {
            dialog.showResult(
                "登录状态已失效，请重新登录。",
                UpdateDialog.Action("知道了") { dialog.dismiss() },
            )
            return
        }
        val url = runCatching { buildDownloadUrl(Session.serverUrl, item.id, token) }.getOrElse {
            dialog.showResult(
                "平台地址无效，请先在设置页填写平台地址。",
                UpdateDialog.Action("知道了") { dialog.dismiss() },
            )
            return
        }
        if (!canRequestInstalls(activity)) {
            dialog.showResult(
                "系统限制安装未知来源应用，请在设置中允许 AutoDroid 安装应用后，重新点击「下载安装」。",
                UpdateDialog.Action("去设置") {
                    dialog.dismiss()
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .setData(Uri.parse("package:${activity.packageName}"))
                    )
                },
                UpdateDialog.Action("取消") { dialog.dismiss() },
            )
            return
        }

        dialog.showDownloading {
            downloadJob?.cancel()
            dialog.dismiss()
        }
        downloadJob = activity.lifecycleScope.launch {
            try {
                val uri = AppUpdateDownloader.download(activity, url) { downloaded, total ->
                    activity.runOnUiThread { dialog.updateProgress(downloaded, total) }
                }
                if (activity.isFinishing || activity.isDestroyed) return@launch
                dialog.dismiss()
                PluginInstaller.installFromFileUri(activity, uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing()) return@launch
                showError(activity, dialog, "下载失败：${e.message ?: e}") {
                    activity.lifecycleScope.launch { startDownload(activity, dialog, item) }
                }
            }
        }
    }

    private fun showError(
        activity: AppCompatActivity,
        dialog: UpdateDialog,
        message: String,
        retry: () -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        dialog.showResult(
            message,
            UpdateDialog.Action("重试") { retry() },
            UpdateDialog.Action("关闭") { dialog.dismiss() },
        )
    }

    /** SDK 26+ 需允许"安装未知应用"。 */
    fun canRequestInstalls(activity: AppCompatActivity): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            activity.packageManager.canRequestPackageInstalls()

    /** 独立入口的未知来源引导（非单弹窗路径用）。 */
    fun ensureCanInstallApks(activity: AppCompatActivity): Boolean {
        if (canRequestInstalls(activity)) return true
        AdGuideDialog.show(
            activity,
            title = "需要安装权限",
            message = "系统限制安装未知来源应用，请在设置中允许 AutoDroid 安装应用后重试。",
            positiveText = "去设置",
            negativeText = "取消",
        ) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${activity.packageName}"))
            )
        }
        return false
    }

    private fun formatVersion(name: String?, code: String?): String {
        val n = name?.trim().orEmpty().ifEmpty { "-" }
        val c = code?.trim().orEmpty().ifEmpty { "-" }
        return "v$n（$c）"
    }
}
