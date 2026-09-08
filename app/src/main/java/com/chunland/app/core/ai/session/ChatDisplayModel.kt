package com.chunland.app.core.ai.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.chunland.app.core.ai.domain.AgentCard
import com.chunland.app.core.ai.domain.MediaRef
import com.chunland.app.core.ai.prompt.AiFence
import com.chunland.app.core.ai.tools.AiToolName
import java.util.UUID

/**
 * 消息在 UI 上的表现（对齐 iOS ChatDisplayModel.swift）。
 *
 * 与 `AgentMessage`（domain）分开：domain 关心「发给模型的是什么」，
 * 这里关心「用户看到的是什么」。两者刻意不同 ——
 * - 工具调用与结果在 domain 里是两条消息，在 UI 上是**一个块**（调用中 → 有结果）
 * - 流式文本在 domain 里只有最终态，在 UI 上要能逐字追加
 * - 系统提示（降级通知、压缩提示）在 UI 上是消息，但绝不进 domain 历史
 *
 * 用 Compose State 承载可变字段而不是「不可变 data class + 整表替换」：
 * 后者会让每个 token 触发整个列表重建，前者只重组变化的那个块。
 * 与 iOS 用 `@Observable class` 是同一个考量。
 */

class ChatTextBlock(
    text: String = "",
    /** 思考内容（灰字展示，可折叠），非正文 */
    val isThinking: Boolean = false,
) {
    val id: String = UUID.randomUUID().toString()
    var text by mutableStateOf(text)
}

class ChatToolBlock(
    val id: String,
    val name: String,
    title: String? = null,
    status: Status = Status.RUNNING,
) {
    enum class Status { RUNNING, SUCCESS, FAILED, CANCELLED }

    /**
     * 模型自述的「这次调用在做什么」（tool_title）。
     *
     * 有它就显示它 —— 「在本店找 100 元内的坚果」比「搜索商品」有用得多。
     * 模型没给才回落到工具的中文名。
     */
    var title by mutableStateOf(title)
    var status by mutableStateOf(status)

    /** 结果摘要（折叠态显示一行） */
    var resultPreview by mutableStateOf<String?>(null)

    /** 用户是否展开了详情 */
    var isExpanded by mutableStateOf(false)

    /** 折叠态那一行的文案 */
    val headline: String get() = title ?: friendlyName(name)

    companion object {
        /** 工具名 → 中文。模型没给 tool_title 时的兜底 */
        fun friendlyName(raw: String): String =
            AiToolName.entries.firstOrNull { it.wire == raw }?.friendlyName ?: raw
    }
}

sealed interface ChatBlock {
    val key: String

    data class Text(val block: ChatTextBlock) : ChatBlock {
        override val key: String get() = "t:${block.id}"
    }

    data class Tool(val block: ChatToolBlock) : ChatBlock {
        override val key: String get() = "x:${block.id}"
    }
}

/** 一条消息在 UI 上的表现 */
class ChatDisplayMessage(
    val role: Role,
    media: List<MediaRef> = emptyList(),
    isStreaming: Boolean = false,
) {
    enum class Role {
        USER,
        ASSISTANT,

        /**
         * 系统提示：降级通知、轮次上限说明、上下文已压缩等。
         * **绝不进 domain 历史** —— 它是讲给用户听的，不是讲给模型听的。
         */
        SYSTEM,
    }

    val id: String = UUID.randomUUID().toString()
    val blocks: SnapshotStateList<ChatBlock> = mutableStateListOf()

    /** 用户附带的图片 */
    val media: SnapshotStateList<MediaRef> = mutableStateListOf<MediaRef>().apply { addAll(media) }

    /**
     * 结构化卡片（R3）—— 商品等实体的权威呈现。
     *
     * **模型只报 id，这里的每个字段都来自服务端那一次查询的真实行。**
     * 模型转述数字迟早会转错一次，卡片不会。
     */
    val cards: SnapshotStateList<AgentCard> = mutableStateListOf()

    /** 出错时的说明（渲染在气泡下方） */
    var error by mutableStateOf<String?>(null)

    /** 这条消息是否还在流式生成 */
    var isStreaming by mutableStateOf(isStreaming)

    /** 可以从这条消息继续跑（触到轮次上限 / 被截断 / 被取消） */
    var isResumable by mutableStateOf(false)

    /** 纯文本内容（复制、搜索预览用） */
    val plainText: String
        get() = blocks.mapNotNull { (it as? ChatBlock.Text)?.block?.takeIf { b -> !b.isThinking }?.text }
            .joinToString("\n")

    val isEmpty: Boolean
        get() = blocks.isEmpty() && media.isEmpty() && error == null

    // MARK: - 流式构建

    /** 追加文本增量。同一段连续文本累积到同一个块里，避免每个 token 建一个块 */
    fun appendText(delta: String, thinking: Boolean = false) {
        val last = blocks.lastOrNull()
        if (last is ChatBlock.Text && last.block.isThinking == thinking) {
            last.block.text += delta
            return
        }
        blocks += ChatBlock.Text(ChatTextBlock(delta, thinking))
    }

    fun addTool(id: String, name: String, title: String?) {
        // 同一个工具 id 只建一个块 —— 事件可能重放（重试路径）
        if (findTool(id) != null) return
        blocks += ChatBlock.Tool(ChatToolBlock(id, name, title))
    }

    /**
     * 给工具块补终态。[resultText] 是**原始工具结果**（带围栏），摘要在这里成型 ——
     * 流式与重开会话两条路径都走这里，摘要口径因此只有一份。
     */
    fun finishTool(id: String, isError: Boolean, resultText: String?) {
        val block = findTool(id) ?: return
        block.status = if (isError) ChatToolBlock.Status.FAILED else ChatToolBlock.Status.SUCCESS
        block.resultPreview = toolPreview(resultText)
    }

    /**
     * 收尾时把仍在 RUNNING 的工具块强制关掉。
     *
     * 安全网：流中断、取消、异常退出都可能让某个块永远停在「执行中」，
     * UI 上就是一个转不完的圈。
     */
    fun closeDanglingTools() {
        blocks.forEach { block ->
            if (block is ChatBlock.Tool && block.block.status == ChatToolBlock.Status.RUNNING) {
                block.block.status = ChatToolBlock.Status.CANCELLED
            }
        }
    }

    private fun findTool(id: String): ChatToolBlock? =
        blocks.filterIsInstance<ChatBlock.Tool>().firstOrNull { it.block.id == id }?.block

    companion object {
        /**
         * 结果摘要的码点上限。双端同值 —— 同一次调用展开看到的东西不该因端而异。
         *
         * 取值比工具结果上限（[AiFence.MAX_RESULT_CHARS] = 6000）小一个量级：
         * 这是「扫一眼确认 AI 没瞎编」的量，不是全文阅读器。
         */
        const val TOOL_PREVIEW_MAX_CHARS = 400

        internal const val TOOL_PREVIEW_ELLIPSIS = "…"

        /**
         * 工具结果 → 折叠态可展开的摘要。
         *
         * 先剥围栏（界面上不该出现划给模型看的边界），再按**码点**截断
         * （与 [AiFence.fence] 同口径，永不切断一个字符）。
         */
        internal fun toolPreview(resultText: String?): String? {
            val body = AiFence.unfence(resultText.orEmpty())
            if (body.isEmpty()) return null
            if (body.codePointCount(0, body.length) <= TOOL_PREVIEW_MAX_CHARS) return body
            val sb = StringBuilder()
            var i = 0
            var n = 0
            while (i < body.length && n < TOOL_PREVIEW_MAX_CHARS) {
                val cp = body.codePointAt(i)
                sb.appendCodePoint(cp)
                i += Character.charCount(cp)
                n++
            }
            return sb.toString() + TOOL_PREVIEW_ELLIPSIS
        }
    }
}
