package com.chunland.app.ui

/** 展示价格式化（wire 是 Double）。只做显示，一切金额计算在服务端。 */
fun formatPrice(value: Double): String =
    if (value % 1.0 == 0.0) "%.0f".format(value) else value.toString()
