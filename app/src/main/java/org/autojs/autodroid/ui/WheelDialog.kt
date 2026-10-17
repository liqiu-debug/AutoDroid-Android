package org.autojs.autodroid.ui

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import org.autojs.autojs6.R
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/**
 * 滚轮选择弹窗（iOS 闹钟风格）：
 * - 时间：小时/分钟两列
 * - 日期：年/月/日三列（天数随年月联动）
 *
 * 选中行落在中间高亮带内，距中越远的行越淡越小，松手自动吸附到最近一行。
 */
object WheelDialog {

    private const val ITEM_HEIGHT_DP = 44
    private const val WHEEL_HEIGHT_DP = 216

    // ------------------------------------------------------------------ //
    //  时间
    // ------------------------------------------------------------------ //

    fun showTime(activity: AppCompatActivity, initialMinutes: Int, onPicked: (Int) -> Unit) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_time_wheel, null)
        val preview = view.findViewById<TextView>(R.id.text_time_preview)
        val hourWheel = Wheel(activity, view.findViewById(R.id.wheel_hour))
        val minuteWheel = Wheel(activity, view.findViewById(R.id.wheel_minute))

        var hour = (initialMinutes / 60).coerceIn(0, 23)
        var minute = (initialMinutes % 60).coerceIn(0, 59)

        fun refreshPreview() {
            preview.text = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
        }
        refreshPreview()

        hourWheel.onChange = { value -> hour = value; refreshPreview() }
        minuteWheel.onChange = { value -> minute = value; refreshPreview() }
        hourWheel.setValues((0..23).map { String.format(Locale.getDefault(), "%02d", it) }, hour)
        minuteWheel.setValues((0..59).map { String.format(Locale.getDefault(), "%02d", it) }, minute)

        val dialog = show(activity, view)
        view.findViewById<View>(R.id.btn_time_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btn_time_ok).setOnClickListener {
            onPicked(hour * 60 + minute)
            dialog.dismiss()
        }
    }

    // ------------------------------------------------------------------ //
    //  日期（年/月/日，天数随年月联动）
    // ------------------------------------------------------------------ //

    fun showDate(
        activity: AppCompatActivity,
        initialYear: Int,
        initialMonth0: Int,
        initialDay: Int,
        onPicked: (year: Int, month0: Int, day: Int) -> Unit,
    ) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_date_wheel, null)
        val preview = view.findViewById<TextView>(R.id.text_date_preview)
        val yearWheel = Wheel(activity, view.findViewById(R.id.wheel_year))
        val monthWheel = Wheel(activity, view.findViewById(R.id.wheel_month))
        val dayWheel = Wheel(activity, view.findViewById(R.id.wheel_day))

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)

        val years = (minOf(currentYear, initialYear)..maxOf(currentYear + 5, initialYear)).toList()
        val months = (1..12).toList()

        var year = initialYear
        var month0 = initialMonth0.coerceIn(0, 11)
        var day = initialDay

        fun daysInMonth(y: Int, m0: Int): Int =
            Calendar.getInstance().apply {
                clear()
                set(y, m0, 1)
            }.getActualMaximum(Calendar.DAY_OF_MONTH)

        fun refreshPreview() {
            preview.text = String.format(Locale.getDefault(), "%04d-%02d-%02d", year, month0 + 1, day)
        }

        fun refreshDays() {
            val maxDay = daysInMonth(year, month0)
            if (day > maxDay) day = maxDay
            dayWheel.setValues((1..maxDay).map { it.toString() }, day - 1)
            refreshPreview()
        }

        yearWheel.onChange = { index ->
            year = years[index]
            refreshDays()
        }
        monthWheel.onChange = { index ->
            month0 = months[index] - 1
            refreshDays()
        }
        dayWheel.onChange = { index ->
            day = index + 1
            refreshPreview()
        }

        yearWheel.setValues(years.map { it.toString() }, years.indexOf(year).coerceAtLeast(0))
        monthWheel.setValues(months.map { it.toString() }, month0)
        dayWheel.setValues((1..daysInMonth(year, month0)).map { it.toString() }, (day - 1).coerceAtLeast(0))
        refreshPreview()

        val dialog = show(activity, view)
        view.findViewById<View>(R.id.btn_date_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btn_date_ok).setOnClickListener {
            onPicked(year, month0, day)
            dialog.dismiss()
        }
    }

    // ------------------------------------------------------------------ //
    //  内部
    // ------------------------------------------------------------------ //

    private fun show(activity: AppCompatActivity, view: View): AlertDialog {
        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        return dialog
    }

    /** 一列滚轮：绑定 RecyclerView，用户滚动导致居中项变化时回调索引。 */
    private class Wheel(activity: AppCompatActivity, private val recycler: RecyclerView) {

        var onChange: ((Int) -> Unit)? = null

        private val density = activity.resources.displayMetrics.density
        private val itemHeight = (ITEM_HEIGHT_DP * density).toInt()
        private val padding = ((WHEEL_HEIGHT_DP - ITEM_HEIGHT_DP) / 2f * density).toInt()
        private val adapter = WheelAdapter()

        /**
         * 程序化滚动（初始化 / 换值）期间不回传：调用方已经把状态设为目标值，
         * 而跳转后的布局位置可能仍是旧的，回传会用错误值覆盖状态。
         * 用户真正滚动时才恢复回传。
         */
        private var suppressReport = true

        init {
            recycler.setPadding(0, padding, 0, padding)
            recycler.clipToPadding = false
            recycler.layoutManager = LinearLayoutManager(activity)
            recycler.adapter = adapter
            recycler.itemAnimator = null
            LinearSnapHelper().attachToRecyclerView(recycler)
            recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    applyStyle()
                    // 程序化跳转也会触发 onScrolled（dy=0，且布局可能尚未更新），必须忽略，
                    // 否则会用旧位置覆盖当前状态
                    if (dy == 0) return
                    suppressReport = false
                    reportCentered()
                }

                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        suppressReport = false
                        reportCentered()
                    }
                }
            })
        }

        fun setValues(values: List<String>, index: Int) {
            val target = index.coerceIn(0, (values.size - 1).coerceAtLeast(0))
            adapter.values = values
            adapter.notifyDataSetChanged()
            suppressReport = true
            recycler.post {
                // offset 相对「内容区起点」（padding 之后），因此传 0 才能让该项落在中间高亮带
                (recycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(target, 0)
                applyStyle()
            }
        }

        /** 当前居中项的索引；尚未布局出子视图时返回 null。 */
        fun currentIndex(): Int? {
            val lm = recycler.layoutManager as? LinearLayoutManager ?: return null
            if (lm.childCount == 0) return null
            val center = recycler.height / 2
            var bestPosition = -1
            var bestDistance = Int.MAX_VALUE
            for (i in 0 until lm.childCount) {
                val child = lm.getChildAt(i) ?: continue
                val distance = abs((child.top + child.bottom) / 2 - center)
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestPosition = lm.getPosition(child)
                }
            }
            return if (bestPosition >= 0) bestPosition else null
        }

        private fun reportCentered() {
            if (suppressReport) return
            val index = currentIndex() ?: return
            onChange?.invoke(index)
        }

        /** 距中越远的行：越淡、越小。 */
        private fun applyStyle() {
            val lm = recycler.layoutManager as? LinearLayoutManager ?: return
            val center = recycler.height / 2
            for (i in 0 until lm.childCount) {
                val child = lm.getChildAt(i) ?: continue
                val rows = abs((child.top + child.bottom) / 2 - center).toFloat() / itemHeight
                val scale = (1f - 0.12f * rows.coerceAtMost(2f)).coerceAtLeast(0.76f)
                val alpha = (1f - 0.45f * rows.coerceAtMost(2f)).coerceAtLeast(0.1f)
                child.scaleX = scale
                child.scaleY = scale
                child.alpha = alpha
            }
        }
    }

    private class WheelAdapter : RecyclerView.Adapter<WheelAdapter.Holder>() {

        var values: List<String> = emptyList()

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view as TextView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_wheel_value, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.text.text = values.getOrNull(position) ?: ""
        }

        override fun getItemCount(): Int = values.size
    }
}
