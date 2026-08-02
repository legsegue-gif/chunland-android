package com.chunland.app.data.model

import kotlinx.serialization.Serializable

/** GET /config/checkout —— 展示用公开配置。金额计算仍一律走服务端 quote。 */
@Serializable
data class CheckoutConfig(
    val platformFeeRate: Double = 0.0,
    val agentFeeRate: Double = 0.0,
    val minOrderAmount: Double = 0.0,
)
