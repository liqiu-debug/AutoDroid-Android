package org.autojs.autodroid.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.autojs.autojs6.R

/**
 * 底部导航：5 个 tab（用例/场景/报告/定时/设置），选中项主色高亮，切换用 REORDER_TO_FRONT 避免堆栈。
 */
object AdBottomNav {

    private data class Tab(val key: String, val tabId: Int, val activity: Class<*>)

    private val TABS = listOf(
        Tab("cases", R.id.tab_cases, CaseListActivity::class.java),
        Tab("scenarios", R.id.tab_scenarios, ScenarioListActivity::class.java),
        Tab("reports", R.id.tab_reports, ReportListActivity::class.java),
        Tab("schedules", R.id.tab_schedules, ScheduleListActivity::class.java),
        Tab("settings", R.id.tab_settings, SettingsActivity::class.java),
    )

    fun bind(activity: AppCompatActivity, currentKey: String) {
        val selectedColor = ContextCompat.getColor(activity, R.color.ad_primary)
        val idleColor = ContextCompat.getColor(activity, R.color.ad_idle)
        for (tab in TABS) {
            val container = activity.findViewById<ViewGroup>(tab.tabId) ?: continue
            val selected = tab.key == currentKey
            tint(container, if (selected) selectedColor else idleColor, selected)
            if (!selected) {
                container.setOnClickListener {
                    activity.startActivity(
                        Intent(activity, tab.activity)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                    // 平级切换：禁用页面过渡动画，避免"进入下一级"的滑动观感
                    activity.overridePendingTransition(0, 0)
                }
            }
        }
    }

    private fun tint(container: ViewGroup, color: Int, bold: Boolean) {
        for (index in 0 until container.childCount) {
            when (val child = container.getChildAt(index)) {
                is ImageView -> child.setColorFilter(color)
                is TextView -> {
                    child.setTextColor(color)
                    child.setTypeface(null, if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                }
                is ViewGroup -> tint(child, color, bold)
                else -> Unit
            }
        }
    }
}
