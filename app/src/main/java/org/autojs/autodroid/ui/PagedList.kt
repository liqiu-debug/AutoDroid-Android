package org.autojs.autodroid.ui

import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.autojs.autodroid.net.PageLoader

fun <P, T> bindPagedList(
    list: ListView,
    pager: PageLoader<P, T>,
    status: TextView,
    swipe: SwipeRefreshLayout,
    emptyText: String,
    totalText: (Int) -> String,
    setItems: (List<T>) -> Unit,
) {
    val more = Button(list.context).apply {
        text = "加载更多"
        setOnClickListener { pager.loadMore() }
    }
    list.addFooterView(more, null, false)
    pager.onChanged = {
        setItems(pager.items)
        if (!pager.loading) swipe.isRefreshing = false
        status.text = pager.error ?: when {
            pager.loading && pager.items.isEmpty() -> "加载中…"
            pager.items.isEmpty() -> emptyText
            else -> "${totalText(pager.total)}（已加载 ${pager.items.size}）"
        }
        more.visibility = if (pager.hasMore) View.VISIBLE else View.GONE
        more.isEnabled = !pager.loading
        more.text = if (pager.loading) "加载中…" else if (pager.error != null) "重试加载更多" else "加载更多"
    }
}
