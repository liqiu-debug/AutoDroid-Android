package org.autojs.autodroid.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.ResponseBody
import org.autojs.autodroid.data.Session
import org.autojs.autodroid.net.AutoDroidApi
import org.autojs.autojs6.R
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * 报告详情：带登录态下载 HTML 报告，用 WebView 渲染
 * （报告内截图等资源走 /api/report-assets 静态挂载，无需鉴权）。
 */
class ReportDetailActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report_detail)

        val executionId = intent.getLongExtra(EXTRA_EXECUTION_ID, -1L)
        findViewById<TextView>(R.id.text_title).text =
            intent.getStringExtra(EXTRA_TITLE) ?: "执行报告"

        val statusText = findViewById<TextView>(R.id.text_status)
        val webView = findViewById<WebView>(R.id.web_report)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        if (executionId < 0) {
            statusText.text = "缺少执行记录 ID"
            return
        }
        statusText.text = "加载报告中…"
        AutoDroidApi.service(Session.serverUrl)
            .downloadReport(executionId)
            .enqueue(object : Callback<ResponseBody> {
                override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                    val body = response.body()
                    if (!response.isSuccessful || body == null) {
                        statusText.text = "报告加载失败（HTTP ${response.code()}）"
                        return
                    }
                    try {
                        val html = body.string()
                        statusText.visibility = View.GONE
                        val baseUrl = Session.serverUrl.let {
                            if (it.endsWith("/")) it else "$it/"
                        }
                        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
                    } catch (e: Exception) {
                        statusText.text = "报告解析失败：${e.message}"
                    }
                }

                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                    statusText.text = "报告加载失败：${t.message}"
                }
            })
    }

    companion object {
        const val EXTRA_EXECUTION_ID = "execution_id"
        const val EXTRA_TITLE = "title"
    }
}
