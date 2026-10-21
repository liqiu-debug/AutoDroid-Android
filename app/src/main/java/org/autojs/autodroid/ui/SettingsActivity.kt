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
import org.autojs.autodroid.net.ServerAddress
import org.autojs.autojs6.R

/**
 * 设置页：服务器地址 / 设备名 / 飞书通知默认开关 / 无障碍与电池引导 / 运行引擎控制台入口。
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        AdHeader.bind(this, "设置", showBack = false)
        AdBottomNav.bind(this, "settings")

        val serverInput = findViewById<EditText>(R.id.input_server)
        val deviceNameInput = findViewById<EditText>(R.id.input_device_name)
        val notifySwitch = findViewById<Switch>(R.id.switch_notify)
        val accessibilityText = findViewById<TextView>(R.id.text_accessibility)

        serverInput.setText(Session.serverUrl)
        deviceNameInput.setText(Session.deviceName)
        notifySwitch.isChecked = Session.notifyDefault

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            val server = try { ServerAddress.normalize(serverInput.text.toString()) }
                catch (error: IllegalArgumentException) { toast(error.message ?: "平台地址无效"); return@setOnClickListener }
            val serverChanged = Session.serverUrl != server
            if (serverChanged) org.autojs.autodroid.net.AppPresence.logout()
            Session.serverUrl = server
            Session.deviceName = deviceNameInput.text.toString()
            Session.notifyDefault = notifySwitch.isChecked
            toast("已保存")
            if (serverChanged) {
                startActivity(Intent(this, LoginActivity::class.java))
                finishAffinity()
            } else org.autojs.autodroid.net.AppPresence.refreshNow()
        }

        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btn_battery).setOnClickListener { requestBatteryWhitelist() }

        findViewById<Button>(R.id.btn_autojs).setOnClickListener {
            try {
                startActivity(Intent(this, org.autojs.autojs.ui.main.MainActivity::class.java))
            } catch (e: Exception) {
                toast("运行引擎控制台打开失败: ${e.message}")
            }
        }

        findViewById<Button>(R.id.btn_logout).setOnClickListener {
            org.autojs.autodroid.net.AppPresence.logout()
            toast("已退出登录")
            startActivity(Intent(this, LoginActivity::class.java))
            finishAffinity()
        }

        refreshAccessibilityState(accessibilityText)
        refreshBatteryState(findViewById(R.id.btn_battery))
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityState(findViewById(R.id.text_accessibility))
        refreshBatteryState(findViewById(R.id.btn_battery))
    }

    /** 无障碍三态：未开启 / 已开启但未运行（ColorOS 常见）/ 已开启并运行。 */
    private fun refreshAccessibilityState(textView: TextView) {
        val tool = org.autojs.autojs.core.accessibility.AccessibilityTool(this)
        val hasService = runCatching { tool.hasService() }.getOrDefault(false)
        val running = runCatching { tool.isRunning() }.getOrDefault(false)
        textView.text = when {
            running ->
                "无障碍服务：已开启并运行 ✓\n（提示：设备端执行期间不要用电脑上的平台页面驱动本机，uiautomator2 会抢占无障碍）"
            hasService ->
                "无障碍服务：已开启但未运行 ✗ —— 到系统设置里关闭再打开本应用的无障碍开关（部分机型需重启设备）"
            else ->
                "无障碍服务：未开启 ✗ —— UI 自动化执行必需，点击下方按钮开启"
        }
    }

    /** 电池白名单状态：已加入 / 未加入。 */
    private fun refreshBatteryState(button: Button) {
        button.text = if (isIgnoringBatteryOptimizations()) {
            "已在电池白名单中 ✓（后台更稳定）"
        } else {
            "加入电池优化白名单（防后台被杀）"
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean = runCatching {
        getSystemService(android.os.PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(packageName) == true
    }.getOrDefault(false)

    /** 申请加入电池优化白名单；部分 ROM（ColorOS 等）不支持直接弹窗，回退到应用详情页并给出指引。 */
    private fun requestBatteryWhitelist() {
        if (isIgnoringBatteryOptimizations()) {
            toast("已在电池白名单中，无需重复操作")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
            return
        } catch (_: Exception) {
        }
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
            toast("当前系统不支持直接申请，请在「电池 / 耗电管理」中选择「允许后台运行 / 无限制」")
        } catch (_: Exception) {
            toast("请手动到 系统设置 → 电池 中允许本应用后台运行")
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
