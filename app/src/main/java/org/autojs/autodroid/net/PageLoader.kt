package org.autojs.autodroid.net

import org.autojs.autodroid.data.Session
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/** Cancels superseded searches and keeps every page within the same login. */
class PageLoader<P, T>(
    private val request: (Int) -> Call<P>,
    private val unpack: (P) -> Pair<List<T>, Int>,
    private val sessionKey: () -> Any? = { Triple(Session.serverUrl, Session.username, Session.token) },
) {
    var items: List<T> = emptyList(); private set
    var total = 0; private set
    var loading = false; private set
    var error: String? = null; private set
    var onChanged: () -> Unit = {}
    private var generation = 0
    private var nextSkip = 0
    private var exhausted = false
    private var scope: Any? = null
    private var active: Call<P>? = null
    val hasMore: Boolean get() = !exhausted && nextSkip < total

    fun refresh() {
        cancel()
        items = emptyList(); total = 0; nextSkip = 0; exhausted = false
        scope = sessionKey()
        loadPage()
    }

    fun loadMore() {
        if (!loading && hasMore && scope == sessionKey()) loadPage()
    }

    fun cancel() {
        generation++
        active?.cancel(); active = null; loading = false
    }

    private fun loadPage() {
        val version = generation
        loading = true; error = null; onChanged()
        fun isCurrent(): Boolean {
            if (generation != version) return false
            if (scope == sessionKey()) return true
            cancel(); items = emptyList(); total = 0
            error = "登录状态已变化，请重新登录"; onChanged()
            return false
        }
        fun fail(message: String) {
            if (!isCurrent()) return
            loading = false; error = message; active = null; onChanged()
        }
        try {
            val call = request(nextSkip)
            active = call
            call.enqueue(object : Callback<P> {
                override fun onResponse(call: Call<P>, response: Response<P>) {
                    if (!isCurrent()) return
                    val body = response.body()
                    if (!response.isSuccessful || body == null) {
                        fail("加载失败（HTTP ${response.code()}）")
                        return
                    }
                    val (page, count) = unpack(body)
                    items = items + page; nextSkip += page.size; total = count
                    exhausted = page.isEmpty()
                    loading = false; active = null; onChanged()
                }
                override fun onFailure(call: Call<P>, t: Throwable) {
                    fail("加载失败：${t.message}")
                }
            })
        } catch (error: Exception) {
            fail("加载失败：${error.message}")
        }
    }
}
