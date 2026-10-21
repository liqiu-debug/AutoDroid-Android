package org.autojs.autodroid.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.autojs.autojs6.BuildConfig
import org.autojs.autodroid.net.ServerAddress
import org.autojs.autodroid.schedule.ScheduleManager

/**
 * AutoDroid 本地会话与设置（服务端地址 / 登录态 / 设备名 / 通知开关）。
 *
 * 敏感字段（token）优先存入 EncryptedSharedPreferences；加密存储不可用时
 * 回退普通 SharedPreferences（开发阶段兜底，正式版再收紧）。
 */
object Session {

    private const val PREFS_NAME = "autodroid_runner_session"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_USERNAME = "username"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_NOTIFY_DEFAULT = "notify_default"

    @Volatile
    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    var onLoginCleared: (() -> Unit)? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        appContext = app
        prefs = try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                PREFS_NAME,
                masterKeyAlias,
                app,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    var serverUrl: String
        get() {
            val value = prefs?.getString(KEY_SERVER_URL, null)?.takeIf { it.isNotBlank() }
                ?: BuildConfig.AUTODROID_DEFAULT_SERVER
            return runCatching { ServerAddress.normalize(value) }.getOrDefault(value)
        }
        set(value) = prefs?.edit()?.putString(KEY_SERVER_URL, ServerAddress.normalize(value))?.apply() ?: Unit

    var token: String?
        get() = prefs?.getString(KEY_TOKEN, null)
        set(value) = prefs?.edit()?.putString(KEY_TOKEN, value)?.apply() ?: Unit

    var username: String?
        get() = prefs?.getString(KEY_USERNAME, null)
        set(value) = prefs?.edit()?.putString(KEY_USERNAME, value)?.apply() ?: Unit

    var deviceName: String
        get() = prefs?.getString(KEY_DEVICE_NAME, null) ?: ""
        set(value) = prefs?.edit()?.putString(KEY_DEVICE_NAME, value.trim())?.apply() ?: Unit

    var notifyDefault: Boolean
        get() = prefs?.getBoolean(KEY_NOTIFY_DEFAULT, true) ?: true
        set(value) = prefs?.edit()?.putBoolean(KEY_NOTIFY_DEFAULT, value)?.apply() ?: Unit

    fun clearLogin() {
        prefs?.edit()?.remove(KEY_TOKEN)?.apply()
        appContext?.let { ScheduleManager.suspendAll(it) }
        onLoginCleared?.invoke()
    }
}
