package com.chunland.app.core.ai.provider

import android.util.Log
import com.chunland.app.core.ai.SystemAiProvider
import com.chunland.app.core.ai.SystemAiStatus
import com.chunland.app.core.ai.domain.AgentMessage
import com.chunland.app.core.ai.domain.AgentStreamEvent
import com.chunland.app.core.ai.domain.AgentToolDefinition
import com.chunland.app.core.ai.domain.MediaRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * 从配置条目造 provider（对齐 iOS ProviderFactory.swift）。
 *
 * 系统 AI 与用户自配的差别全部收敛在这里：
 * 前者的地址与密钥是**运行时动态**的（本机服务端口会变、就绪态会变），
 * 每次都要现取；后者是静态配置，读库 + 本地安全存储。
 *
 * ⚠️ 依赖反转红线：本文件（以及整个 provider 层）**绝不 import 本机 AI 模块**。
 * 系统 AI 的地址、密钥、模型名一律经 [SystemAiProvider] 这个接缝取 ——
 * 接缝由 app 层注入，模块不存在时自动降级为「系统 AI 不可用」，一切照常编译。
 */
class ProviderFactory(
    private val config: ProviderConfigStore,
    private val credentials: ProviderCredentials,
    private val loadImage: ((MediaRef) -> ByteArray?)? = null,
) {

    /**
     * 解析出一个可用的 provider。
     *
     * 抛错而不是返回 null：失败原因（未配置 / 系统 AI 未就绪 / 缺密钥）
     * 各自对应不同的用户提示与降级决策，吞掉就没法区分了。
     */
    fun make(entry: ModelEntry): OpenAiCompatibleProvider {
        val instance = config.instance(entry.instanceId) ?: throw LlmError.NotConfigured

        val baseUrl: String
        val apiKey: String

        when (instance.kind) {
            ProviderKind.SYSTEM -> {
                // 每次现取：端口与就绪态是动态的，固化到配置里必然过期
                baseUrl = SystemAiProvider.endpoint
                    ?: throw LlmError.SystemProviderUnavailable(systemUnavailableReason())
                apiKey = SystemAiProvider.internalKey
            }

            ProviderKind.OPENAI_COMPATIBLE -> {
                baseUrl = instance.baseUrl?.takeIf { it.isNotBlank() }
                    ?: throw LlmError.NotConfigured
                apiKey = credentials.apiKey(instance.id)
                    ?: throw LlmError.InvalidApiKey("「${instance.label}」尚未填写 API Key")
            }

            ProviderKind.UNSUPPORTED ->
                throw LlmError.ProviderError("「${instance.label}」的来源类型本版本不支持，请升级 App")
        }

        return OpenAiCompatibleProvider(
            modelId = entry.modelId,
            baseUrl = baseUrl,
            apiKey = apiKey,
            defaultMaxTokens = entry.maxOutputTokens,
            supportsVision = entry.supportsVision,
            loadImage = loadImage,
        )
    }

    /**
     * 系统 AI 不可用时的用户可读原因。
     *
     * 措辞刻意保持服务级、不暴露本机实现细节 —— 用户不需要知道
     * 它是一个跑在本地的服务，只需要知道该等一会儿还是该换来源。
     */
    private fun systemUnavailableReason(): String = when (SystemAiProvider.status) {
        SystemAiStatus.DISABLED -> "系统 AI 暂停服务，可在配置中改用自定义来源"
        SystemAiStatus.WAITING_AUTH -> "系统 AI 需要先登录"
        SystemAiStatus.STARTING, SystemAiStatus.STOPPED,
        SystemAiStatus.UNREACHABLE, SystemAiStatus.FAILED -> "系统 AI 正在准备中，请稍候再试"
        else -> "系统 AI 暂不可用"
    }
}

/**
 * 重试与降级（对齐 iOS ProviderRouter.swift）。
 *
 * 这一层解决当前实现里最要命的一个问题：**系统 AI 是唯一来源，它挂了用户就没 AI 用**
 * （号池耗尽 → 服务端下发 disabled → 直接不可用，没有任何兜底）。
 *
 * 降级链：
 * ```
 * 首选模型 ──失败──> 按策略：
 *                     LIMITED（保守）→ 网络/瞬时错误先原地重试，耗尽再换模型
 *                     ALWAYS（激进）→ 任何错误立刻换下一个
 *                   ──换到底──> 抛出最后一个错误
 * ```
 *
 * **降级只发生在「第一个事件到达之前」。** 一旦模型开始吐字，内容已经进了 UI，
 * 这时再换模型会让用户看到半句话被另一个模型接着写下去。中途失败属于
 * 「清除未提交的尾巴 + 原模型重试」，那是循环层的职责，不在这里。
 */
class ProviderRouter(
    private val config: ProviderConfigStore,
    private val factory: ProviderFactory,
) {

    companion object {
        private const val TAG = "ProviderRouter"

        /**
         * 重试间隔（毫秒）。
         *
         * 比通用 agent 工具短得多 —— 那类工具在做长任务，用户不盯着；
         * 而这里用户正看着对话框等回复，超过十几秒就该换模型而不是继续等。
         */
        val RETRY_DELAYS_MS = listOf(2_000L, 4_000L, 8_000L)

        /**
         * 系统 AI 未就绪时最多等多久。
         *
         * 用户正盯着输入框，等待必须短到「像是在加载」而不是「卡住了」。
         * 等不到就按原路失败 —— 后台那次拉取仍在跑，再点一次通常就好了。
         */
        const val SYSTEM_SYNC_WAIT_MS = 3_000L
    }

    /**
     * 系统 AI 就绪预热（对齐 iOS ProviderRouter.primeSystemAvailability）。
     *
     * 系统 AI 不可用是候选链里唯一**前置条件可修**的失败：模块本就在轮询，
     * 只是下一拍可能还有一整个周期（默认 60s）。这里先催一次。
     *
     * **只在它是唯一候选时才等**：还有下一档可降级时干等，等于让配了兜底来源的用户
     * 平白多花几秒 —— 那种情况下催拉扔后台，本次请求照常走降级。
     */
    private suspend fun primeSystemAvailability(candidates: List<ModelEntry>) {
        if (!SystemAiProvider.isIntegrated || SystemAiProvider.isAvailable) return
        if (candidates.none { it.instanceId == ProviderInstance.SYSTEM_INSTANCE_ID }) return

        if (candidates.size == 1) {
            SystemAiProvider.requestSync(SYSTEM_SYNC_WAIT_MS)
        } else {
            SystemAiProvider.requestSyncDetached()
        }
    }

    /** 一次路由的结果 */
    data class Routed(
        val stream: Flow<AgentStreamEvent>,
        /** 最终用上的模型条目 */
        val entry: ModelEntry,
        /** 中途发生过的降级（按顺序），供 UI 告知用户 */
        val fallbacks: List<FallbackRecord>,
    )

    /**
     * 打开一条流，必要时自动重试与降级。
     *
     * 返回时第一个事件已经成功取到 —— 连接、鉴权、限流的失败都已在内部处理完。
     */
    suspend fun stream(
        binding: SessionModelBinding?,
        messages: List<AgentMessage>,
        systemPrompt: String?,
        tools: List<AgentToolDefinition>,
        maxTokens: Int? = null,
    ): Routed {
        val candidates = resolveCandidates(binding)
        if (candidates.isEmpty()) throw LlmError.NotConfigured
        primeSystemAvailability(candidates)

        val strategy = fallbackStrategy(binding)
        val fallbacks = mutableListOf<FallbackRecord>()
        var lastError: LlmError = LlmError.NotConfigured

        candidates.forEachIndexed { index, entry ->
            val isLast = index == candidates.size - 1
            // 保守策略下才在当前模型上重试；激进策略直接换下一个
            val attempts = if (strategy == FallbackStrategy.LIMITED) RETRY_DELAYS_MS.size + 1 else 1

            for (attempt in 0 until attempts) {
                if (attempt > 0) {
                    val wait = RETRY_DELAYS_MS[attempt - 1]
                    Log.i(TAG, "重试等待 ${wait}ms model=${entry.modelId} attempt=$attempt")
                    delay(wait)
                }

                try {
                    val provider = factory.make(entry)
                    val raw = provider.streamAgent(
                        messages = messages,
                        systemPrompt = systemPrompt,
                        tools = tools,
                        maxTokens = maxTokens ?: entry.maxOutputTokens,
                    )
                    // 关键：把第一个事件拉出来。连接失败、鉴权失败、限流
                    // 都在这一步暴露 —— 此时还没有任何内容进 UI，可以安全换模型。
                    val primed = prime(raw)
                    if (fallbacks.isNotEmpty()) {
                        Log.i(TAG, "降级后成功 model=${entry.modelId} hops=${fallbacks.size}")
                    }
                    return Routed(primed, entry, fallbacks.toList())

                } catch (e: Throwable) {
                    val error = LlmError.fromThrowable(e)
                    lastError = error
                    if (error.isCancellation) throw error

                    val canRetryHere = strategy == FallbackStrategy.LIMITED &&
                        error.isRetryable &&
                        attempt < attempts - 1
                    if (canRetryHere) {
                        Log.w(TAG, "将重试 model=${entry.modelId} reason=${error.fallbackReason}")
                        continue
                    }
                    Log.w(TAG, "放弃该模型 model=${entry.modelId} reason=${error.fallbackReason}")
                    break   // 换下一个候选
                }
            }

            if (!isLast) {
                val next = candidates[index + 1]
                fallbacks += FallbackRecord(
                    fromModel = entry.displayName,
                    toModel = next.displayName,
                    reason = lastError.fallbackReason,
                )
            }
        }

        throw lastError
    }

    // MARK: - 候选解析

    /** 按绑定解析出有序的候选链 */
    private fun resolveCandidates(binding: SessionModelBinding?): List<ModelEntry> = when (binding) {
        // 用户显式钉死了模型：**不降级**。他选了什么就用什么，
        // 背着他换模型比失败更糟（回答风格突变且无从解释）。
        is SessionModelBinding.Entry -> listOfNotNull(config.entry(binding.entryId))

        is SessionModelBinding.Group ->
            config.group(binding.groupId)?.let { config.usableEntries(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: defaultCandidates()

        null -> defaultCandidates()
    }

    private fun defaultCandidates(): List<ModelEntry> =
        config.defaultGroup()?.let { config.usableEntries(it) } ?: emptyList()

    private fun fallbackStrategy(binding: SessionModelBinding?): FallbackStrategy {
        if (binding is SessionModelBinding.Group) {
            config.group(binding.groupId)?.let { return it.fallbackStrategy }
        }
        return config.defaultGroup()?.fallbackStrategy ?: FallbackStrategy.LIMITED
    }

    // MARK: - 预热

    /**
     * 拉取第一个事件，再把它与后续事件一起重新组成流。
     *
     * 这是「失败可降级」与「已出内容不可撤回」之间的分界线：
     * 第一个事件成功 = 连接已建立、鉴权已通过、上游开始工作。
     */
    private suspend fun prime(upstream: Flow<AgentStreamEvent>): Flow<AgentStreamEvent> {
        // 独立子作用域：上游要在本函数返回后继续跑（把剩余事件送进 channel），
        // 所以不能用 coroutineScope{}（它会等子协程结束才返回）。
        // SupervisorJob 挂在调用方的 context 上 —— 调用方取消时它一并取消。
        val scope = CoroutineScope(currentCoroutineContext() + SupervisorJob())
        val channel = Channel<AgentStreamEvent>(Channel.BUFFERED)
        val firstSignal = CompletableDeferred<Unit>()

        val job = scope.launch {
            try {
                var sawFirst = false
                upstream.collect { event ->
                    if (!sawFirst) {
                        sawFirst = true
                        firstSignal.complete(Unit)
                    }
                    channel.send(event)
                }
                // 上游一个事件都没产出就正常结束：不是失败，交给循环层判定空响应
                if (!sawFirst) firstSignal.complete(Unit)
                channel.close()
            } catch (e: Throwable) {
                // 首个事件之前失败 → 让 await() 抛出，路由据此降级；
                // 之后失败 → 关闭 channel 把异常传给下游收集者。
                if (!firstSignal.isCompleted) firstSignal.completeExceptionally(e)
                channel.close(e)
            }
        }

        firstSignal.await()

        return flow {
            try {
                for (event in channel) emit(event)
            } finally {
                job.cancel()
            }
        }
    }
}
