package org.autojs.autodroid.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import org.autojs.autodroid.data.Session

/**
 * AutoDroid 启动路由：
 * 未登录 → 登录页；已登录 → 用例列表。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        Session.init(this)
        val target = if (Session.token.isNullOrBlank()) {
            LoginActivity::class.java
        } else {
            CaseListActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }
}
