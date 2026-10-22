package org.autojs.autodroid.ui

import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.autojs.autojs6.R

/** 引导弹窗（圆角卡片）：标题 + 说明 + 双按钮；单按钮时隐藏取消键。 */
object AdGuideDialog {

    fun show(
        activity: AppCompatActivity,
        title: String,
        message: String,
        positiveText: String,
        negativeText: String = "暂不",
        @ColorRes positiveColorRes: Int = R.color.ad_primary,
        onPositive: () -> Unit,
    ) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_guide, null)
        view.findViewById<TextView>(R.id.text_guide_title).text = title
        view.findViewById<TextView>(R.id.text_guide_message).text = message
        val okButton = view.findViewById<Button>(R.id.btn_guide_ok)
        val cancelButton = view.findViewById<Button>(R.id.btn_guide_cancel)
        okButton.text = positiveText
        okButton.setTextColor(ContextCompat.getColor(activity, positiveColorRes))
        cancelButton.text = negativeText

        val dialog = AdDialog.show(activity, view, widthRatio = 0.86f)
        cancelButton.setOnClickListener { dialog.dismiss() }
        okButton.setOnClickListener {
            dialog.dismiss()
            onPositive()
        }
    }

    /** 单按钮提示（隐藏取消键），用于「知道了」类信息弹窗。 */
    fun showInfo(
        activity: AppCompatActivity,
        title: String,
        message: String,
        positiveText: String = "知道了",
    ) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_guide, null)
        view.findViewById<TextView>(R.id.text_guide_title).text = title
        view.findViewById<TextView>(R.id.text_guide_message).text = message
        view.findViewById<Button>(R.id.btn_guide_ok).text = positiveText
        view.findViewById<View>(R.id.btn_guide_cancel).visibility = View.GONE

        val dialog = AdDialog.show(activity, view, widthRatio = 0.86f)
        view.findViewById<View>(R.id.btn_guide_ok).setOnClickListener { dialog.dismiss() }
    }
}
