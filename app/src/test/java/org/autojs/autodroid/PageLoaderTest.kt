package org.autojs.autodroid

import okhttp3.Request
import okio.Timeout
import org.autojs.autodroid.net.PageLoader
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class PageLoaderTest {
    private data class Page(val items: List<Int>, val total: Int)
    private class PendingCall : Call<Page> {
        lateinit var callback: Callback<Page>
        private var cancelled = false
        override fun enqueue(callback: Callback<Page>) { this.callback = callback }
        fun respond(vararg ids: Int, total: Int) { callback.onResponse(this, Response.success(Page(ids.toList(), total))) }
        override fun cancel() { cancelled = true }
        override fun isCanceled() = cancelled
        override fun isExecuted() = ::callback.isInitialized
        override fun clone(): Call<Page> = PendingCall()
        override fun request() = Request.Builder().url("https://audit.invalid").build()
        override fun timeout() = Timeout.NONE
        override fun execute(): Response<Page> = error("Asynchronous test only")
    }

    @Test fun loadsEveryPageOnceAndKeepsTheFirstPage() {
        val calls = mutableListOf<Pair<Int, PendingCall>>()
        val pager = PageLoader(request = { skip -> PendingCall().also { calls.add(skip to it) } },
            unpack = { page: Page -> page.items to page.total }, sessionKey = { "account" })
        pager.refresh(); calls[0].second.respond(1, 2, total = 3)
        pager.loadMore(); pager.loadMore()
        assertEquals(listOf(0, 2), calls.map { it.first })
        calls[1].second.respond(3, total = 3)
        assertEquals(listOf(1, 2, 3), pager.items)
        assertFalse(pager.hasMore)
        pager.loadMore(); assertEquals(2, calls.size)
    }

    @Test fun lateSearchResultsCannotOverwriteTheLatestSearch() {
        val calls = mutableListOf<PendingCall>()
        val pager = PageLoader(request = { _: Int -> PendingCall().also { calls.add(it) } },
            unpack = { page: Page -> page.items to page.total }, sessionKey = { "account" })
        pager.refresh(); pager.refresh()
        assertTrue(calls[0].isCanceled)
        calls[1].respond(9, total = 1)
        calls[0].respond(1, 2, total = 2)
        assertEquals(listOf(9), pager.items)
        assertFalse(pager.loading)
    }

    @Test fun accountChangesDiscardOldResultsAndStopLoading() {
        var account = "alice"
        val call = PendingCall()
        val pager = PageLoader(request = { _: Int -> call },
            unpack = { page: Page -> page.items to page.total }, sessionKey = { account })
        pager.refresh(); account = "bob"; call.respond(1, total = 1)
        assertTrue(pager.items.isEmpty())
        assertFalse(pager.loading)
        assertNotNull(pager.error)
    }
}
