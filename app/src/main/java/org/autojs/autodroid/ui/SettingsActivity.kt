package org.autojs.autodroid.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.autojs.autodroid.data.Session
import org.autojs.autojs6.R

/**
 * 设置页：服务器地址 / 设备名 / 飞书通知默认开关 / 无障碍与电池引导 / AutoJs6 控制台入口。
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val serverInput = findViewById<EditText>(R.id.input_server)
        val deviceNameInput = findViewById<EditText>(R.id.input_device_name)
        val notifySwitch = findViewById<Switch>(R.id.switch_notify)
        val accessibilityText = findViewById<TextView>(R.id.text_accessibility)

        serverInput.setText(Session.serverUrl)
        deviceNameInput.setText(Session.deviceName)
        notifySwitch.isChecked = Session.notifyDefault

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            Session.serverUrl = serverInput.text.toString()
            Session.deviceName = deviceNameInput.text.toString()
            Session.notifyDefault = notifySwitch.isChecked
            toast("已保存")
        }

        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btn_battery).setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                toast("请在系统设置 → 电池 中允许本应用后台运行")
            }
        }

        findViewById<Button>(R.id.btn_autojs).setOnClickListener {
            try {
                startActivity(Intent(this, org.autojs.autojs.ui.main.MainActivity::class.java))
            } catch (e: Exception) {
                toast("AutoJs6 控制台打开失败: ${e.message}")
            }
        }

        findViewById<Button>(R.id.btn_logout).setOnClickListener {
            Session.clearLogin()
            toast("已退出登录")
            startActivity(Intent(this, LoginActivity::class.java))
            finishAffinity()
        }

        refreshAccessibilityState(accessibilityText)
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityState(findViewById(R.id.text_accessibility))
    }

    private fun refreshAccessibilityState(textView: TextView) {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val serviceOn = enabled.contains("org.autojs.autojs.core.accessibility.AccessibilityServiceUsher")
        textView.text = if (serviceOn) {
            "无障碍服务：已开启 ✓\n（提示：设备端执行期间不要用电脑上的平台页面驱动本机，uiautomator2 会抢占无障碍）"
        } else {
            "无障碍服务：未开启 ✗  —— UI 自动化执行必需，点击下方按钮开启"
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
