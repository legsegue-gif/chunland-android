package com.chunland.app.ui

import android.text.format.DateUtils
import java.time.OffsetDateTime

/**
 * ISO8601（带时区，可能含小数秒）→ 本地化相对时间（“3分钟前”，对齐 iOS
 * RelativeDateTimeFormatter 观感）。解析失败回退日期段。
 */
fun relativeTime(iso: String): String {
    val millis = runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
        ?: return iso.take(10)
    return relativeTime(millis)
}

fun relativeTime(epochMillis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        epochMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
