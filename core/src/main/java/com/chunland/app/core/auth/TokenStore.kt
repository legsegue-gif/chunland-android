package com.chunland.app.core.auth

import android.content.Context
import com.chunland.app.core.security.KeystoreSecretBox
import com.chunland.app.core.security.SealedStore
import com.chunland.app.core.security.SecretBox
import com.chunland.app.core.security.SharedPrefsStore

/**
 * token 与登录快照的本地存储（承担 iOS Keychain 的角色）。
 *
 * 落在 app 私有 SharedPreferences（拦截器需要同步读，manifest 已关 allowBackup），
 * **两个 token 经 Android Keystore 加密后才落盘**（见 [SealedStore]）。
 *
 * ⚠️ **只有 token 加密**：userId / roles / activeIdentity 不是秘密，
 * 拿到它们既登不了录也越不了权。给非秘密上加密只买到心理安慰，
 * 代价是每次读写多一遍 Keystore —— 而 activeIdentity 是切身份时的热字段。
 */
class TokenStore(
    context: Context,
    box: SecretBox = KeystoreSecretBox("chunland_auth_v1"),
) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val sealed = SealedStore(box, SharedPrefsStore(prefs))

    init {
        // 主动扫掉解不开的残留（加固前的明文 / 已作废密钥的密文）。
        // 只扫这两个 —— 同一个 prefs 里的 userId / roles / activeIdentity 本来就不加密。
        // 不能等到被读：refresh_token 只有 401 才读，明文会一直躺在磁盘上（实测踩过）。
        sealed.sweep(listOf(KEY_ACCESS, KEY_REFRESH))
    }

    var accessToken: String?
        get() = sealed.get(KEY_ACCESS)
        set(value) = sealed.set(KEY_ACCESS, value)

    var refreshToken: String?
        get() = sealed.get(KEY_REFRESH)
        set(value) = sealed.set(KEY_REFRESH, value)

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = put(KEY_USER_ID, value)

    var roles: List<String>
        get() = prefs.getString(KEY_ROLES, null)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = put(KEY_ROLES, value.joinToString(",").ifEmpty { null })

    var activeIdentity: String
        get() = prefs.getString(KEY_ACTIVE_IDENTITY, null) ?: "consumer"
        set(value) = put(KEY_ACTIVE_IDENTITY, value)

    fun clear() {
        prefs.edit().clear().apply()
        // 缓存必须跟着清 —— 否则登出后 accessToken 还读得到旧值
        sealed.forgetAll()
    }

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
