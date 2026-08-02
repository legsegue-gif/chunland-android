package com.chunland.app.core.ai

import android.content.Context
import androidx.core.content.edit

/**
 * AI 配置本地存储（对齐 iOS 实际行为：baseUrl/model/apiKey 全存端上，
 * `/ai-config` 服务端路由 iOS 也未消费）。**apiKey 只存本机、绝不发 chunland server**。
 * 与 TokenStore 同为 SharedPreferences —— 安全等级一致，不单独引入加密库。
 */
class AiSettings(context: Context) {

    private val prefs = context.getSharedPreferences("chunland_ai", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "") ?: ""
        set(value) = prefs.edit { putString(KEY_BASE_URL, value) }

    var model: String
        get() = prefs.getString(KEY_MODEL, "") ?: ""
        set(value) = prefs.edit { putString(KEY_MODEL, value) }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_API_KEY, value) }

    /** AI 来源：true = 系统提供（本机 proxy，零配置）；false = 自定义 endpoint（对齐 iOS ai_use_system） */
    var useSystem: Boolean
        get() = prefs.getBoolean(KEY_USE_SYSTEM, false)
        set(value) = prefs.edit { putBoolean(KEY_USE_SYSTEM, value) }

    /** 自定义三字段是否齐全（不含来源判断，供配置页表单用） */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    /** 系统来源是否生效（勾了系统且模块已接入；模块不可用时自动回落自定义判断） */
    val systemActive: Boolean
        get() = useSystem && SystemAiProvider.isIntegrated

    /** 当前来源下能否发起对话（对齐 iOS：选系统即视为已配置，就绪与否由运行时状态解释） */
    val isUsable: Boolean
        get() = systemActive || isConfigured

    private companion object {
        const val KEY_BASE_URL = "ai.baseUrl"
        const val KEY_MODEL = "ai.model"
        const val KEY_API_KEY = "ai.apiKey"
        const val KEY_USE_SYSTEM = "ai.useSystem"
    }
}
