package com.chunland.app.core.ai

/**
 * 依赖反转接缝（镜像 iOS ChunlandCore/AI/SystemAIProvider）：让 AiChatStore / 配置 UI 用上
 * 「本机系统 AI」（可选本地 AI 模块提供的 localhost OpenAI 服务），而 core/ai **不 import 该模块**。
 * app 层（ChunlandApp）启动时注入 endpointProvider + statusProvider + presetProvider。
 *
 * 未注入时（模块不可用）→ provider 均回 null → isIntegrated/isAvailable=false →
 * 「系统提供」选项与运行状态行自动隐藏、AiChatStore 回退用户自定义配置。core/ai 照常编译运行。
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

    /** 系统 AI 预设（模型 id + 内部 key）。**内容属模块私有知识，接缝只存放注入结果。** */
    data class Preset(val model: String, val key: String)

    /**
     * app 层随模块一起注入；null = 模块未接入（此时 isIntegrated=false，选项不显示，
     * 下面两个便利访问器的调用路径走不到，空串仅为类型兜底）。
     */
    @Volatile
    var presetProvider: (() -> Preset)? = null

    /** 系统 AI 预设模型 id（模块未接入时空串）。 */
    val defaultModel: String get() = presetProvider?.invoke()?.model ?: ""

    /** 系统 AI 内部 key（模块未接入时空串）。 */
    val internalKey: String get() = presetProvider?.invoke()?.key ?: ""
}
