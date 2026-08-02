package com.chunland.app.core

import android.content.Context
import androidx.core.content.edit

/**
 * 店铺列表定位锚点（对齐 iOS MerchantStore.anchor）：用户手选的地点，可为省/市/区县任一级
 * region code。SharedPreferences 持久化跨启动生效；**不随登出清** —— 位置是设备语境不是账号数据。
 * null = 未手选（回退默认地址区县；再无则全国不排序）。
 */
class StoreAnchorStore(context: Context) {

    private val prefs = context.getSharedPreferences("chunland_store_anchor", Context.MODE_PRIVATE)

    var code: String?
        get() = prefs.getString(KEY_CODE, null)
        private set(value) = prefs.edit { putString(KEY_CODE, value) }

    var name: String?
        get() = prefs.getString(KEY_NAME, null)
        private set(value) = prefs.edit { putString(KEY_NAME, value) }

    fun set(code: String, name: String) {
        this.code = code
        this.name = name
    }

    fun clear() {
        prefs.edit { remove(KEY_CODE); remove(KEY_NAME) }
    }

    private companion object {
        const val KEY_CODE = "anchor.code"
        const val KEY_NAME = "anchor.name"
    }
}
