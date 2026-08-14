package com.chunland.app.core.ai.provider

import android.content.Context
import androidx.core.content.edit

/**
 * 来源凭证存储（对齐 iOS ProviderCredentials.swift）。
 *
 * ⚠️ 红线：**API Key 只存本地、绝不进 SQLite、绝不发往本项目服务端。**
 *
 * 库里只有 instance id，密钥按 id 存。删除来源时要连带删密钥 ——
 * 否则换个同 id 的新来源会读到上一个的密钥（低概率但后果严重）。
 *
 * 与登录 token 各存各的：那边管登录态（登出要清），这边管第三方 AI 凭证
 * （登出不该清 —— 用户自己配的密钥与账号无关）。
 */
class ProviderCredentials(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("chunland_ai_credentials", Context.MODE_PRIVATE)

    private fun key(instanceId: String) = "apikey:$instanceId"

    fun apiKey(instanceId: String): String? =
        prefs.getString(key(instanceId), null)?.takeIf { it.isNotBlank() }

    fun setApiKey(instanceId: String, value: String?) {
        if (value.isNullOrBlank()) {
            deleteApiKey(instanceId)
            return
        }
        prefs.edit { putString(key(instanceId), value) }
    }

    fun deleteApiKey(instanceId: String) {
        prefs.edit { remove(key(instanceId)) }
    }

    /** 删除来源时调用 —— 库里的行走 CASCADE，密钥要手动清 */
    fun purge(instanceIds: List<String>) {
        prefs.edit { instanceIds.forEach { remove(key(it)) } }
    }
}
