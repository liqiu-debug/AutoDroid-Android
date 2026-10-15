package org.autojs.autodroid.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.autojs.autodroid.data.Session
import org.autojs.autodroid.net.AutoDroidApi
import org.autojs.autodroid.net.DeviceRegisterResponse
import org.autojs.autodroid.net.LoginResponse
import org.autojs.autojs6.R
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * 登录页：服务器地址（可改）+ 账号密码 → 平台 /auth/token → 保存登录态 → 注册设备。
 */
class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val serverInput = findViewById<EditText>(R.id.input_server)
        val usernameInput = findViewById<EditText>(R.id.input_username)
        val passwordInput = findViewById<EditText>(R.id.input_password)
        val loginButton = findViewById<Button>(R.id.btn_login)
        val statusText = findViewById<TextView>(R.id.text_status)

        serverInput.setText(Session.serverUrl)
        usernameInput.setText(Session.username ?: "")

        loginButton.setOnClickListener {
            val server = serverInput.text.toString().trim()
            val username = usernameInput.text.toString().trim()
            val password = passwordInput.text.toString()
            when {
                server.isEmpty() -> toast("请填写服务器地址")
                !server.startsWith("http") -> toast("服务器地址需以 http:// 或 https:// 开头")
                username.isEmpty() || password.isEmpty() -> toast("请填写账号与密码")
                else -> doLogin(server, username, password, loginButton, statusText)
            }
        }
    }

    private fun doLogin(
        server: String,
        username: String,
        password: String,
        loginButton: Button,
        statusText: TextView,
    ) {
        loginButton.isEnabled = false
        statusText.text = "登录中…"
        Session.serverUrl = server
        AutoDroidApi.service(server) { null }.login(username, password)
            .enqueue(object : Callback<LoginResponse> {
                override fun onResponse(call: Call<LoginResponse>, response: Response<LoginResponse>) {
                    loginButton.isEnabled = true
                    val token = response.body()?.access_token
                    if (response.isSuccessful && !token.isNullOrBlank()) {
                        Session.token = token
                        Session.username = username
                        statusText.text = "登录成功，正在注册设备…"
                        registerDevice()
                        startActivity(Intent(this@LoginActivity, CaseListActivity::class.java))
                        finish()
                    } else {
                        statusText.text = "登录失败（HTTP ${response.code()}），请检查账号密码或服务器地址"
                    }
                }

                override fun onFailure(call: Call<LoginResponse>, t: Throwable) {
                    loginButton.isEnabled = true
                    statusText.text = "连接失败：${t.message}\n请检查服务器地址与网络（设备需能访问平台所在局域网）"
                }
            })
    }

    /** 登录成功后向平台注册本机（设备中心可见 autojs6:<androidId>）。 */
    private fun registerDevice() {
        val payload = mapOf(
            "android_id" to Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID),
            "name" to Session.deviceName.ifBlank { Build.MODEL ?: "" },
            "model" to (Build.MODEL ?: ""),
            "brand" to (Build.BRAND ?: ""),
            "android_version" to (Build.VERSION.RELEASE ?: ""),
            "resolution" to "${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}",
        )
        AutoDroidApi.service(Session.serverUrl) { Session.token }
            .registerDevice(payload)
            .enqueue(object : Callback<DeviceRegisterResponse> {
                override fun onResponse(call: Call<DeviceRegisterResponse>, response: Response<DeviceRegisterResponse>) {
                    Log.i(TAG, "设备注册完成: ${response.body()?.device_serial}")
                }

                override fun onFailure(call: Call<DeviceRegisterResponse>, t: Throwable) {
                    Log.w(TAG, "设备注册失败（不影响使用）: ${t.message}")
                }
            })
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "AutoDroidRunner"
    }
}
