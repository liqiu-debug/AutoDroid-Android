package org.autojs.autodroid.update

import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.autojs.autodroid.ui.AdDialog
import org.autojs.autojs6.R

/**
 * 检查更新弹窗：检查中 / 结果 / 下载进度 三相位在同一弹窗内就地切换（无闪烁）。
 *
 * 同一 AlertDialog 实例贯穿全部相位；按钮回调不自动 dismiss——
 * 终态动作（知道了/稍后再说/取消/拉起安装器）自行调用 [dismiss]，相位间切换则继续。
 * 动作点击做 500ms 防抖：相位切换会把同一按钮换义（如「下载安装」→「取消」），
 * 防止快速双击在换义后误触发新语义。
 */
class UpdateDialog(private val activity: AppCompatActivity) {

    /** [onClick] 由调用方决定是否 dismiss（终态先 dismiss 再执行，切换相位则不 dismiss）。 */
    class Action(val text: String, val onClick: () -> Unit)

    /** 弹窗被 dismiss 时回调（含本类 dismiss），用于清理持有方的引用。 */
    var onDismiss: (() -> Unit)? = null

    private val view = activity.layoutInflater.inflate(R.layout.dialog_update, null)
    private val phaseChecking = view.findViewById<View>(R.id.phase_checking)
    private val phaseResult = view.findViewById<TextView>(R.id.phase_result)
    private val phaseDownloading = view.findViewById<View>(R.id.phase_downloading)
    private val progressDownload = view.findViewById<ProgressBar>(R.id.progress_download)
    private val textDownloadBytes = view.findViewById<TextView>(R.id.text_download_bytes)
    private val buttonPrimary = view.findViewById<Button>(R.id.btn_update_primary)
    private val buttonSecondary = view.findViewById<Button>(R.id.btn_update_secondary)

    private var lastActionAt = 0L

    private val dialog = AdDialog.show(activity, view, widthRatio = 0.88f, cancelable = false).also {
        it.setOnDismissListener { onDismiss?.invoke() }
    }

    fun isShowing(): Boolean = dialog.isShowing

    /** 宿主 Activity 已销毁/正在销毁（此时弹窗可能仍标记为 showing，属窗口泄漏状态）。 */
    fun isStale(): Boolean = activity.isFinishing || activity.isDestroyed

    /** 相位 1：检查中（无按钮，不可取消）。 */
    fun showChecking() {
        phaseChecking.visibility = View.VISIBLE
        phaseResult.visibility = View.GONE
        phaseDownloading.visibility = View.GONE
        bindAction(buttonPrimary, null)
        bindAction(buttonSecondary, null)
    }

    /** 相位 2：结果文案 + 可选按钮组。 */
    fun showResult(message: String, primary: Action?, secondary: Action? = null) {
        phaseChecking.visibility = View.GONE
        phaseResult.visibility = View.VISIBLE
        phaseDownloading.visibility = View.GONE
        phaseResult.text = message
        bindAction(buttonPrimary, primary)
        bindAction(buttonSecondary, secondary)
    }

    /** 相位 3：下载进度；主按钮变「取消」。 */
    fun showDownloading(onCancel: () -> Unit) {
        phaseChecking.visibility = View.GONE
        phaseResult.visibility = View.GONE
        phaseDownloading.visibility = View.VISIBLE
        textDownloadBytes.text = ""
        progressDownload.progress = 0
        progressDownload.isIndeterminate = true
        bindAction(buttonPrimary, Action("取消", onCancel))
        bindAction(buttonSecondary, null)
    }

    fun updateProgress(downloaded: Long, total: Long) {
        if (total > 0) {
            progressDownload.isIndeterminate = false
            progressDownload.max = 100
            progressDownload.progress = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
            textDownloadBytes.text = String.format(
                "%s / %s · %d%%",
                formatMb(downloaded), formatMb(total),
                (downloaded * 100) / total,
            )
        } else {
            progressDownload.isIndeterminate = true
            textDownloadBytes.text = "已下载 ${formatMb(downloaded)}"
        }
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun bindAction(button: Button, action: Action?) {
        button.isEnabled = true
        if (action == null) {
            button.visibility = View.GONE
            button.setOnClickListener(null)
        } else {
            button.visibility = View.VISIBLE
            button.text = action.text
            button.setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - lastActionAt < ACTION_DEBOUNCE_MS) return@setOnClickListener
                lastActionAt = now
                action.onClick()
            }
        }
    }

    private fun formatMb(bytes: Long): String = String.format("%.1f MB", bytes / 1024.0 / 1024.0)

    companion object {
        private const val ACTION_DEBOUNCE_MS = 500L
    }
}
