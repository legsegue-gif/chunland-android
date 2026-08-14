package com.chunland.app.feature.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chunland.app.core.ai.storage.AiSearchHit
import com.chunland.app.core.ai.storage.AiSessionRecord
import com.chunland.app.core.ai.storage.AiTextSegmenter
import com.chunland.app.core.ai.storage.SessionRepo
import java.util.Calendar
import kotlinx.coroutines.launch

/**
 * 会话抽屉（对齐 iOS AgentConversationDrawer.swift）。
 *
 * 与旧实现的两处差别：
 *
 * - **分页**：旧的把所有会话连同全部消息一次读进内存。页面 ✨ 会话按 contextKey
 *   堆积（每个商品、每个订单各一条），量涨得比预期快。现在只查会话表，
 *   带 message_count 与 last_preview 两个冗余列，翻到哪读到哪。
 * - **搜索**：旧的只能按时间倒序翻。现在可以搜「上次问的那个坚果」，
 *   跨会话命中并直接跳到那条会话。
 */
@Composable
fun AgentConversationDrawer(
    repo: SessionRepo,
    ownerUserId: String?,
    padding: PaddingValues,
    onOpen: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var sessions by remember { mutableStateOf<List<AiSessionRecord>>(emptyList()) }
    var hits by remember { mutableStateOf<List<AiSearchHit>>(emptyList()) }
    var keyword by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var reachedEnd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AiSessionRecord?>(null) }

    val pageSize = 30

    suspend fun loadFirstPage() {
        loading = true
        sessions = runCatching { repo.list(ownerUserId, pageSize) }.getOrDefault(emptyList())
        reachedEnd = sessions.size < pageSize
        loading = false
    }

    LaunchedEffect(Unit) { loadFirstPage() }

    LaunchedEffect(keyword) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) {
            hits = emptyList()
            return@LaunchedEffect
        }
        loading = true
        hits = runCatching { repo.search(ownerUserId, trimmed) }.getOrDefault(emptyList())
        loading = false
    }

    // 滚到底自动加载下一页 —— 不做「加载更多」按钮，
    // 会话列表是连续浏览的场景，按钮会打断节奏
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            keyword.isBlank() && !reachedEnd && !loading && last >= sessions.size - 3
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (!shouldLoadMore) return@LaunchedEffect
        loading = true
        val next = runCatching { repo.list(ownerUserId, pageSize, sessions.size) }
            .getOrDefault(emptyList())
        sessions = sessions + next
        reachedEnd = next.size < pageSize
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(padding)) {
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            placeholder = { Text("搜索对话内容") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        HorizontalDivider()

        if (keyword.isBlank()) {
            SessionList(sessions, listState, loading) { record -> onOpen(record.id) }
        } else {
            SearchResults(hits, keyword, loading) { hit -> onOpen(hit.sessionId) }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条对话？") },
            text = { Text("删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { repo.delete(target.id) }
                        sessions = sessions.filterNot { it.id == target.id }
                        pendingDelete = null
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SessionList(
    sessions: List<AiSessionRecord>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    loading: Boolean,
    onOpen: (AiSessionRecord) -> Unit,
) {
    if (sessions.isEmpty() && !loading) {
        EmptyHint("还没有历史对话")
        return
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        val grouped = sessions.groupBy { dayBucket(it.updatedAt) }
        listOf("今天", "昨天", "更早").forEach { bucket ->
            val items = grouped[bucket].orEmpty()
            if (items.isEmpty()) return@forEach
            item(key = "header-$bucket") {
                Text(
                    bucket,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            items(items, key = { it.id }) { record ->
                SessionRow(record) { onOpen(record) }
            }
        }
        if (loading) {
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun SessionRow(record: AiSessionRecord, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (record.contextKey != null) {
                // 页面 ✨ 起的会话，与主对话区分开
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary)
            }
            Text(record.title, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        record.lastPreview?.takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SearchResults(
    hits: List<AiSearchHit>,
    keyword: String,
    loading: Boolean,
    onOpen: (AiSearchHit) -> Unit,
) {
    if (hits.isEmpty()) {
        EmptyHint(if (loading) "搜索中…" else "没有找到包含「$keyword」的对话")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(hits, key = { it.messageId }) { hit ->
            Column(
                Modifier.fillMaxWidth().clickable { onOpen(hit) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(hit.sessionTitle, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    highlighted(hit.snippet, keyword),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 命中词加粗。
 *
 * 在原文里定位而不是用数据库的 snippet 函数 —— 后者返回的是分词后的
 * 文本（中文会变成「坚 果」带空格），拿来展示很难看。
 */
private fun highlighted(text: String, keyword: String): AnnotatedString {
    val ranges = AiTextSegmenter.highlightRanges(text, keyword)
    if (ranges.isEmpty()) return AnnotatedString(text)

    return buildAnnotatedString {
        var cursor = 0
        ranges.forEach { range ->
            if (range.first < cursor) return@forEach
            append(text.substring(cursor, range.first))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(text.substring(range.first, minOf(range.last + 1, text.length)))
            }
            cursor = minOf(range.last + 1, text.length)
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}

@Composable
private fun EmptyHint(text: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline)
    }
}

private fun dayBucket(epochMillis: Long): String {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = epochMillis }
    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    if (sameDay(now, target)) return "今天"
    now.add(Calendar.DAY_OF_YEAR, -1)
    if (sameDay(now, target)) return "昨天"
    return "更早"
}
