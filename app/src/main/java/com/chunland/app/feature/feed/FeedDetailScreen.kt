package com.chunland.app.feature.feed

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.chunland.app.core.AppGraph
import com.chunland.app.core.auth.LoginIntents
import com.chunland.app.core.network.absoluteMediaUrl
import com.chunland.app.data.model.FeedItem
import com.chunland.app.data.model.FeedLink
import com.chunland.app.feature.report.ReportSheet
import kotlinx.coroutines.launch

/**
 * 内容卡 → 详情页的进程内传递（服务端无按 id 取单条 feed 的端点，对齐 iOS
 * NavigationLink 直传结构体的语义）。LRU 上限防长会话堆积；进程重建后取不到
 * 则详情页直接退回列表（feed 数据本就是流式快照，无恢复义务）。
 */
internal object FeedItemStash {
    private val items = object : LinkedHashMap<Long, FeedItem>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, FeedItem>) = size > 32
    }

    fun put(item: FeedItem) = synchronized(items) { items[item.id] = item }
    fun get(id: Long): FeedItem? = synchronized(items) { items[id] }
}

/**
 * 内容详情页（对齐 iOS FeedDetailView；商家可购卡走商品详情页，不到这里）。
 * 富文本内联化（方案A）：meta.links / meta.coupons 按锚文本装回正文 ——
 * 正文里的【购买链接】直接可点开外链、口令点击即复制；只有正文找不到锚点的
 * 条目才回退到底部组件，信息不丢失。
 */
@Composable
fun FeedDetailScreen(
    graph: AppGraph,
    itemId: Long,
    onBack: () -> Unit,
    requireLogin: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val item = remember(itemId) { FeedItemStash.get(itemId) }
    if (item == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val authState by graph.authManager.state.collectAsState()
    val followKeys by graph.followStore.keys.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    var menuOpen by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }

    LaunchedEffect(authState.isLoggedIn) {
        if (authState.isLoggedIn) graph.followStore.loadIfNeeded()
    }

    val copyCoupon: (String) -> Unit = { code ->
        clipboard.setText(AnnotatedString(code))
        scope.launch { snackbar.showSnackbar("已复制口令") }
    }
    val openLink: (String) -> Unit = { url ->
        runCatching { uriHandler.openUri(url) }
            .onFailure { scope.launch { snackbar.showSnackbar("无法打开链接") } }
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val inline = remember(item) { buildInlineContent(item, linkColor, openLink, copyCoupon) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Surface {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp),
                ) {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text("详情", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    androidx.compose.material3.IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("举报内容") },
                            onClick = {
                                menuOpen = false
                                if (!authState.isLoggedIn) {
                                    LoginIntents.set { showReport = true }
                                    requireLogin()
                                } else {
                                    showReport = true
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // 头部：频道名 + @handle + 关注按钮
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    item.authorName ?: item.authorHandle ?: "频道",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                item.authorHandle?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "@$it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                item.channelId?.let { cid ->
                    val followed = "channel:$cid" in followKeys
                    val doToggle: () -> Unit = {
                        scope.launch {
                            graph.followStore.loadIfNeeded()
                            graph.followStore.toggle("channel", cid.toString())
                                ?.let { snackbar.showSnackbar(it) }
                        }
                    }
                    TextButton(onClick = {
                        if (!authState.isLoggedIn) {
                            LoginIntents.set(doToggle)   // 登录成功自动续做
                            requireLogin()
                        } else {
                            doToggle()
                        }
                    }) {
                        Text(if (followed) "已关注" else "+ 关注")
                    }
                }
            }

            if (inline.text.isNotEmpty()) {
                Text(inline.text, style = MaterialTheme.typography.bodyLarge)
            }

            // 全部大图（完整比例展示，对齐 iOS scaledToFit）
            val images = item.media.filter { it.kind == "photo" || it.kind == "animation" }
            images.forEach { m ->
                // 局部变量：跨 module 的 public 属性无法 smart cast（:core 的 DTO）
                val w = m.width
                val h = m.height
                val ratio = if (w != null && h != null && h > 0) w.toFloat() / h else 4f / 3f
                AsyncImage(
                    model = absoluteMediaUrl(m.url),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(ratio.coerceIn(0.4f, 2.5f))
                        .clip(MaterialTheme.shapes.medium),
                )
            }

            // 底部回退：仅渲染正文里没匹配到锚点的口令
            if (inline.unmatchedCoupons.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "优惠码 / 口令",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    inline.unmatchedCoupons.forEach { c ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().clickable { copyCoupon(c) },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(10.dp),
                            ) {
                                Text(
                                    c,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = "复制",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // 底部回退：仅渲染正文里没匹配到锚点的外链（如无 label 的裸链接）
            if (inline.unmatchedLinks.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    inline.unmatchedLinks.forEach { link ->
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().clickable { openLink(link.url) },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(14.dp),
                            ) {
                                Icon(
                                    Icons.Filled.ShoppingCart,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    link.label ?: "去购买",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showReport) {
        ReportSheet(
            graph = graph,
            targetType = "feed_item",
            targetKey = item.id.toString(),
            onDismiss = { showReport = false },
        )
    }
}

// MARK: 内联化（方案A：label 文本匹配，对齐 iOS InlineContent）

internal class InlineParts(
    val text: AnnotatedString,
    val unmatchedLinks: List<FeedLink>,
    val unmatchedCoupons: List<String>,
)

/** 把 meta.links / meta.coupons 装回正文锚点；匹配不到的留给底部回退组件。 */
internal fun buildInlineContent(
    item: FeedItem,
    linkColor: Color,
    onOpenLink: (String) -> Unit,
    onCopyCoupon: (String) -> Unit,
): InlineParts {
    val raw = item.text.orEmpty()
    val unmatchedLinks = mutableListOf<FeedLink>()
    val unmatchedCoupons = mutableListOf<String>()

    class ClickSpan(val range: IntRange, val onClick: () -> Unit)
    val spans = mutableListOf<ClickSpan>()

    fun matchAll(needle: String): List<IntRange> {
        if (needle.isEmpty()) return emptyList()
        val out = mutableListOf<IntRange>()
        var from = 0
        while (true) {
            val i = raw.indexOf(needle, from)
            if (i < 0) break
            out += i until (i + needle.length)
            from = i + needle.length
        }
        return out
    }

    for (link in item.meta?.links.orEmpty()) {
        val label = link.label
        // 优先连书名号整体匹配【label】（整段着色更醒目），退化匹配裸 label
        val ranges = if (label.isNullOrEmpty()) emptyList() else {
            matchAll("【$label】").ifEmpty { matchAll(label) }
        }
        if (ranges.isEmpty()) {
            unmatchedLinks += link
        } else {
            ranges.forEach { r -> spans += ClickSpan(r) { onOpenLink(link.url) } }
        }
    }

    for (coupon in item.meta?.coupons.orEmpty()) {
        // 先整串匹配（ETL 即从正文切出，通常命中），退化只匹配 ￥…￥ 口令体
        var ranges = matchAll(coupon)
        if (ranges.isEmpty()) {
            Regex("￥[^￥]+￥").find(coupon)?.value?.let { token -> ranges = matchAll(token) }
        }
        if (ranges.isEmpty()) {
            unmatchedCoupons += coupon
        } else {
            ranges.forEach { r -> spans += ClickSpan(r) { onCopyCoupon(coupon) } }
        }
    }

    val annotated = buildAnnotatedString {
        append(raw)
        val style = TextLinkStyles(style = SpanStyle(color = linkColor))
        spans.forEach { s ->
            addLink(
                LinkAnnotation.Clickable(tag = "inline", styles = style) { s.onClick() },
                s.range.first,
                s.range.last + 1,
            )
        }
    }
    return InlineParts(annotated, unmatchedLinks, unmatchedCoupons)
}
