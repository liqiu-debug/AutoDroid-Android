package org.autojs.autodroid.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerAddress {
    fun normalize(input: String): String {
        val text = input.trim().trimEnd('/')
        val url = text.toHttpUrlOrNull()
        require(url != null && (text.startsWith("http://", true) || text.startsWith("https://", true)) &&
            url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "请输入有效的平台地址，例如 http://192.168.1.10:8000"
        }
        return url.toString().trimEnd('/').removeSuffix("/api")
    }
}
