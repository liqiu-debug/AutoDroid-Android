package org.autojs.autodroid.ui

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 圆角卡片弹窗薄壳：AlertDialog + 根布局 ad_bg_dialog 圆角 + 透明 window +
 * 固定屏宽比例（与 PickerDialog / AdGuideDialog 一致的呈现）。
 */
object AdDialog {

    fun show(
        activity: AppCompatActivity,
        content: View,
        widthRatio: Float = 0.88f,
        cancelable: Boolean = true,
    ): AlertDialog {
        val dialog = AlertDialog.Builder(activity)
            .setView(content)
            .create()
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.window?.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * widthRatio).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        return dialog
    }
}
