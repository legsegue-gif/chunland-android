package com.chunland.app.core.ai.provider

import android.content.Context
import com.chunland.app.core.security.KeystoreSecretBox
import com.chunland.app.core.security.SealedStore
import com.chunland.app.core.security.SecretBox
import com.chunland.app.core.security.SharedPrefsStore

/**
 * 来源凭证存储（对齐 iOS ProviderCredentials.swift）。
 *
 * ⚠️ 红线：**API Key 只存本地、绝不进 SQLite、绝不发往本项目服务端。**
 * 落盘经 Android Keystore 加密（见 [SealedStore]），对应 iOS 侧的 Keychain。
 *
 * 库里只有 instance id，密钥按 id 存。删除来源时要连带删密钥 ——
 * 否则换个同 id 的新来源会读到上一个的密钥（低概率但后果严重）。
 *
 * 与登录 token 各存各的：那边管登录态（登出要清），这边管第三方 AI 凭证
 * （登出不该清 —— 用户自己配的密钥与账号无关）。Keystore 别名也分开，
 * 与 iOS 那边两个 Keychain service 分开是同一考虑。
 */
class ProviderCredentials(
    context: Context,
    box: SecretBox = KeystoreSecretBox("chunland_ai_credentials_v1"),
) {

    private val prefs = context.applicationContext
        .getSharedPreferences("chunland_ai_credentials", Context.MODE_PRIVATE)
    private val sealed = SealedStore(box, SharedPrefsStore(prefs))

    init {
        // 这份 prefs 每一条都是密钥，全扫。见 SealedStore.sweep 的注释：
        // 懒清理会让某些条目的明文一直留在磁盘上。
        sealed.sweepAll()
    }

    private fun key(instanceId: String) = "apikey:$instanceId"

    fun apiKey(instanceId: String): String? =
        sealed.get(key(instanceId))?.takeIf { it.isNotBlank() }

    fun setApiKey(instanceId: String, value: String?) {
        if (value.isNullOrBlank()) {
            deleteApiKey(instanceId)
            return
        }
        sealed.set(key(instanceId), value)
    }

    fun deleteApiKey(instanceId: String) {
        sealed.set(key(instanceId), null)
    }

    /** 删除来源时调用 —— 库里的行走 CASCADE，密钥要手动清 */
    fun purge(instanceIds: List<String>) {
        instanceIds.forEach { sealed.set(key(it), null) }
    }
}
