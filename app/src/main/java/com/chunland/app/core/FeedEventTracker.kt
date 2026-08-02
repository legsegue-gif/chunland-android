package com.chunland.app.core

import com.chunland.app.core.network.apiCallUnit
import com.chunland.app.data.api.FeedApi
import com.chunland.app.data.model.FeedEventPayload
import com.chunland.app.data.model.FeedEventsRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 互动埋点（对齐 iOS FeedImpressionTracker 的角色）：曝光进程内去重、
 * 缓冲攒批（满 10 条即发），上报失败静默 —— 埋点绝不影响浏览。
 * 全部 fire-and-forget，UI 调用点无 suspend 负担。
 */
class FeedEventTracker(
    private val api: FeedApi,
    private val scope: CoroutineScope,
) {
    private val seenImpressions = mutableSetOf<Long>()
    private val buffer = mutableListOf<FeedEventPayload>()
    private val mutex = Mutex()

    fun impression(feedItemId: Long) {
        scope.launch {
            mutex.withLock {
                if (!seenImpressions.add(feedItemId)) return@launch
                buffer += FeedEventPayload(feedItemId, "impression")
                if (buffer.size >= FLUSH_THRESHOLD) flushLocked()
            }
        }
    }

    fun click(feedItemId: Long) {
        scope.launch {
            mutex.withLock {
                buffer += FeedEventPayload(feedItemId, "click")
                flushLocked()   // 点击是强信号，立即随批发出
            }
        }
    }

    /**
     * 停留时长（卡片一次可见窗口，ms）。<1s 的滚动扫过不记；>5min 截断（挂后台等异常窗口）。
     * 同卡多次可见记多段，不去重 —— 每段都是真实注意力信号。
     */
    fun dwell(feedItemId: Long, ms: Long) {
        if (ms < MIN_DWELL_MS) return
        val clamped = ms.coerceAtMost(MAX_DWELL_MS).toInt()
        scope.launch {
            mutex.withLock {
                buffer += FeedEventPayload(feedItemId, "dwell", dwellMs = clamped)
                if (buffer.size >= FLUSH_THRESHOLD) flushLocked()
            }
        }
    }

    fun flush() {
        scope.launch { mutex.withLock { flushLocked() } }
    }

    private suspend fun flushLocked() {
        if (buffer.isEmpty()) return
        val batch = buffer.toList()
        buffer.clear()
        runCatching { apiCallUnit { api.reportEvents(FeedEventsRequest(batch)) } }
        // 失败静默：埋点丢了就丢了，不重试不打扰
    }

    private companion object {
        const val FLUSH_THRESHOLD = 10
        const val MIN_DWELL_MS = 1_000L
        const val MAX_DWELL_MS = 300_000L
    }
}
