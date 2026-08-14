package com.chunland.app.core.ai.session

import com.chunland.app.core.ai.AiContext
import com.chunland.app.core.ai.domain.MediaRef
import com.chunland.app.core.ai.loop.AgentLoop
import com.chunland.app.core.ai.loop.AgentToolExecuting
import com.chunland.app.core.ai.loop.AgentToolPipeline
import com.chunland.app.core.ai.loop.AgentMutationIntent
import com.chunland.app.core.ai.loop.MutationConfirming
import com.chunland.app.core.ai.loop.ToolLoopDetector
import com.chunland.app.core.ai.prompt.AiPrompts
import com.chunland.app.core.ai.provider.LlmError
import com.chunland.app.core.ai.provider.LlmTurn
import com.chunland.app.core.ai.provider.ProviderConfigStore
import com.chunland.app.core.ai.provider.completeText
import com.chunland.app.core.ai.provider.ProviderCredentials
import com.chunland.app.core.ai.provider.ProviderFactory
import com.chunland.app.core.ai.provider.ProviderRouter
import com.chunland.app.core.ai.storage.AiDatabase
import com.chunland.app.core.ai.storage.MediaStore
import com.chunland.app.core.ai.storage.MessageRepo
import com.chunland.app.core.ai.storage.SessionRepo
import java.io.File
import kotlinx.coroutines.CoroutineScope

/**
 * 会话实例注册表（对齐 iOS AISessionRegistry.swift）。
 *
 * **每个 contextKey 一个会话实例，进程内复用。**
 *
 * 这是对旧实现的替换。旧的是「一个全局 store 被反复改用途」，
 * 每一步都在处理由此带来的状态残留。多实例把这类问题整体消掉：
 * 进店的会话和购物车的会话是两个对象，互不知道对方存在。
 */
class AiSessionRegistry(
    private val db: AiDatabase,
    private val config: ProviderConfigStore,
    private val credentials: ProviderCredentials,
    private val mediaDir: File,
    private val scope: CoroutineScope,
    /**
     * 建工具执行器。收整个 [AiContext] 而不只是 scope ——
     * 工具集裁剪要同时用到作用域与页面建议的子集。
     */
    private val executorFactory: (AiContext) -> AgentToolExecuting,
    private val ownerUserId: () -> String?,
    private val profileFragment: suspend () -> String? = { null },
) {

    private val sessions = mutableMapOf<String, AiChatSession>()
    private val sessionRepo = SessionRepo(db)
    private val mediaStore = MediaStore(db, mediaDir)
    private val messageRepo = MessageRepo(db, mediaStore)

    /** 取（或建）该上下文的会话实例 */
    fun session(context: AiContext): AiChatSession {
        val key = context.contextKey ?: context.title
        sessions[key]?.let { return it }

        val executor = executorFactory(context)
        val detector = ToolLoopDetector()
        val factory = ProviderFactory(config, credentials) { ref: MediaRef ->
            // 图片字节只在 wire 编码那一刻读，读完即弃
            runCatching { mediaStore.loadBytes(ref) }.getOrNull()
        }
        val router = ProviderRouter(config, factory)

        // pipeline 需要 confirmer，而 confirmer 就是 session 自己（HITL 单槽位）——
        // 先建 session 再补 pipeline 会绕，所以用一个转发壳打破循环依赖
        val forwarder = ConfirmForwarder()
        val pipeline = AgentToolPipeline(executor, forwarder, detector)
        val loop = AgentLoop(
            router = router,
            pipeline = pipeline,
            executor = executor,
            detector = detector,
            // 压缩摘要走单次协议 —— 与对话共用同一套配置与传输，
            // 但**不带工具**（摘要不该触发工具调用）。
            summarize = { transcript ->
                val entry = config.resolveEntry(null) ?: throw LlmError.NotConfigured
                factory.make(entry).completeText(
                    messages = listOf(LlmTurn.user(AiPrompts.compactionRequest(transcript))),
                    systemPrompt = AiPrompts.compaction,
                    // 摘要要压缩内容，给太多额度反而让它啰嗦
                    maxTokens = minOf(2048, entry.maxOutputTokens),
                )
            },
        )

        val session = AiChatSession(
            context = context,
            loop = loop,
            sessions = sessionRepo,
            messagesRepo = messageRepo,
            config = config,
            scope = scope,
            ownerUserId = ownerUserId,
            profileFragment = profileFragment,
        )
        forwarder.target = session

        sessions[key] = session
        return session
    }

    /** 丢弃某个会话实例（关闭页面且会话为空时） */
    fun discard(contextKey: String) {
        sessions.remove(contextKey)
    }

    /**
     * 换账号 / 登出：全部清掉。
     *
     * 会话是账号数据 —— 同设备换账号绝不能看到别人的对话，
     * 内存里的实例也必须一并丢弃（库里按 owner 过滤只挡住了读路径）。
     */
    fun reset() {
        sessions.values.forEach { it.stop() }
        sessions.clear()
    }
}

/**
 * 确认请求的转发壳。
 *
 * 只为打破「pipeline 需要 confirmer，而 confirmer 是 session，session 又需要
 * 装好 pipeline 的 loop」这个循环。除了转发不做任何事。
 */
private class ConfirmForwarder : MutationConfirming {
    var target: AiChatSession? = null

    override suspend fun confirm(batch: List<AgentMutationIntent>): Boolean =
        target?.confirm(batch) ?: false
}
