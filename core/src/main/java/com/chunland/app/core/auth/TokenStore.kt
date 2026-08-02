package com.chunland.app.core.auth

import android.content.Context

/**
 * token 与登录快照的本地存储（承担 iOS Keychain 的角色）。
 * 先用 app 私有 SharedPreferences（拦截器需要同步读，manifest 已关 allowBackup）；
 * 对外分发前可换 Android Keystore 加密加固。
 */
class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS, null)
        set(value) = put(KEY_ACCESS, value)

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)
        set(value) = put(KEY_REFRESH, value)

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = put(KEY_USER_ID, value)

    var roles: List<String>
        get() = prefs.getString(KEY_ROLES, null)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = put(KEY_ROLES, value.joinToString(",").ifEmpty { null })

    var activeIdentity: String
        get() = prefs.getString(KEY_ACTIVE_IDENTITY, null) ?: "consumer"
        set(value) = put(KEY_ACTIVE_IDENTITY, value)

    fun clear() = prefs.edit().clear().apply()

    private fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_ROLES = "user_roles"
        const val KEY_ACTIVE_IDENTITY = "active_identity"
    }
}
