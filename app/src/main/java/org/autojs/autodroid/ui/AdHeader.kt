package org.autojs.autodroid.ui

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.autojs.autojs6.R

/** 统一头部：设置标题 + 返回按钮（点击 finish）。 */
object AdHeader {

    fun bind(activity: AppCompatActivity, title: String, showBack: Boolean = true) {
        activity.findViewById<TextView>(R.id.text_title)?.text = title
        val back = activity.findViewById<View>(R.id.btn_back)
        if (back != null) {
            // 五个主导航页为平级页面，不显示返回按钮（子页面显示）
            back.visibility = if (showBack) View.VISIBLE else View.INVISIBLE
            back.setOnClickListener { activity.finish() }
        }
    }
}
