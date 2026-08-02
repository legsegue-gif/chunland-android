package com.chunland.app.feature.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * 两段式降采样解码：先 bounds 探尺寸，再按 inSampleSize 预缩到长边 ≤ maxSide。
 * 直接 decodeByteArray 全尺寸解码是 OOM 温床（12MP 小票 ≈ 48MB、相册 50MP 原图 ≈ 200MB+），
 * 而消费端最多渲染屏幕大小 —— 凭证缩略/预览、会话图片收发链统一走这里。
 */
fun decodeSampledBitmap(bytes: ByteArray, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}
