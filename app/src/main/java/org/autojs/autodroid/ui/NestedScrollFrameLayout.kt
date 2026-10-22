package org.autojs.autodroid.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 可滚动能力代理容器：把 canScrollVertically 委托给子 View。
 *
 * SwipeRefreshLayout 判断"子 View 是否还能上滚"只询问其**直接子 View**；
 * 空态改造把 ListView 包进 FrameLayout 后，容器本身永远不可滚，
 * 列表中部的下拉会被误判为"已在顶部"而触发刷新。本容器让判断穿透到
 * 内部的 ListView，恢复"滑回顶部途中不刷新、到顶再下拉才刷新"的语义。
 */
class NestedScrollFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    override fun canScrollVertically(direction: Int): Boolean {
        for (index in 0 until childCount) {
            if (getChildAt(index).canScrollVertically(direction)) return true
        }
        return false
    }
}
