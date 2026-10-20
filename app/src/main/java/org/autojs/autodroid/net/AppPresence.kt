package org.autojs.autodroid.net

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.autojs.autodroid.data.Session
import org.autojs.autojs.AutoJs
import org.autojs.autojs.core.accessibility.AccessibilityTool
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** App heartbeats are independent of ADB. No background service is started solely for presence. */
object AppPresence : Application.ActivityLifecycleCallbacks {
    private lateinit var app: Application
    private val handler = Handler(Looper.getMainLooper())
    private val queue = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
    private var visible = 0
    private var lastSent = 0L
    private var lastState = ""
    private var lastAccount = ""
    private val tick = object : Runnable {
        override fun run() { heartbeat(); handler.postDelayed(this, 15_000) }
    }

    fun install(application: Application) {
        if (::app.isInitialized) return
        app = application
        Session.init(app)
        app.registerActivityLifecycleCallbacks(this)
        handler.post(tick)
    }

    fun refreshNow() { handler.post { lastSent = 0; heartbeat(force = true) } }

    private fun payload(state: String) = JSONObject().apply {
        put("platform", "android")
        put("android_id", Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID))
        put("name", Session.deviceName.ifBlank { Build.MODEL })
        put("model", Build.MODEL); put("brand", Build.BRAND)
        put("android_version", Build.VERSION.RELEASE); put("os_version", Build.VERSION.RELEASE)
        put("resolution", "${app.resources.displayMetrics.widthPixels}x${app.resources.displayMetrics.heightPixels}")
        put("executor_status", state)
    }

    private fun heartbeat(force: Boolean = false) {
        val token = Session.token?.takeIf { it.isNotBlank() } ?: return
        val running = runCatching { AutoJs.instance.scriptEngineService.engines.isNotEmpty() }.getOrDefault(false)
        if (!force && visible == 0 && !running) return
        val state = if (running) "RUNNING" else if (runCatching { AccessibilityTool(app).isRunning() }.getOrDefault(false)) "READY" else "NOT_READY"
        val server = Session.serverUrl
        val account = "$server|${Session.username}"
        val now = SystemClock.elapsedRealtime()
        if (!force && lastSent != 0L && now - lastSent < 60_000 && state == lastState && account == lastAccount) return
        lastSent = now; lastState = state; lastAccount = account
        val body = payload(state).toString()
        queue.execute {
            // Do not resurrect an account after logout while this heartbeat was queued.
            if (Session.token == token && Session.serverUrl == server) send(server, token, "register", body)
        }
    }

    fun logout() {
        val token = Session.token
        val server = Session.serverUrl
        val body = if (::app.isInitialized) payload("UNKNOWN").toString() else null
        Session.clearLogin()
        lastSent = 0; lastAccount = ""
        if (!token.isNullOrBlank() && body != null) queue.execute { send(server, token, "logout", body) }
    }

    private fun send(server: String, token: String, action: String, body: String) {
        runCatching {
            val request = Request.Builder().url("${server.trimEnd('/')}/device-runs/$action")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody("application/json".toMediaType())).build()
            http.newCall(request).execute().use { response ->
                if (response.code == 401 && Session.token == token && Session.serverUrl == server) Session.clearLogin()
            }
        }
        // Network failure leaves the last known heartbeat to expire on the platform.
    }

    override fun onActivityStarted(activity: Activity) { visible++; heartbeat() }
    override fun onActivityStopped(activity: Activity) { visible = (visible - 1).coerceAtLeast(0) }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
