package com.chunland.app.data.model

import kotlinx.serialization.Serializable

// GET /feed —— 内容流（公开，keyset 分页 cursor 原样回传）。
// source 只是不透明标识（端上不解释其含义），频道字段统一用 channelId。

@Serializable
data class FeedMedia(
    val url: String,          // 已是完整代理 URL
    val kind: String = "photo",
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class FeedLink(
    val url: String,
    val label: String? = null,
)

/** 卡片附加数据：内容源 {links,coupons} / 商家动态 {productCode,merchantId,price} */
@Serializable
data class FeedMeta(
    val links: List<FeedLink>? = null,
    val coupons: List<String>? = null,
    val productCode: String? = null,
    val merchantId: Int? = null,
    val price: Double? = null,
)

@Serializable
data class FeedItem(
    val id: Long,
    val source: String = "",
    val kind: String = "text",
    val authorName: String? = null,
    val authorHandle: String? = null,
    val text: String? = null,
    val media: List<FeedMedia> = emptyList(),
    val meta: FeedMeta? = null,
    val channelId: Long? = null,
    val publishedAt: String = "",
)

@Serializable
data class FeedPage(
    val items: List<FeedItem> = emptyList(),
    val nextCursor: String? = null,
)

// ---- 关注/收藏（多态：channel=频道 id 文本 / merchant=商家 id 文本 / product=商品 code）----
// product = 商品收藏：只进「收藏与关注」管理页，不进 following 流（与服务端约定一致）。

@Serializable
data class FeedFollow(
    val targetType: String,
    val targetKey: String,
)

@Serializable
data class FollowsResponse(
    val follows: List<FeedFollow> = emptyList(),
)

@Serializable
data class FollowRequest(
    val targetType: String,
    val targetKey: String,
)

/** 管理页条目：对象失效（下架/停用）时 name 可能为 null 或 available=false —— 行仍显示可移除 */
@Serializable
data class FollowDetailItem(
    val targetType: String,
    val targetKey: String,
    val name: String? = null,
    val handle: String? = null,
    val thumbnail: String? = null,
    val price: Double? = null,
    val available: Boolean? = null,
)

@Serializable
data class FollowDetailList(
    val items: List<FollowDetailItem> = emptyList(),
)

// ---- 互动埋点（POST /feed/events，可选认证，匿名也收；失败静默不影响浏览）----

@Serializable
data class FeedEventPayload(
    val feedItemId: Long,
    val eventType: String,   // impression | click | dwell
    val dwellMs: Int? = null,
)

@Serializable
data class FeedEventsRequest(
    val events: List<FeedEventPayload>,
)
