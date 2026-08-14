package com.chunland.app.core.ai

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.chunland.app.core.ai.loop.AgentToolExecuting
import com.chunland.app.core.ai.provider.ProviderConfigStore
import com.chunland.app.core.ai.provider.ProviderCredentials
import com.chunland.app.core.ai.session.AiChatSession
import com.chunland.app.core.ai.session.AiSessionRegistry
import com.chunland.app.core.ai.storage.AiDatabase
import com.chunland.app.core.ai.storage.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * AI 子系统的装配点（对齐 iOS AIRuntime.swift）。
 *
 * 把「谁依赖谁」集中在一处：库 → 凭证 → 配置 → 会话注册表。
 * 之前这些散在各处，导致同一份配置被多处各读一遍。
 *
 * 进程级单份：AI 的存储与配置是进程级的（一个库文件、一份来源配置），
 * 每个页面各建一份只会打架。会话实例本身是多个 —— 那由注册表管。
 */
class AiRuntime(
    context: Context,
    private val scope: CoroutineScope,
    executorFactory: (AiContext) -> AgentToolExecuting,
    ownerUserId: () -> String?,
    profileFragment: suspend () -> String?,
) {

    val database = AiDatabase(context)
    val credentials = ProviderCredentials(context)
    val config = ProviderConfigStore(database, credentials)

    /** 图片输入落盘用（内容寻址，字节永不进库、永不进消息） */
    val media = MediaStore(database, AiDatabase.mediaDirectory(context))

    val sessions = AiSessionRegistry(
        db = database,
        config = config,
        credentials = credentials,
        mediaDir = AiDatabase.mediaDirectory(context),
        scope = scope,
        executorFactory = executorFactory,
        ownerUserId = ownerUserId,
        profileFragment = profileFragment,
    )

    /** 是否已完成初始化。UI 据此决定显示加载态还是内容 */
    var isReady by mutableStateOf(false)
        private set

    /** 初始化失败的原因（库打不开等）。非 null 时 AI 整体不可用 */
    var bootstrapError by mutableStateOf<String?>(null)
        private set

    private val bootMutex = Mutex()

    /**
     * 建库 + 加载配置。幂等，可重复调用。
     *
     * App 启动时调一次即可 —— 但每个 AI 入口也会调，因为用户可能从任意
     * 入口第一次进 AI（冷启动直接点 ✨），不能假设 tab 一定先被访问过。
     */
    suspend fun bootstrap() {
        if (isReady) return
        bootMutex.withLock {
            if (isReady) return
            runCatching { config.loadIfNeeded() }
                .onSuccess {
                    isReady = true
                    bootstrapError = null
                    Log.i(TAG, "AI 运行时就绪")
                }
                .onFailure {
                    bootstrapError = it.message ?: it.toString()
                    Log.e(TAG, "AI 运行时初始化失败", it)
                }
        }
    }

    /**
     * 降级链首选那一档的名字（「我的」页的一行摘要用）。
     *
     * 给的是**首选**而不是全链 —— 一行放不下全链，而用户想知道的是「现在用的是哪个」。
     * 全链与各档可用性在配置页里展示。
     */
    suspend fun preferredSourceLabel(): String {
        bootstrap()
        if (!isReady) return "未配置"
        val group = config.defaultGroup() ?: return "未配置"
        val entry = config.usableEntries(group).firstOrNull() ?: return "未配置"
        val instance = config.instance(entry.instanceId) ?: return "未配置"
        return "${instance.label} · ${entry.displayName}"
    }

    /**
     * 关闭一条会话（没聊过的空会话会被删掉，不让抽屉堆一次性死会话）。
     *
     * 走 app 级作用域而不是页面作用域：调用点是页面销毁时，
     * 页面自己的 scope 此刻已经取消，收尾动作会被一并掐掉。
     */
    fun closeSession(session: AiChatSession) {
        scope.launch { session.close() }
    }

    /**
     * 换账号 / 登出。
     *
     * 会话实例必须一并丢弃 —— 库里按属主过滤只挡住了读路径，
     * 内存里那些已经装载了上一个账号消息的实例不会自己消失。
     */
    fun resetForAccountChange() {
        sessions.reset()
        Log.i(TAG, "已重置 AI 会话（账号变更）")
    }

    /**
     * 后台维护：回收没有任何消息引用的媒体文件。
     *
     * 删会话时不立即删文件（同一张图可能被别的会话引用），所以需要这个低频清理。
     * 失败无所谓，下次再扫。
     */
    suspend fun collectGarbage() {
        if (!isReady) return
        val count = runCatching { media.collectGarbage() }.getOrDefault(0)
        if (count > 0) Log.i(TAG, "回收媒体文件 count=$count")
    }

    private companion object {
        const val TAG = "AiRuntime"
    }
}
