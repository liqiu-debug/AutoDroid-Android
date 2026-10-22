package org.autojs.autodroid.ui

import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.autojs.autodroid.net.PageLoader
import org.autojs.autojs6.R

fun <P, T> bindPagedList(
    list: ListView,
    pager: PageLoader<P, T>,
    status: TextView,
    swipe: SwipeRefreshLayout,
    emptyText: String,
    totalText: (Int) -> String,
    setItems: (List<T>) -> Unit,
    emptyHint: String? = null,
    @DrawableRes emptyIcon: Int? = null,
) {
    val emptyView = emptyHint?.let {
        AdEmptyState.bind(list, title = emptyText, hint = it, iconRes = emptyIcon)
    }
    val more = Button(list.context).apply {
        text = "加载更多"
        setBackgroundResource(R.drawable.ad_bg_button_secondary)
        setTextColor(ContextCompat.getColor(context, R.color.ad_text))
        textSize = 14f
        minWidth = 0
        minHeight = 40
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, 0, pad, 0)
        setOnClickListener { pager.loadMore() }
    }
    list.addFooterView(more, null, false)
    pager.onChanged = {
        setItems(pager.items)
        if (!pager.loading) swipe.isRefreshing = false
        status.text = pager.error ?: when {
            pager.loading && pager.items.isEmpty() -> "加载中…"
            pager.items.isEmpty() -> if (emptyHint != null) "" else emptyText
            else -> "${totalText(pager.total)}（已加载 ${pager.items.size}）"
        }
        // 空态只在"非加载、无错误、确认为空"时显示；加载中/出错/有数据一律隐藏
        emptyView?.visibility =
            if (!pager.loading && pager.error == null && pager.items.isEmpty()) View.VISIBLE else View.GONE
        more.visibility = if (pager.hasMore) View.VISIBLE else View.GONE
        more.isEnabled = !pager.loading
        more.text = if (pager.loading) "加载中…" else if (pager.error != null) "重试加载更多" else "加载更多"
    }
}
