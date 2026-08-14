package com.chunland.app.core.ai.provider

import com.chunland.app.core.ai.domain.AgentBlockStart
import com.chunland.app.core.ai.domain.AgentMessage
import com.chunland.app.core.ai.domain.AgentStreamEvent
import com.chunland.app.core.ai.domain.AgentToolDefinition
import com.chunland.app.core.ai.domain.MediaRef
import com.chunland.app.core.ai.domain.TokenUsage
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI 兼容 provider（对齐 iOS OpenAICompatibleProvider.swift）。
 *
 * 同时实现两个接口：单次调用与 agent 循环共用同一份传输实现。
 * 这正是要消灭的重复所在 —— 旧代码里对话链路与单次调用链路
 * 各写了一套 SSE 解析、各有一套错误处理、各读一遍配置。
 *
 * ⚠️ **裸 OkHttpClient，绝不复用主 client** —— 那条链挂着认证拦截器，
 * 会把本项目的 token 泄给第三方服务；反向同理，用户的 apiKey 也绝不发本项目服务端。
 *
 * 职责边界：只负责「把请求发出去、把 SSE 翻译成事件」。
 * 不重试、不降级、不碰历史 —— 那些在上层。
 */
class OpenAiCompatibleProvider(
    override val modelId: String,
    private val baseUrl: String,
    private val apiKey: String,
    override val defaultMaxTokens: Int = 4096,
    private val supportsVision: Boolean = false,
    /** 图片字节读取器。只在编码那一刻调用，读完即弃 */
    private val loadImage: ((MediaRef) -> ByteArray?)? = null,
) : AgentProvider, LlmProvider {

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * 流式请求的「无数据超时」要放宽：模型思考期间可能几十秒不吐字节。
         * 读超时设 0（不限）由业务侧的取消与整体超时兜底。
         */
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    // MARK: - AgentProvider

    override fun streamAgent(
        messages: List<AgentMessage>,
        systemPrompt: String?,
        tools: List<AgentToolDefinition>,
        maxTokens: Int,
    ): Flow<AgentStreamEvent> {
        val wireMessages = OpenAiWire.encode(
            messages = messages,
            systemPrompt = systemPrompt,
            loadImage = if (supportsVision) loadImage else null,
        )
        val body = OpenAiWire.requestBody(
            model = modelId,
            messages = wireMessages,
            tools = tools,
            maxTokens = maxTokens,
            temperature = null,
        )
        return openStream(body)
    }

    // MARK: - LlmProvider

    override fun streamText(
        messages: List<LlmTurn>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
    ): Flow<String> {
        val body = OpenAiWire.requestBody(
            model = modelId,
            messages = OpenAiWire.encode(messages, systemPrompt),
            tools = emptyList(),
            maxTokens = maxTokens,
            temperature = temperature,
        )
        return openStream(body).textDeltas()
    }

    // MARK: - 传输

    private fun openStream(body: JsonObject): Flow<AgentStreamEvent> = flow {
        val trimmed = baseUrl.trimEnd('/')
        val request = Request.Builder()
            .url("$trimmed/chat/completions")
            .addHeader("Accept", "text/event-stream")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val call = client.newCall(request)
        val response = runCatching { call.execute() }
            .getOrElse { throw LlmError.fromThrowable(it) }

        response.use { resp ->
            if (!resp.isSuccessful) {
                // 错误 body 也要读出来再归类 —— 不读的话用户只能看到
                // 「HTTP 400」，看不到上游给的具体原因。
                val detail = runCatching { resp.body?.string().orEmpty() }.getOrDefault("").take(800)
                throw LlmError.fromHttpStatus(resp.code, detail)
            }

            val source = resp.body?.source() ?: throw LlmError.NetworkError("响应为空")
            val assembler = ToolCallAssembler()
            var sawContent = false
            var finishReason: String? = null
            var lastToolName = ""

            while (true) {
                currentCoroutineContextEnsureActive()
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break

                // HTTP 200 里内嵌的错误帧 —— 上游过载时的常见形态。
                // 不识别的话流会静默结束，表现为「AI 什么都没说」。
                OpenAiWire.parseErrorPayload(payload)?.let {
                    throw LlmError.TransientError(it)
                }

                val (delta, usage) = OpenAiWire.parseChunk(payload) ?: continue

                usage?.let {
                    emit(AgentStreamEvent.Usage(TokenUsage(it.input, it.output, it.cached)))
                }
                if (delta == null) continue

                delta.finishReason?.let { finishReason = it }

                delta.content?.takeIf { it.isNotEmpty() }?.let { text ->
                    if (!sawContent) {
                        sawContent = true
                        emit(AgentStreamEvent.BlockStart(AgentBlockStart.Text))
                    }
                    emit(AgentStreamEvent.TextDelta(text))
                }

                delta.reasoning?.takeIf { it.isNotEmpty() }?.let {
                    emit(AgentStreamEvent.ThinkingDelta(it))
                }

                if (delta.toolCalls.isNotEmpty()) {
                    delta.toolCalls.forEach { c ->
                        if (!c.name.isNullOrEmpty()) {
                            lastToolName = c.name
                            emit(AgentStreamEvent.BlockStart(
                                AgentBlockStart.ToolUse(c.id.orEmpty(), lastToolName)
                            ))
                        }
                    }
                    assembler.accept(delta.toolCalls)
                    // 实时预览：把当前累积的参数文本推给 UI
                    delta.toolCalls.firstOrNull()?.let { first ->
                        assembler.rawArguments(first.index)?.let { raw ->
                            emit(AgentStreamEvent.ToolInputDelta(lastToolName, raw))
                        }
                    }
                }
            }

            assembler.finish().forEach { entry ->
                emit(AgentStreamEvent.ToolCallComplete(entry.id, entry.name, entry.input))
            }

            // 有工具调用时，部分端点不给 finish_reason，按实际内容判定
            val stop = OpenAiWire.stopReason(finishReason)
                ?: if (assembler.isEmpty) null else com.chunland.app.core.ai.domain.AgentStopReason.TOOL_USE
            // 没有 stop：流在收到终止事件前就断了。不合成一个假的 Done ——
            // 循环层据「有没有 Done」判断是否中断，伪造终止会让中断的回合
            // 被当成正常完成落库。
            stop?.let { emit(AgentStreamEvent.Done(it)) }
        }
    }.flowOn(Dispatchers.IO)

    /** flow 内取消检查（Flow 的收集上下文里 ensureActive 需要 currentCoroutineContext） */
    private suspend fun currentCoroutineContextEnsureActive() {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
    }
}
