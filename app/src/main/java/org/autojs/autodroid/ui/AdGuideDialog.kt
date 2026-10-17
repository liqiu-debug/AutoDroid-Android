package org.autojs.autodroid.ui

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.autojs.autojs6.R

/** 引导弹窗（圆角卡片）：标题 + 说明 + 「暂不 / 去操作」。 */
object AdGuideDialog {

    fun show(
        activity: AppCompatActivity,
        title: String,
        message: String,
        positiveText: String,
        onPositive: () -> Unit,
    ) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_guide, null)
        view.findViewById<TextView>(R.id.text_guide_title).text = title
        view.findViewById<TextView>(R.id.text_guide_message).text = message
        view.findViewById<Button>(R.id.btn_guide_ok).text = positiveText

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        view.findViewById<View>(R.id.btn_guide_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btn_guide_ok).setOnClickListener {
            dialog.dismiss()
            onPositive()
        }
        dialog.show()
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.86f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }
}
