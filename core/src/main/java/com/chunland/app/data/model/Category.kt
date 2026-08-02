package com.chunland.app.data.model

import kotlinx.serialization.Serializable

/** 分类树节点（GET /categories 返回 level 1 根 + 嵌套 children） */
@Serializable
data class Category(
    val code: String,
    val name: String,
    val englishName: String? = null,
    val url: String? = null,
    val level: Int = 1,
    val sequence: Int = 0,
    val parentCode: String? = null,
    val children: List<Category> = emptyList(),
)
