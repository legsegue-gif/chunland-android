package com.chunland.app.data.model

import kotlinx.serialization.Serializable

/** 下单时随订单冻结的地址快照（不依赖地址簿） */
@Serializable
data class DeliveryAddress(
    val name: String,
    val phone: String,
    val address: String,
    val note: String? = null,
    /** 接单匹配 + 距离定价用的收货区县 code（可空，兼容旧地址） */
    val areaCode: String? = null,
)

/** 服务端地址簿条目 */
@Serializable
data class Address(
    val id: Int,
    val name: String,
    val phone: String,
    val address: String,
    val note: String? = null,
    val isDefault: Boolean = false,
    val provinceCode: String? = null,
    val cityCode: String? = null,
    val areaCode: String? = null,
    val detail: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
) {
    fun snapshot(): DeliveryAddress =
        DeliveryAddress(name = name, phone = phone, address = address, note = note, areaCode = areaCode)
}

/** PATCH /addresses/{id}（字段可选，只发要改的） */
@Serializable
data class UpdateAddressRequest(
    val isDefault: Boolean? = null,
)

@Serializable
data class CreateAddressRequest(
    val name: String,
    val phone: String,
    val address: String,
    val note: String? = null,
    val isDefault: Boolean = false,
    val provinceCode: String? = null,
    val cityCode: String? = null,
    val areaCode: String? = null,
    val detail: String? = null,
)

/** 行政区划字典项。level: 1省 2市 3区县 4街道；区县级 code = 距离定价/接单匹配键 */
@Serializable
data class Region(
    val code: String,
    val name: String,
    val level: Int = 1,
)
