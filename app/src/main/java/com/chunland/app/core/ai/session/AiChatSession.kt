package com.chunland.app.core.ai.session

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.chunland.app.core.ai.AiContext
import com.chunland.app.core.ai.domain.AgentContentPart
import com.chunland.app.core.ai.domain.AgentMessage
import com.chunland.app.core.ai.domain.AgentToolDefinition
import com.chunland.app.core.ai.domain.MediaRef
import com.chunland.app.core.ai.loop.AgentLoop
import com.chunland.app.core.ai.loop.AgentLoopEnd
import com.chunland.app.core.ai.loop.AgentLoopEvent
import com.chunland.app.core.ai.loop.AgentMutationIntent
import com.chunland.app.core.ai.loop.MutationConfirming
import com.chunland.app.core.ai.prompt.AiPrompts
import com.chunland.app.core.ai.provider.ProviderConfigStore
import com.chunland.app.core.ai.storage.MessageRepo
import com.chunland.app.core.ai.storage.SessionRepo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 一条会话（对齐 iOS AIChatSession.swift）。
 *
 * 连接 [AgentLoop]（内核）与视图的桥。视图只读它的状态、只调它的方法，
 * 不知道循环、provider、存储的存在。
 *
 * **每个 contextKey 一个实例**（见 [AiSessionRegistry]）—— 这是对旧实现
 * 「一个全局 store + 反复改用途」的替换：进店的会话和购物车的会话是两个对象，
 * 互不知道对方存在，也就没有状态残留可言。
 */
class AiChatSession(
    val context: AiContext,
    private val loop: AgentLoop,
    private val sessions: SessionRepo,
    private val messagesRepo: MessageRepo,
    private val config: ProviderConfigStore,
    private val scope: CoroutineScope,
    private val ownerUserId: () -> String?,
    private val profileFragment: suspend () -> String? = { null },
) : MutationConfirming {

    companion object {
        private const val TAG = "AiChatSession"

        /** 同 contextKey 的会话多久内可续聊 —— 「问一半收起再打开」上下文不丢 */
        const val RESUME_WINDOW_MS = 24 * 60 * 60 * 1000L
    }

    // MARK: - 对外状态

    val messages: SnapshotStateList<ChatDisplayMessage> = mutableStateListOf()

    /** 正在生成（输入栏禁用、显示停止按钮） */
    var isResponding by mutableStateOf(false)
        private set

    /** 待用户确认的一批变更（HITL）。非 null 时 UI 弹确认框 */
    var pendingConfirmation by mutableStateOf<List<AgentMutationIntent>?>(null)
        private set

    /** 当前会话在库里的 id */
    var sessionId by mutableStateOf<String?>(null)
        private set

    /**
     * 欢迎语。**是 View 层装饰，绝不作为 assistant 消息进历史** ——
     * 塞进历史会被模型当成「自己说过的话」照抄，导致复读。
     */
    val welcomeText: String get() = context.welcome ?: "你好！有什么可以帮你？"

    private var runJob: Job? = null
    private var confirmSignal: CompletableDeferred<Boolean>? = null

    // MARK: - 生命周期

    /**
     * 打开会话：命中同 contextKey 的近期会话就续聊，否则新建。
     *
     * 默认续聊窗口 24h —— 「问一半收起再打开」上下文不丢，隔天算新话题。
     * 传 null = 不限时间（助手 tab 的主对话用它：那是「你正在进行的对话」，
     * 每次冷启动都新建会在历史里堆一串没说过话的空会话）。
     */
    suspend fun open(resumeWithinMs: Long? = RESUME_WINDOW_MS) {
        if (sessionId != null) return
        val owner = ownerUserId()

        runCatching {
            val key = context.contextKey
            if (key != null) {
                val existing = sessions.recent(owner, key, resumeWithinMs)
                if (existing != null) {
                    sessionId = existing.id
                    val history = messagesRepo.load(existing.id)
                    loop.setHistory(history)
                    messages.clear()
                    messages += history.mapNotNull(::displayFrom)
                    Log.i(TAG, "续聊 key=$key messages=${history.size}")
                    return
                }
            }
            val record = sessions.create(owner, context.title, key)
            sessionId = record.id
        }.onFailure { Log.e(TAG, "打开会话失败", it) }
    }

    /**
     * 装载一条指定的历史会话（抽屉里点开某条时用）。
     *
     * 与 [open] 的区别：那个是「按 contextKey 找或建」，这个是「就要这一条」。
     * 装载前先把当前会话存好 —— 用户从 A 切到 B 再切回 A，A 的内容不能丢。
     */
    suspend fun load(targetId: String) {
        if (targetId == sessionId) return
        stop()
        persist()

        runCatching {
            val history = messagesRepo.load(targetId)
            sessionId = targetId
            loop.setHistory(history)
            messages.clear()
            messages += history.mapNotNull(::displayFrom)
            Log.i(TAG, "装载历史会话 messages=${history.size}")
        }.onFailure { Log.e(TAG, "装载历史会话失败", it) }
    }

    /** 关闭：没聊过的空会话直接删掉，不让抽屉堆一次性死会话 */
    suspend fun close() {
        stop()
        val id = sessionId ?: return
        if (messages.isEmpty()) {
            runCatching { sessions.delete(id) }
            Log.i(TAG, "删除空会话")
        }
    }

    /**
     * 「新对话」= 归档语义：旧会话摘掉续聊键留在抽屉里当历史，
     * 新会话独占这个 key。**不删旧的** —— 用户可能还想翻回去看。
     */
    suspend fun restart() {
        stop()
        sessionId?.let { id ->
            runCatching {
                if (messages.isEmpty()) sessions.delete(id) else sessions.detachContextKey(id)
            }
        }
        sessionId = null
        messages.clear()
        loop.reset()
        open()
    }

    // MARK: - 发送

    fun send(text: String, media: List<MediaRef> = emptyList()) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && media.isEmpty()) return
        if (isResponding) return

        val userDisplay = ChatDisplayMessage(ChatDisplayMessage.Role.USER, media)
        userDisplay.appendText(trimmed)
        messages += userDisplay

        val assistantDisplay = ChatDisplayMessage(ChatDisplayMessage.Role.ASSISTANT, isStreaming = true)
        messages += assistantDisplay

        isResponding = true
        runJob = scope.launch { run(trimmed, media, assistantDisplay) }
    }

    /**
     * 停止生成。
     *
     * 已经产出的内容保留 —— 用户点停止是「够了」，不是「撤销」。
     */
    fun stop() {
        runJob?.cancel()
        runJob = null
        // 卡在确认框里时点停止：当作取消处理，否则循环会一直等下去
        resumeConfirmation(false)
    }

    private suspend fun run(text: String, media: List<MediaRef>, display: ChatDisplayMessage) {
        try {
            val id = sessionId
            if (id == null) {
                display.error = "会话未就绪，请重试。"
                return
            }

            val binding = runCatching { config.binding(id) }.getOrNull()
            val entry = config.resolveEntry(binding)
            if (entry == null) {
                display.error = "尚未配置可用的 AI 模型，请到「AI 配置」选择。"
                return
            }

            // system prompt 发送期现拼：prompt 迭代要能即时触达存量会话，
            // 而且工具集是当前身份的函数 —— 冻进历史的那份描述的是当时的身份
            val profile = runCatching { profileFragment() }.getOrNull()
            val systemPrompt = AiPrompts.system(context.seedNote, profile)

            val parts = buildList {
                if (text.isNotEmpty()) add(AgentContentPart.Text(text))
                media.forEach { add(AgentContentPart.Image(it)) }
            }

            loop.run(
                userMessage = AgentMessage(AgentMessage.Role.USER, parts),
                binding = binding,
                systemPrompt = systemPrompt,
                contextWindow = entry.contextWindow,
            ).collect { event -> apply(event, display) }

            persist()
        } finally {
            display.isStreaming = false
            display.closeDanglingTools()
            isResponding = false
            runJob = null
        }
    }

    // MARK: - 事件 → UI

    private fun apply(event: AgentLoopEvent, display: ChatDisplayMessage) {
        when (event) {
            is AgentLoopEvent.TextDelta -> display.appendText(event.text)
            is AgentLoopEvent.ThinkingDelta -> display.appendText(event.text, thinking = true)
            is AgentLoopEvent.ToolStarted -> display.addTool(event.id, event.name, event.title)
            is AgentLoopEvent.ToolFinished -> display.finishTool(event.id, event.isError, null)
            // 降级必须让用户看见 —— 否则「今天回答风格怎么变了」无从解释
            is AgentLoopEvent.Fallback -> appendSystemNote(event.record.userText)
            AgentLoopEvent.Compacted -> appendSystemNote("较早的对话已折叠以节省上下文。")
            is AgentLoopEvent.Finished -> applyEnd(event.end, display)
            is AgentLoopEvent.TurnStarted, is AgentLoopEvent.Usage -> Unit
        }
    }

    private fun applyEnd(end: AgentLoopEnd, display: ChatDisplayMessage) {
        display.isResumable = end.isResumable
        display.error = when (end) {
            AgentLoopEnd.Completed -> null
            is AgentLoopEnd.TurnLimit -> AiPrompts.turnLimitReached(end.limit)
            AgentLoopEnd.ContextExhausted -> "这轮对话已经很长了，新开一个对话可以继续。"
            AgentLoopEnd.Truncated -> "回复被截断了，可以让我接着说。"
            AgentLoopEnd.Refused -> "这个请求我没法回答，换个说法试试，或者到配置里换个模型。"
            // 用户主动停的，不算错误，不显示红字
            AgentLoopEnd.Cancelled -> null
            is AgentLoopEnd.Failed -> end.message
        }
    }

    private fun appendSystemNote(text: String) {
        val note = ChatDisplayMessage(ChatDisplayMessage.Role.SYSTEM)
        note.appendText(text)
        // 插在正在生成的助手消息之前，时间顺序才对
        val lastIndex = messages.lastIndex
        if (lastIndex >= 0 && messages[lastIndex].isStreaming) {
            messages.add(lastIndex, note)
        } else {
            messages += note
        }
    }

    // MARK: - 落库

    private suspend fun persist() {
        val id = sessionId ?: return
        runCatching {
            val history = loop.currentHistory()
            // 只写还没落库的部分 —— 已有 dbId 的说明写过了
            val pending = history.filter { it.dbId == null }
            if (pending.isEmpty()) return

            val stored = messagesRepo.append(id, pending)
            val storedIterator = stored.iterator()
            loop.setHistory(history.map { if (it.dbId == null && storedIterator.hasNext()) storedIterator.next() else it })

            // 首条用户消息发出后用它派生标题
            val firstUser = messages.firstOrNull { it.role == ChatDisplayMessage.Role.USER }
            if (firstUser != null && (context.contextKey == null || context.title == SessionRepo.UNTITLED)) {
                val title = firstUser.plainText.take(20)
                if (title.isNotEmpty()) runCatching { sessions.rename(id, title) }
            }
        }.onFailure { Log.e(TAG, "落库失败", it) }
    }

    // MARK: - 展示模型转换

    /**
     * 历史消息 → 展示模型。
     *
     * 工具调用与结果在 domain 里是两条消息，在 UI 上要合成一个块 ——
     * 所以先建块（assistant 的 ToolUse），结果由后续补状态。
     */
    private fun displayFrom(message: AgentMessage): ChatDisplayMessage? {
        val role = if (message.role == AgentMessage.Role.USER) {
            ChatDisplayMessage.Role.USER
        } else {
            ChatDisplayMessage.Role.ASSISTANT
        }
        val display = ChatDisplayMessage(role)

        message.parts.forEach { part ->
            when (part) {
                is AgentContentPart.Text -> {
                    // 空响应提醒是内部注入的，不给用户看
                    if (!part.text.startsWith("<系统提醒>")) display.appendText(part.text)
                }
                is AgentContentPart.ToolUse ->
                    display.addTool(part.id, part.name, part.input.string(AgentToolDefinition.TOOL_TITLE_KEY))
                is AgentContentPart.ToolResult -> Unit
                is AgentContentPart.Image -> display.media += part.media
            }
        }
        message.reasoning?.takeIf { it.isNotEmpty() }?.let { display.appendText(it, thinking = true) }
        return if (display.isEmpty) null else display
    }

    // MARK: - HITL 确认

    /**
     * 一次确认一批变更。
     *
     * 挂起循环直到用户点了按钮 —— 单槽位（同一时刻只有一个确认框），
     * 这也是为什么变更类工具必须按序执行。
     */
    override suspend fun confirm(batch: List<AgentMutationIntent>): Boolean {
        if (batch.isEmpty()) return true
        val signal = CompletableDeferred<Boolean>()
        confirmSignal = signal
        pendingConfirmation = batch
        return signal.await()
    }

    /** UI 点了确认 / 取消 */
    fun resolveConfirmation(approved: Boolean) = resumeConfirmation(approved)

    private fun resumeConfirmation(approved: Boolean) {
        val signal = confirmSignal ?: return
        confirmSignal = null
        pendingConfirmation = null
        signal.complete(approved)
    }
}
