package com.chunland.app.core.pay

import android.app.Activity

/**
 * 支付宝 SDK 桥接（对齐 iOS AlipayBridge/AlipayBridgeManager 范式）：
 * 本文件不 import 支付 SDK —— 实现类在可选模块里。未注册时 Manager 保持空态，
 * 调用方走「暂不可用」降级，编译不破。
 *
 * ⚠️ success 仅表示端内支付返回成功，真正订单状态以服务端 notify 为准 ——
 * 调用方拿到回调只用于触发 reload。
 */
interface AlipayPayBridge {
    /** 阻塞式支付（实现内切 IO 线程）。返回 (success, resultStatus)：9000=成功 6001=取消 */
    suspend fun pay(activity: Activity, orderStr: String): Pair<Boolean, String?>
}

object PayBridgeManager {
    private var bridge: AlipayPayBridge? = null

    val isRegistered: Boolean get() = bridge != null

    fun register(b: AlipayPayBridge) {
        bridge = b
    }

    /** 返回 null 表示桥接未注册（未接入支付 SDK 时的常态） */
    suspend fun pay(activity: Activity, orderStr: String): Pair<Boolean, String?>? =
        bridge?.pay(activity, orderStr)
}
