package com.chunland.app.core.ai.provider

import java.util.UUID

/**
 * 四级配置模型（对齐 iOS ProviderModels.swift）。
 *
 * ```
 * ProviderInstance   凭证与地址（一个「来源」）
 *   └ ModelEntry     该来源下的一个模型
 *       └ ModelGroup 一组模型 + 路由策略（降级的载体）
 *           └ 会话绑定  某个会话用哪个组 / 哪个模型
 * ```
 *
 * 为什么要四级而不是「一条配置记录」：
 * 当前实现是 baseUrl/model/apiKey 三个字段，意味着**系统 AI 挂了用户就没 AI 用**
 * （号池耗尽 → 下发 disabled → 直接不可用）。有了组，才能表达
 * 「先用系统 AI，不行就切用户自配」这件事。
 *
 * 结构照搬但实现只做 OpenAI 兼容：系统 AI 与用户自配都是 OpenAI 兼容协议，
 * 多协议实现当前没有需求驱动。
 */

/** 来源类型 */
enum class ProviderKind(val wire: String) {
    /** 用户自配的 OpenAI 兼容端点 */
    OPENAI_COMPATIBLE("openai_compatible"),

    /**
     * 系统提供的 AI（本机服务）。
     * 地址与密钥都不存库 —— 运行时经依赖反转接缝取（端口与就绪态是动态的）。
     */
    SYSTEM("system"),

    /**
     * 本 App 版本不认识的类型（更高版本写入的配置）。
     * 解码成它而不是丢弃，避免把别人的配置改坏。
     */
    UNSUPPORTED("unsupported");

    val displayName: String
        get() = when (this) {
            OPENAI_COMPATIBLE -> "自定义（OpenAI 兼容）"
            SYSTEM -> "系统提供"
            UNSUPPORTED -> "不支持的来源"
        }

    /** 密钥是否存本地安全存储。系统 AI 的密钥由接缝提供，不落任何存储 */
    val usesStoredApiKey: Boolean get() = this == OPENAI_COMPATIBLE

    companion object {
        fun decoded(raw: String): ProviderKind =
            entries.firstOrNull { it.wire == raw } ?: UNSUPPORTED
    }
}

/** 一个配置好的来源 */
data class ProviderInstance(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val kind: ProviderKind,
    /** OpenAI 兼容端点的 base URL（系统 AI 为 null，运行时取） */
    val baseUrl: String? = null,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    /** 不认识的类型的原始字符串 —— 回写时原样保留，不改坏别人的配置 */
    val unknownKindRaw: String? = null,
) {
    companion object {
        /** 系统 AI 实例的固定 id —— 它是单例，不允许建多个 */
        const val SYSTEM_INSTANCE_ID = "system"

        fun system() = ProviderInstance(
            id = SYSTEM_INSTANCE_ID,
            label = "系统提供的 AI",
            kind = ProviderKind.SYSTEM,
        )
    }
}

/** 一个可用的模型 */
data class ModelEntry(
    val instanceId: String,
    val modelId: String,
    val displayName: String = modelId,
    /** 上下文窗口。决定上下文治理走哪一档策略 */
    val contextWindow: Int = 32_000,
    /** 默认最大输出 token */
    val maxOutputTokens: Int = 4_096,
    /** 是否支持图片输入。不支持时不下发读图类工具、也不把图片编进请求 */
    val supportsVision: Boolean = false,
) {
    /**
     * 复合 id：`{instanceId}:{modelId}` —— 同一个模型挂在不同来源下是两个条目。
     *
     * **系统 AI 例外**：它的 modelId 是运行时属性（随模块预设 / 服务端下发变化），
     * 若编进 id，换一次模型就等于换了一个条目 —— 默认组成员与会话绑定会一起踩空。
     * 故系统 AI 的 id 恒为 `system:system`，与 modelId 解耦。
     */
    val id: String get() =
        if (instanceId == ProviderInstance.SYSTEM_INSTANCE_ID) SYSTEM_ENTRY_ID
        else "$instanceId:$modelId"

    companion object {
        /** 系统 AI 落库时占位用的 modelId。真实模型 id 是运行时属性，读出口现取 */
        const val SYSTEM_MODEL_SENTINEL = "system"

        /** 系统 AI 条目的固定 id */
        const val SYSTEM_ENTRY_ID = "${ProviderInstance.SYSTEM_INSTANCE_ID}:$SYSTEM_MODEL_SENTINEL"

        /** 从复合 id 拆回两段 */
        fun split(entryId: String): Pair<String, String>? {
            val sep = entryId.indexOf(':')
            if (sep <= 0 || sep == entryId.length - 1) return null
            return entryId.substring(0, sep) to entryId.substring(sep + 1)
        }
    }
}

/** 组内路由策略 */
enum class RoutingStrategy(val wire: String) {
    /** 按顺序试，失败换下一个 */
    FALLBACK("fallback"),

    /** 会话间轮换（分摊用量） */
    LOAD_BALANCE("loadBalance");

    companion object {
        fun decoded(raw: String?): RoutingStrategy =
            entries.firstOrNull { it.wire == raw } ?: FALLBACK
    }
}

/** 什么错误触发降级 */
enum class FallbackStrategy(val wire: String) {
    /**
     * 保守：只在 provider 级错误（限流、密钥无效、拒绝）时换；
     * 网络与瞬时错误先在当前模型重试。
     */
    LIMITED("limited"),

    /** 激进：任何错误都立刻换，不在当前模型重试 */
    ALWAYS("always");

    companion object {
        fun decoded(raw: String?): FallbackStrategy =
            entries.firstOrNull { it.wire == raw } ?: LIMITED
    }
}

/** 一组模型 —— 降级的载体 */
data class ModelGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** 有序的成员条目 id。顺序即降级顺序 */
    val memberEntryIds: List<String>,
    val strategy: RoutingStrategy = RoutingStrategy.FALLBACK,
    val fallbackStrategy: FallbackStrategy = FallbackStrategy.LIMITED,
) {
    companion object {
        /** 默认组的固定 id —— 新装用户自动获得「系统 AI 优先，自配兜底」 */
        const val DEFAULT_GROUP_ID = "default"
    }
}

/** 会话用哪个模型 */
sealed interface SessionModelBinding {
    /** 绑到一个组（可降级） */
    data class Group(val groupId: String) : SessionModelBinding

    /** 钉死一个模型（用户显式选择，不降级、不被自动改写） */
    data class Entry(val entryId: String) : SessionModelBinding
}

/**
 * 降级记录。
 *
 * 降级必须让用户看得见 —— 否则「为什么今天回答风格变了」无从解释。
 */
data class FallbackRecord(
    val fromModel: String,
    val toModel: String,
    val reason: String,
    val at: Long = System.currentTimeMillis(),
) {
    val userText: String get() = "「$fromModel」$reason，已切换到「$toModel」继续。"
}
