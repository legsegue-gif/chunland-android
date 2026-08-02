package com.chunland.app.core.ai

import com.chunland.app.core.network.userMessage
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AI agent 会话（对齐 iOS AIOrchestrator.runAgentLoop：流式 + 工具循环，最多 8 轮）。
 * AppGraph 级单例：tab 切走消息仍在；登出 reset（会话不跨账号）。
 *
 * ⚠️ 两条网络链永不交叉：对话直连用户自配 endpoint，用**裸 OkHttpClient**（绝不复用
 * AppGraph 的 client —— 那条链挂着 AuthInterceptor，会把 chunland token 泄给外部服务；
 * 反向同理，用户的 apiKey 也绝不发 chunland server）；工具执行走 AiToolRegistry 里
 * AppGraph 的 Retrofit（带 chunland token、不带用户 apiKey）。
 *
 * 会话上下文经构造注入（AiContext，null = tab 主会话）：scoped ✨ 入口（进店等）
 * 每个 contextKey 一个实例（AppGraph.scopedAiStore 缓存复用 = 进程内续聊）。
 * 作用域是结构化硬约束：进店会话的 search_products / get_categories
 * 经 context.scope.merchantId 由代码限定本店，绝不许只写进 prompt 许愿。
 */
class AiChatStore(
    private val settings: AiSettings,
    private val scope: CoroutineScope,
    private val registry: AiToolRegistry,
    /** 当前活跃身份（consumer/agent/merchant），决定会话下发的工具全集 */
    private val activeIdentity: () -> String,
    /** 会话上下文（scoped ✨ 入口注入；null = tab 主会话，全局作用域） */
    private val context: AiContext? = null,
    /** 多会话持久化（null = 不落盘，单测用）；属主经 ownerUserId 隔离 */
    private val conversations: ConversationStore? = null,
    private val ownerUserId: () -> String = { "guest" },
) {

    data class AiMessage(
        val id: Long,
        val role: String,             // user | assistant | tool
        val content: String,
        val reasoning: String? = null,
        /** 生成失败/被打断时置为该气泡的补充说明（View 渲染成小字，不进对话历史） */
        val note: String? = null,
        /** assistant 请求的工具调用（wire 形态原样保存，历史回发必须原样携带） */
        val toolCalls: List<WireToolCall>? = null,
        /** role == "tool"：结果对应的 call id / 工具名 */
        val toolCallId: String? = null,
        val toolName: String? = null,
    )

    private val _messages = MutableStateFlow<List<AiMessage>>(emptyList())
    val messages: StateFlow<List<AiMessage>> = _messages.asStateFlow()

    private val _responding = MutableStateFlow(false)
    val responding: StateFlow<Boolean> = _responding.asStateFlow()

    /** HITL：AI 想执行 mutation 工具时置值，UI 弹确认框；决策走 confirmIntent / cancelIntent */
    private val _pendingIntent = MutableStateFlow<MutationIntent?>(null)
    val pendingIntent: StateFlow<MutationIntent?> = _pendingIntent.asStateFlow()

    /** 当前来源下可用（系统提供 = 选中即视为已配置；自定义 = 三字段齐全），供配置 CTA 判断 */
    val isConfigured: Boolean get() = settings.isUsable

    /** scoped ✨ 会话（进店/订单等页面上下文）。历史抽屉只在 tab 主会话显示（对齐 iOS：
     *  ConversationDrawer 只挂 AIView；scoped 会话恢复旧会话会劫持其 contextKey 续聊槽） */
    val isScoped: Boolean get() = context != null

    /** 欢迎语（View 装饰，绝不进对话历史）；scoped 会话用上下文专属文案 */
    val welcomeText: String get() = context?.welcome ?: DEFAULT_WELCOME

    private val nextId = AtomicLong(1)
    private var job: Job? = null
    private var call: Call? = null
    private var pendingDecision: CompletableDeferred<Boolean>? = null

    /** 当前会话的持久化身份；「新对话」换新 id（旧会话已在盘 = 归档进抽屉） */
    @Volatile
    private var conversationId: String = java.util.UUID.randomUUID().toString()

    /** 当前轮占位 message id —— 取消/失败时兜底收尾用 */
    @Volatile
    private var lastPlaceholderId = -1L

    /** 工具执行的结构化作用域：tab 主会话 GLOBAL；scoped 会话由 AiContext 注入（红线，见类注释） */
    private val runScope: AiToolScope = context?.scope ?: AiToolScope.GLOBAL

    // 流式读 SSE：readTimeout 放宽到 120s（长回答中途可有停顿）
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 发送一轮。返回 null=已受理；非 null=不可发送的原因（对齐仓库 mutator 约定）。 */
    fun send(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return "请输入内容"
        if (!settings.isUsable) return "请先配置 AI API"
        if (_responding.value) return "正在回复中"

        _messages.update { it + AiMessage(nextId.getAndIncrement(), "user", t) }
        _responding.value = true

        job = scope.launch(Dispatchers.IO) {
            try {
                runAgentLoop()
            } finally {
                // 收尾 + 落盘必须在 NonCancellable 内：用户点「停止」= job.cancel()，
                // 普通挂起点会立刻抛 CancellationException → 该轮永不落盘（杀进程即丢）。
                // iOS 的 stop() 只取消内层 task、外层 send 照常 persistCurrent —— 此处对齐该行为。
                withContext(NonCancellable) {
                    // 兜底收尾：空占位（连不上/被立刻停止/取消）给出说明而不是留白
                    val pid = lastPlaceholderId
                    _messages.update { list ->
                        list.map { m ->
                            if (m.id == pid && m.role == "assistant" && m.content.isEmpty() &&
                                m.reasoning.isNullOrEmpty() && m.note == null && m.toolCalls == null
                            ) {
                                m.copy(note = "（未收到回复）")
                            } else m
                        }
                    }
                    persistSnapshot()   // 一轮收尾落盘（含工具轮；失败/中止/停止的 note 也如实存）
                    _responding.value = false
                    call = null
                }
            }
        }
        return null
    }

    /** 停止生成：取消底层请求与整个 loop（含挂起中的确认弹窗），已生成内容保留。 */
    fun stop() {
        pendingDecision?.cancel()
        call?.cancel()
        job?.cancel()
    }

    /** 新对话 / 登出清空（会话不跨账号，对齐 iOS 属主隔离精神）。
     * 归档语义（对齐 iOS）：旧会话已随每轮收尾落盘、留在抽屉；换新 conversationId 独占后续。 */
    fun reset() {
        stop()
        _pendingIntent.value = null
        _messages.value = emptyList()
        conversationId = java.util.UUID.randomUUID().toString()
    }

    // MARK: - 多会话持久化（对齐 iOS SwiftData 语义，见 ConversationStore）

    /** 从抽屉恢复会话（stop 当前生成；nextId 续排避免 id 冲突）。 */
    fun restore(conversation: StoredConversation) {
        stop()
        _pendingIntent.value = null
        conversationId = conversation.id
        _messages.value = conversation.messages.map { s ->
            AiMessage(
                id = nextId.getAndIncrement(),
                role = s.role,
                content = s.content,
                reasoning = s.reasoning,
                note = s.note,
                toolCalls = s.toolCalls,
                toolCallId = s.toolCallId,
                toolName = s.toolName,
            )
        }
    }

    /** scoped ✨ 进程重启续聊：仅当会话还是空白时恢复（不覆盖用户已开始的新对话）。 */
    fun restoreIfEmpty(conversation: StoredConversation) {
        if (_messages.value.isEmpty() && !_responding.value) restore(conversation)
    }

    private suspend fun persistSnapshot() {
        val store = conversations ?: return
        val msgs = _messages.value
        if (msgs.none { it.role == "user" }) return   // 空会话不落盘（= iOS 空 scoped 会话关闭即删）
        store.save(
            StoredConversation(
                id = conversationId,
                ownerUserId = ownerUserId(),
                title = context?.title
                    ?: msgs.firstOrNull { it.role == "user" }?.content?.take(20)
                    ?: "对话",
                contextKey = context?.contextKey,
                updatedAt = System.currentTimeMillis(),
                messages = msgs.map { m ->
                    StoredMessage(
                        role = m.role,
                        content = m.content,
                        reasoning = m.reasoning,
                        note = m.note,
                        toolCalls = m.toolCalls,
                        toolCallId = m.toolCallId,
                        toolName = m.toolName,
                    )
                },
            ),
        )
    }

    // MARK: - HITL 决策入口（UI 确认框调用）

    fun confirmIntent() {
        _pendingIntent.value = null
        pendingDecision?.complete(true)
    }

    fun cancelIntent() {
        _pendingIntent.value = null
        pendingDecision?.complete(false)
    }

    private suspend fun awaitConfirmation(intent: MutationIntent): Boolean {
        val decision = CompletableDeferred<Boolean>()
        pendingDecision = decision
        _pendingIntent.value = intent
        return try {
            decision.await()
        } finally {
            pendingDecision = null
            _pendingIntent.value = null
        }
    }

    // MARK: - Agent loop（对齐 iOS runAgentLoop：finish=tool_calls → 执行工具 → 续轮，最多 8 轮）

    private suspend fun runAgentLoop() {
        var rounds = 0
        while (rounds < MAX_ROUNDS) {
            rounds += 1
            val placeholderId = nextId.getAndIncrement()
            lastPlaceholderId = placeholderId
            _messages.update { it + AiMessage(placeholderId, "assistant", "") }

            when (val outcome = streamOnce(placeholderId)) {
                is StreamOutcome.ToolCalls -> {
                    // 占位转 tool_call 形态（已流出的 content 保留），执行工具后续轮
                    _messages.update { list ->
                        list.map { if (it.id == placeholderId) it.copy(toolCalls = outcome.calls) else it }
                    }
                    val results = executeToolBatch(outcome.calls)
                    outcome.calls.zip(results).forEach { (c, result) ->
                        _messages.update {
                            it + AiMessage(
                                nextId.getAndIncrement(), "tool", result,
                                toolCallId = c.id, toolName = c.function.name,
                            )
                        }
                    }
                }
                StreamOutcome.Finished -> return
                StreamOutcome.Failed -> return   // note 已写进占位气泡
            }
        }
        _messages.update { it + AiMessage(nextId.getAndIncrement(), "assistant", "抱歉，查询轮次过多，请换个问题试试。") }
    }

    // MARK: - 执行工具调用

    /** 同轮多工具执行（对齐 iOS executeToolBatch）：**全只读才并发**（网络并行，只读工具间
     *  无写副作用、也无同轮数据依赖）；含 mutation 则整批按序 —— HITL 确认是单槽位
     *  （pendingDecision）不能并发弹窗，且同轮「先加购后查车」的因果顺序不可乱。
     *  结果恒按原顺序回填（awaitAll 保序），tool 帧与 assistant.tool_calls 对齐。 */
    private suspend fun executeToolBatch(calls: List<WireToolCall>): List<String> {
        val allReadOnly = calls.all {
            registry.spec(it.function.name)?.kind != AiToolName.Kind.MUTATION
        }
        if (!allReadOnly || calls.size <= 1) return calls.map { executeTool(it) }
        return coroutineScope {
            calls.map { c -> async { executeTool(c) } }.awaitAll()
        }
    }

    private suspend fun executeTool(call: WireToolCall): String {
        val spec = registry.spec(call.function.name)
            ?: return "未知工具: ${call.function.name}"
        // 执行侧身份守卫（第二道，与下发侧同一真相源 allowedIdentities）：
        // 会话跨身份留存，模型可能从历史复调旧身份的工具 —— 拒绝并引导切换身份。
        val identity = activeIdentity()
        if (!spec.name.allowedFor(identity)) {
            val need = spec.name.allowedIdentities
                .map { AiToolName.identityLabel(it) }.sorted().joinToString("或")
            return "工具 ${spec.name.wire} 在当前身份（${AiToolName.identityLabel(identity)}）下不可用，" +
                "此操作需要${need}身份。请直接告知用户：到「我的」页切换身份后再试，不要重试本工具。"
        }
        val args = parseArgs(call.function.arguments)
        return try {
            if (spec.kind == AiToolName.Kind.MUTATION) {
                // 执行期解析工具（如 place_order）：先解析真实数据（地址/报价）再确认，
                // 确认展示与执行共用同一份解析快照（见 PreparedMutation）。
                val prepare = spec.prepare
                if (prepare != null) {
                    return when (val prepared = prepare(args, runScope)) {
                        is PreparedMutation.Abort -> prepared.text
                        is PreparedMutation.Ready ->
                            if (!awaitConfirmation(prepared.intent)) "用户取消了操作"
                            else prepared.execute()
                    }
                }
                val summary = spec.intentSummary?.invoke(args) ?: "AI 想执行 ${spec.name.wire}"
                val payload = args.mapValues { it.value.toString() }
                if (!awaitConfirmation(MutationIntent(spec.name, summary, payload))) {
                    return "用户取消了操作"
                }
            }
            spec.run(args, runScope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "执行 ${call.function.name} 时出错：${e.userMessage}"
        }
    }

    private fun parseArgs(json: String): JsonObject =
        runCatching { AiWireJson.parseToJsonElement(json).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }

    // MARK: - 单轮流式请求

    private sealed interface StreamOutcome {
        /** 正常收尾（含空响应；生成中止 note 已写） */
        data object Finished : StreamOutcome
        /** 错误/被停止，note 已写，结束循环 */
        data object Failed : StreamOutcome
        data class ToolCalls(val calls: List<WireToolCall>) : StreamOutcome
    }

    /** 把 UI 消息映射回 wire 历史：tool 结果带 tool_call_id；assistant 的 tool_calls 原样回发。
     *  历史经 foldWireHistory 裁剪/过期折叠后发出（只影响请求，UI 与落盘保留完整原文）。 */
    private fun buildWire(placeholderId: Long): List<ChatWireMessage> {
        // seed 只有一条 system（欢迎语是 View 装饰，绝不进历史）；
        // scoped 会话的 seedNote 并入这条 system（对齐 iOS：避免某些 endpoint 对多条 system 处理不一致）
        val sys = context?.seedNote?.takeIf { it.isNotEmpty() }
            ?.let { "$SYSTEM_PROMPT\n\n当前上下文：$it" } ?: SYSTEM_PROMPT
        val history = buildList {
            _messages.value.forEach { m ->
                if (m.id == placeholderId) return@forEach
                when {
                    m.role == "tool" ->
                        add(ChatWireMessage("tool", m.content, toolCallId = m.toolCallId, name = m.toolName))
                    m.toolCalls != null ->
                        add(ChatWireMessage("assistant", m.content.ifEmpty { null }, toolCalls = m.toolCalls))
                    m.content.isNotEmpty() ->
                        add(ChatWireMessage(m.role, m.content))
                }
            }
        }
        return listOf(ChatWireMessage("system", sys)) + foldWireHistory(history)
    }

    private fun streamOnce(placeholderId: Long): StreamOutcome {
        // 按来源解析 endpoint：系统提供 = 每次实时取本机 proxy（端口/就绪态动态，不固化）；
        // 未就绪时按状态说准原因（服务级措辞，不暴露端口/本机实现 —— 红线，对齐 iOS streamCallAI）。
        val base: String
        val apiKey: String
        val model: String
        if (settings.systemActive) {
            val ep = SystemAiProvider.endpoint
            if (ep == null) {
                appendNote(
                    placeholderId,
                    when (SystemAiProvider.status) {
                        SystemAiStatus.DISABLED -> "系统 AI 服务维护中，请稍后再试，或在配置中改用自定义来源"
                        else -> "系统 AI 服务暂不可用，请稍候再试"
                    },
                )
                return StreamOutcome.Failed
            }
            base = ep.trimEnd('/')
            apiKey = SystemAiProvider.internalKey
            model = SystemAiProvider.defaultModel
        } else {
            base = settings.baseUrl.trimEnd('/')
            apiKey = settings.apiKey
            model = settings.model
        }
        val tools = registry.wireTools(toolScope = context?.tools, identity = activeIdentity())
        val body = AiWireJson.encodeToString(
            ChatWireRequest.serializer(),
            ChatWireRequest(
                model = model,
                messages = buildWire(placeholderId),
                tools = tools.ifEmpty { null },
                toolChoice = if (tools.isEmpty()) null else "auto",
            ),
        )
        val req = Request.Builder()
            .url("$base/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val c = http.newCall(req)
        call = c
        try {
            c.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val detail = runCatching { resp.body?.string()?.take(300) }.getOrNull()
                    appendNote(placeholderId, "请求失败 HTTP ${resp.code}${detail?.let { "：$it" } ?: ""}")
                    return StreamOutcome.Failed
                }
                val source = resp.body?.source() ?: run {
                    appendNote(placeholderId, "响应为空")
                    return StreamOutcome.Failed
                }

                // token 合批 ~80ms 写回（对齐 iOS：首 token 立即，退出前 force flush）
                var contentBuf = StringBuilder()
                var reasoningBuf = StringBuilder()
                var lastFlush = 0L
                fun flush(force: Boolean) {
                    if (contentBuf.isEmpty() && reasoningBuf.isEmpty()) return
                    val now = System.currentTimeMillis()
                    if (!force && now - lastFlush < 80) return
                    val addContent = contentBuf.toString(); contentBuf = StringBuilder()
                    val addReasoning = reasoningBuf.toString(); reasoningBuf = StringBuilder()
                    _messages.update { list ->
                        list.map { m ->
                            if (m.id == placeholderId) m.copy(
                                content = m.content + addContent,
                                reasoning = when {
                                    addReasoning.isEmpty() -> m.reasoning
                                    else -> (m.reasoning ?: "") + addReasoning
                                },
                            ) else m
                        }
                    }
                    lastFlush = now
                }

                val assembler = ToolCallAssembler()
                var finishReason: String? = null
                var isErrorEvent = false
                var firstToken = true
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty()) { isErrorEvent = false; continue }
                    if (line.startsWith("event: ")) { isErrorEvent = line.removePrefix("event: ") == "error"; continue }
                    if (!line.startsWith("data: ")) continue
                    val payload = line.removePrefix("data: ")
                    if (payload == "[DONE]") break

                    when (val ev = parseSsePayload(payload, isErrorEvent)) {
                        is SseEvent.Error -> {
                            flush(force = true)
                            appendNote(placeholderId, ev.message)
                            return StreamOutcome.Failed
                        }
                        is SseEvent.Delta -> {
                            ev.content?.let { contentBuf.append(it) }
                            ev.reasoning?.let { reasoningBuf.append(it) }
                            ev.toolCalls?.forEach { assembler.add(it) }
                            flush(force = firstToken)
                            firstToken = false
                            if (ev.finishReason != null) finishReason = ev.finishReason
                        }
                        SseEvent.Skip -> Unit
                    }
                }
                flush(force = true)

                // 组装 tool_calls（仅当 finish_reason 明确为 tool_calls，对齐 iOS）
                if (finishReason == "tool_calls") {
                    val calls = assembler.build()
                    if (calls.isNotEmpty()) return StreamOutcome.ToolCalls(calls)
                }
                if (finishReason != null && finishReason != "stop" && finishReason != "tool_calls") {
                    appendNote(placeholderId, "（生成中止：$finishReason）")
                }
                return StreamOutcome.Finished
            }
        } catch (e: IOException) {
            // call.cancel()（用户停止）也走这里：已生成内容已 flush 保留
            if (c.isCanceled()) appendNote(placeholderId, "（已停止）")
            else appendNote(placeholderId, "网络错误：${e.message ?: "连接失败"}")
            return StreamOutcome.Failed
        }
    }

    private fun appendNote(placeholderId: Long, note: String) {
        _messages.update { list ->
            list.map { if (it.id == placeholderId) it.copy(note = note) else it }
        }
    }

    private companion object {
        const val MAX_ROUNDS = 8
        const val DEFAULT_WELCOME = "你好！我是你的代购助手。你想买什么？"

        // 完整人设（对齐 iOS systemPrompt）：身份 + 回复风格 + 输出格式 + 工具规则。
        // 工具规则的核心是避免模型把历史 tool result 当永久事实复用。
        val SYSTEM_PROMPT = """
        你是代购平台 App 的内置助手，帮用户挑选商品、加入购物车、下单、跟进订单。
        用户可能同时拥有买家/代购人/商家多重身份，但工具集按其**当前活跃身份**下发：
        买家身份才有选购/购物车/下单工具，代购身份才有接单/采购清单/结算工具，
        商家身份才有店铺商品/分类方案工具。用户想做当前身份之外的事（如代购身份下想买东西）时，
        不要凭对话历史调用当前不可用的工具，直接提示其到「我的」页切换身份后再来。

        回复风格：
        - 简洁直接：先给结论或答案，再补必要细节；不重复问候，不复述用户的话。
        - 默认用中文回复；用户用其他语言提问时跟随对方语言。
        - 排版用 Markdown：关键信息（价格、数量、状态）用**粗体**，多个商品或选项用列表；金额统一写成 ¥1,234.56 格式。
        - 拿不准的信息（价格、库存、订单状态）不要凭记忆编造 —— 先用工具查询再回答。

        工具规则（违反会让用户看到过期数据）：
        1. 涉及实时可变数据的问题（购物车内容、订单状态等），**每次都必须重新调用对应的工具**获取最新数据。两次对话之间用户可能修改了数据，**不要复用历史 tool 结果**。
        2. 加购 (add_to_cart) 和 下单 (place_order) 是变更操作 —— 调用前先用一句话告诉用户你打算做什么，再调用工具。
        3. 商品搜索结果（search_products / get_product_detail）可以适当复用，但用户说"再搜一次/刷新"时必须重新调用。
        4. 下单 (place_order) 的收货地址自动取用户地址簿的默认地址，费用以服务端报价为准，两者都会在确认弹窗里展示给用户 —— **绝不向用户索要姓名/电话/收货地址，也不要凭空报费用**。用户没有地址时引导其到「我的 → 地址管理」添加；想换地址时告知其到购物车结算页选择，或先调整默认地址。
        """.trimIndent()
    }
}
