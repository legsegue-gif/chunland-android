package com.chunland.app.core

import com.chunland.app.core.network.apiCall
import com.chunland.app.core.network.apiCallUnit
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.api.FeedApi
import com.chunland.app.data.model.FollowRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 关注集（对齐 iOS FollowStore）：Set<"type:key">，乐观更新 + 失败回滚。
 * 登出时由 AppGraph 收到登录态翻转后 reset。
 *
 * refresh 与 toggle 经 Mutex 串行化：登录瞬间「loadIfNeeded（整体替换）」与
 * 「intent retry 的 toggle（乐观增删）」并发时，旧快照的 refresh 响应会把刚落库的
 * 乐观更新覆盖掉（表现为：库已落、UI 却回到未关注）。
 */
class FollowStore(private val api: FeedApi) {

    private val _keys = MutableStateFlow<Set<String>>(emptySet())
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    @Volatile
    private var loaded = false

    private val mutex = Mutex()

    suspend fun loadIfNeeded() {
        mutex.withLock {
            if (!loaded) refreshLocked()
        }
    }

    suspend fun refresh() {
        mutex.withLock { refreshLocked() }
    }

    private suspend fun refreshLocked() {
        runCatching {
            val resp = apiCall { api.follows() }
            _keys.value = resp.follows.map { "${it.targetType}:${it.targetKey}" }.toSet()
            loaded = true
        }
    }

    /** 乐观翻转关注态；失败回滚并返回 toast 文案（null=成功，业务 mutator 约定） */
    suspend fun toggle(type: String, key: String): String? = mutex.withLock {
        val k = "$type:$key"
        val had = k in _keys.value
        _keys.value = if (had) _keys.value - k else _keys.value + k
        try {
            if (had) {
                apiCallUnit { api.unfollow(FollowRequest(type, key)) }
            } else {
                apiCallUnit { api.follow(FollowRequest(type, key)) }
            }
            null
        } catch (e: Exception) {
            _keys.value = if (had) _keys.value + k else _keys.value - k
            e.userMessage
        }
    }

    fun reset() {
        _keys.value = emptySet()
        loaded = false
    }
}
