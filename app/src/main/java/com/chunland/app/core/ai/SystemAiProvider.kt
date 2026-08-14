package com.chunland.app.core.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 依赖反转接缝（镜像 iOS ChunlandCore/AI/SystemAIProvider）：让来源配置与对话链路用上
 * 「本机系统 AI」（可选本地 AI 模块提供的 localhost OpenAI 服务），而 core/ai **不 import 该模块**。
 * app 层（ChunlandApp）启动时注入 endpointProvider + statusProvider + presetProvider。
 *
 * 未注入时（模块不可用）→ provider 均回 null → isIntegrated/isAvailable=false →
 * 「系统提供」这一档不进降级链、配置页里的状态行自动隐藏，链路回落到用户自配。core/ai 照常编译运行。
 *
 * ⚠️ 本接缝**不得持有任何上游身份信息**（模型 id / key / 厂商名）—— 那属于模块的私有知识，
 * 一律经 presetProvider 注入。
 */

/** 系统 AI 运行状态（中性措辞，不暴露具体 proxy 实现）。app 层负责把模块内部状态映射过来。 */
enum class SystemAiStatus {
    STOPPED, // 未运行
    STARTING, // 启动中（首次同步进行中）
    WAITING_AUTH, // 等待登录
    UNREACHABLE, // 无法获取配置（网络/服务端异常），自动重试中
    DISABLED, // 服务端已停用
    RUNNING,
    FAILED, // 启动失败，自动重试中
}

object SystemAiProvider {
    /** app 层注入；默认 null（模块未接入时的安全态）。 */
    @Volatile
    var endpointProvider: () -> String? = { null }

    /** app 层注入；null = 模块未接入（「系统提供」选项与状态行整体隐藏）。 */
    @Volatile
    var statusProvider: (() -> SystemAiStatus)? = null

    /** 本机 proxy 的 /v1 baseUrl（不可用时 null）。 */
    val endpoint: String? get() = endpointProvider()

    /** 系统 AI 此刻是否可用（proxy 正在运行）。 */
    val isAvailable: Boolean get() = endpoint != null

    /**
     * 模块是否接入（决定配置页「系统提供」选项是否显示）。与 isAvailable 的区别：
     * isIntegrated 只看接缝是否被注入，proxy 未就绪时选项仍显示、由状态行解释原因。
     */
    val isIntegrated: Boolean get() = statusProvider != null

    /** 当前运行状态（模块未接入时 null）。 */
    val status: SystemAiStatus? get() = statusProvider?.invoke()

    /**
     * 系统 AI 预设 —— **模型的全部运行时属性**。内容属模块私有知识，接缝只存放注入结果。
     *
     * ⚠️ 这里**只放模型的属性，不放展示文案**。一旦允许 preset 带 displayName，
     * 上游模型名就会直接出现在配置页上（配置页渲染的正是 displayName）。
     * 「系统提供的 AI」这个中性称呼永远由 core/ai 侧提供。
     */
    data class Preset(
        val model: String,
        val key: String,
        /** 上下文窗口。决定 ContextPolicy 走哪一档 */
        val contextWindow: Int,
        /** 默认最大输出 token */
        val maxOutputTokens: Int,
        /**
         * 是否支持图片输入。**这是「模型能力 ∧ 本端能力」的结果**，由模块注入时算好 ——
         * 端上没有图片上传能力时，模型再能识图也必须是 false。
         */
        val supportsVision: Boolean,
    )

    /**
     * app 层随模块一起注入；null = 模块未接入（此时 isIntegrated=false，选项不显示，
     * 下面两个便利访问器的调用路径走不到，空串仅为类型兜底）。
     */
    @Volatile
    var presetProvider: (() -> Preset)? = null

    /** 当前预设（模块未接入时 null）。**每次现取** —— 服务端可热更模型，快照必然过期。 */
    val preset: Preset? get() = presetProvider?.invoke()

    // ── 按需唤醒（对齐 iOS SystemAIProvider）─────────────────────────────
    //
    // 系统 AI 未就绪是**唯一「前置条件可修」的失败**：模块本来就在轮询，只是下一次轮询
    // 可能还要等一整个周期。用户点了发送却被告知「正在准备中」、干等 60 秒再点一次，
    // 这个体验没必要 —— 催一次即可。
    //
    // ⚠️ 接缝仍不持有任何上游身份：这是个无参无返回值的纯动作，节流与实现都在模块内。

    /** 催一次配置同步。app 层随模块注入；未注入 = 无操作。 */
    @Volatile
    var syncRequester: (suspend () -> Unit)? = null

    /**
     * 催一次同步，最多等 [timeoutMs] 毫秒。
     *
     * 超时即返回，**但后台那次拉取不取消** —— 它跑完之后系统 AI 就绪，
     * 用户再点一次就能用上。等待上限存在的意义是不让用户对着转圈干等一次网络超时（15s）。
     */
    suspend fun requestSync(timeoutMs: Long) {
        val requester = syncRequester ?: return
        // 独立 scope：本次等待超时/被取消都不该掐掉正在跑的那次拉取
        syncScope.launch { requester() }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (endpointProvider() != null) return
            delay(100)
        }
    }

    /**
     * 催一次同步但不等 —— 用于「还有下一档可降级」时：本次请求走兜底来源，
     * 这次催拉是为了让下一次请求能用上系统 AI。
     */
    fun requestSyncDetached() {
        val requester = syncRequester ?: return
        syncScope.launch { requester() }
    }

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 系统 AI 预设模型 id（模块未接入时空串）。 */
    val defaultModel: String get() = presetProvider?.invoke()?.model ?: ""

    /** 系统 AI 内部 key（模块未接入时空串）。 */
    val internalKey: String get() = presetProvider?.invoke()?.key ?: ""
}
