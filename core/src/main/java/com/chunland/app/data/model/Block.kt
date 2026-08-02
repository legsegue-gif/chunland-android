package com.chunland.app.data.model

import kotlinx.serialization.Serializable

// /blocks —— 用户拉黑（1.2 ③）。生效面在服务端：会话冻结 + 对方无法再接我的订单。

@Serializable
data class BlockedUser(
    val userId: Int,
    val displayName: String = "",
    val createdAt: String? = null,
)

@Serializable
data class BlockedUserList(
    val items: List<BlockedUser> = emptyList(),
)

@Serializable
data class BlockRequest(
    val blockedUserId: Int,
)
