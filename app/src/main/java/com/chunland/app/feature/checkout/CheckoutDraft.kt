package com.chunland.app.feature.checkout

/**
 * 购物车 → 结算页的进程内交接（勾选结算，对齐 iOS CheckoutView 只结算已选商品）。
 * null = 整车结算。quote 与下单都带同一份 codes，服务端按购物车行过滤。
 */
object CheckoutDraft {
    var productCodes: List<String>? = null
}
