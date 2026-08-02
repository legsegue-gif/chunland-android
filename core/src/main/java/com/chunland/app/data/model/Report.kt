package com.chunland.app.data.model

import kotlinx.serialization.Serializable

// POST /reports —— 举报（多态：内容/商品/代购人/订单/AI 消息/通用），任意登录用户。

@Serializable
data class CreateReportRequest(
    /** feed_item | product | agent | order | ai_message | general（服务端白名单） */
    val targetType: String,
    /** illegal | fraud | porn | infringement | harassment | other */
    val reasonCode: String,
    /** general 外的类型必填：被举报对象的 key（内容 id / 商品 code / 用户 id / 订单 id） */
    val targetKey: String? = null,
    val detail: String? = null,
    /** AI 消息举报附带的内容快照 */
    val snapshot: String? = null,
)
