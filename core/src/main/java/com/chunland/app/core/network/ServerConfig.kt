package com.chunland.app.core.network

import android.content.Context
import androidx.core.content.edit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * API 基址的真相源（对齐 iOS `AppSettings.serverBaseURL`）。
 *
 * Debug 允许覆盖，存本机 SharedPreferences；Release 恒返回编译默认、**无视任何遗留 override**
 * —— 同一台机器上 Debug↔Release 来回装不会互相污染，正式用户也拿不到切后端的入口。
 * 这条规则两端客户端保持一致。
 *
 * :core 读不到 :app 的 BuildConfig（也不该感知构建配置），故默认值与是否允许覆盖
 * 都由 :app 注入 —— 沿用 [com.chunland.app.core.CoreGraph] 的既有单向注入约定。
 */
class ServerConfig(
    context: Context,
    /** 编译期默认基址（`BuildConfig.API_BASE_URL`），含 `/api/v1`，末尾无斜杠。 */
    val defaultBaseUrl: String,
    /** 是否允许覆盖 —— 由 :app 传 `BuildConfig.DEBUG`，等价 iOS 的 `#if DEBUG`。 */
    val canOverride: Boolean,
) {
    private val prefs = context.getSharedPreferences("chunland_server", Context.MODE_PRIVATE)

    /** 当前生效基址。末尾统一不带斜杠，拼接方自己加。 */
    val baseUrl: String
        get() = if (canOverride) (override ?: defaultBaseUrl) else defaultBaseUrl

    /**
     * 用户设置的覆盖值；null = 未覆盖（走编译默认）。
     * setter 内部过 [normalize]，存进去的一定是可解析的地址。
     */
    var override: String?
        get() = prefs.getString(KEY_OVERRIDE, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit {
            val v = value?.let { normalize(it) }
            if (v == null) remove(KEY_OVERRIDE) else putString(KEY_OVERRIDE, v)
        }

    /** 清除覆盖，回到随构建走的默认（配置页「恢复默认」用，对齐 iOS `resetServerBaseURL`）。 */
    fun resetToDefault() {
        override = null
    }

    companion object {
        /**
         * 校验并归一用户输入：必须是可解析的 http(s) 绝对地址，非法返回 null。
         *
         * iOS 侧不做校验（`ServerConfigSheet` 直接存 trim 后的串）。这里刻意收紧 ——
         * Android 的基址是拦截器出站时现取的，存进一个解析不了的串只会让请求静默打到
         * 哨兵域名，用户会以为「保存没生效」。
         */
        fun normalize(input: String): String? {
            val trimmed = input.trim().trimEnd('/')
            // toHttpUrlOrNull 只认 http/https，非法 scheme 与残缺 host 都返回 null
            return if (trimmed.toHttpUrlOrNull() != null) trimmed else null
        }

        private const val KEY_OVERRIDE = "base_url_override"
    }
}
