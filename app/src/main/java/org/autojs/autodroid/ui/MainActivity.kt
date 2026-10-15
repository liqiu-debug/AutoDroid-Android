package org.autojs.autodroid.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * AutoDroid Runner 主入口。
 *
 * 平台客户端 UI 的壳：后续按计划接入
 * 登录 / 用例 / 场景 / 运行 / 报告 / 定时任务 / 设置 页面。
 * 执行引擎复用本应用内的 AutoJs6（Rhino + 无障碍），不再依赖外部 Agent。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.WHITE)
        }
        layout.addView(TextView(this).apply {
            text = "AutoDroid Runner"
            textSize = 24f
            setTextColor(Color.parseColor("#1F2329"))
            gravity = Gravity.CENTER
        })
        layout.addView(TextView(this).apply {
            text = "平台客户端骨架已就位\n（登录 / 用例 / 运行 / 报告 / 定时任务 待接入）"
            textSize = 14f
            setTextColor(Color.parseColor("#646A73"))
            gravity = Gravity.CENTER
            setPadding(0, (12 * density).toInt(), 0, 0)
        })
        setContentView(layout)
    }
}
