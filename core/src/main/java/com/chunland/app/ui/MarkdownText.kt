package com.chunland.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 轻量块级 Markdown 渲染（对齐 iOS Components/MarkdownText：零三方依赖，只覆盖
 * AI 回复常见结构）：# 标题 / - 无序列表 / 1. 有序列表 / ``` 代码块 / 段落，
 * 行内 **加粗** 与 `代码`。解析失败/不认识的语法按原文段落显示，信息不丢失。
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parseBlocks(text).forEach { block ->
            when (block) {
                is MdBlock.Code -> Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        block.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(8.dp),
                    )
                }
                is MdBlock.Heading -> Text(
                    inlineStyled(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                )
                is MdBlock.ListItem -> Row {
                    Text(
                        block.marker,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(inlineStyled(block.text), style = MaterialTheme.typography.bodyMedium)
                }
                is MdBlock.Paragraph -> Text(
                    inlineStyled(block.text),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class ListItem(val marker: String, val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
}

private val headingRe = Regex("^(#{1,4})\\s+(.*)$")
private val bulletRe = Regex("^[-*•]\\s+(.*)$")
private val orderedRe = Regex("^(\\d+)[.、]\\s+(.*)$")

private fun parseBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    var codeBuffer: StringBuilder? = null

    fun flushParagraph() {
        if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }

    text.lines().forEach { line ->
        val fence = line.trimStart().startsWith("```")
        val code = codeBuffer
        when {
            fence && code == null -> {
                flushParagraph()
                codeBuffer = StringBuilder()
            }
            fence && code != null -> {
                blocks += MdBlock.Code(code.toString().trimEnd())
                codeBuffer = null
            }
            code != null -> code.appendLine(line)
            else -> {
                val heading = headingRe.find(line)
                val bullet = bulletRe.find(line.trim())
                val ordered = orderedRe.find(line.trim())
                when {
                    heading != null -> {
                        flushParagraph()
                        blocks += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
                    }
                    bullet != null -> {
                        flushParagraph()
                        blocks += MdBlock.ListItem("· ", bullet.groupValues[1])
                    }
                    ordered != null -> {
                        flushParagraph()
                        blocks += MdBlock.ListItem("${ordered.groupValues[1]}. ", ordered.groupValues[2])
                    }
                    line.isBlank() -> flushParagraph()
                    else -> {
                        if (paragraph.isNotEmpty()) paragraph.append('\n')
                        paragraph.append(line)
                    }
                }
            }
        }
    }
    // 未闭合代码围栏按代码块兜底（流式输出常见半截状态）
    codeBuffer?.let { blocks += MdBlock.Code(it.toString().trimEnd()) }
    flushParagraph()
    return blocks
}

// 行内：**加粗** 与 `代码`。逐段扫描，未闭合标记按字面输出。
private fun inlineStyled(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else {
                    append(text[i]); i++
                }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }
            else -> {
                append(text[i]); i++
            }
        }
    }
}

private fun AnnotatedString.Builder.withStyle(style: SpanStyle, block: AnnotatedString.Builder.() -> Unit) {
    val start = length
    block()
    addStyle(style, start, length)
}
