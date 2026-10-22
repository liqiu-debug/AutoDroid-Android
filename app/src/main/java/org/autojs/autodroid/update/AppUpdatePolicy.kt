package org.autojs.autodroid.update

/**
 * 更新判定策略：纯 Kotlin，无 Android 依赖（便于 JVM 单测）。
 *
 * 版本号语义与 org.autojs.autojs.network.entity.VersionInfo.isNewer 一致：
 * 以数字 versionCode 比较，version_name 仅作展示。
 * 远端 version_code 来自平台安装包库（上传时解析 APK 得到的字符串），
 * 可能为空或非法值，此时无法自动比较。
 */
enum class UpdateVerdict {
    /** 远端版本高于本地，可更新 */
    UPDATE_AVAILABLE,

    /** 本地已是最新（或高于远端） */
    UP_TO_DATE,

    /** 远端版本号无法解析，不自动比较 */
    UNKNOWN_VERSION,
}

object AppUpdatePolicy {

    /** 解析 versionCode 字符串：整数优先，兼容 "3821.0" 这类浮点表示；非法返回 null。 */
    fun parseVersionCode(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { return it }
        return text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.toLong()
    }

    fun verdict(remoteVersionCode: String?, localVersionCode: Int): UpdateVerdict {
        val remote = parseVersionCode(remoteVersionCode) ?: return UpdateVerdict.UNKNOWN_VERSION
        return if (remote > localVersionCode.toLong()) {
            UpdateVerdict.UPDATE_AVAILABLE
        } else {
            UpdateVerdict.UP_TO_DATE
        }
    }
}
