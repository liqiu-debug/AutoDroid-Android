package org.autojs.autodroid.ui

import android.view.MotionEvent
import android.view.View
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * 下拉时让内容整体跟随手指下移，松手回弹。
 *
 * 说明：androidx SwipeRefreshLayout 1.1.0 起只位移刷新指示器（不再位移内容），
 * 因此这里在它接管手势后额外平移内容视图，得到「整页下拉」的观感。
 */
object PullDownMoveHelper {

    fun attach(swipe: SwipeRefreshLayout, content: View) {
        val density = swipe.resources.displayMetrics.density
        val maxPull = 76 * density
        var tracking = false
        var lastY = 0f
        var pull = 0f

        swipe.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking) {
                        // 事件被 SwipeRefreshLayout 接管后才会走到这里，首次进入时记录起点
                        tracking = true
                        lastY = event.y
                    }
                    val dy = event.y - lastY
                    lastY = event.y
                    pull = (pull + dy * 0.5f).coerceIn(0f, maxPull)
                    content.translationY = pull
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracking = false
                    if (pull != 0f) {
                        pull = 0f
                        content.animate().translationY(0f).setDuration(200).start()
                    }
                }
            }
            false // 不消费事件，仍交给 SwipeRefreshLayout 处理（指示器与刷新触发）
        }
    }
}
