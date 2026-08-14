package com.chunland.app.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import com.chunland.app.core.media.CameraPermission
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import com.chunland.app.core.ai.domain.MediaRef
import com.chunland.app.core.ai.storage.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chunland.app.core.ai.session.AiChatSession
import com.chunland.app.core.ai.session.ChatBlock
import com.chunland.app.core.ai.session.ChatDisplayMessage
import com.chunland.app.ui.MarkdownText

/**
 * 聊天主体（新会话模型，对齐 iOS AgentChatView.swift）。
 *
 * 与旧面板的差别不在样子，在底下：消费 [AiChatSession] 的 [ChatDisplayMessage]，
 * 因而拿到了旧实现没有的三样东西 —— 工具块（可回看、可展开）、
 * 系统提示消息（降级/压缩通知）、批量确认。
 *
 * 滚动跟随沿用既有做法：用户上滑看历史就停止自动跟底，滚回底部恢复。
 * 没有它，流式期间每个 token 都会把用户拽回底部。
 */
@Composable
fun AgentChatPanel(
    session: AiChatSession,
    /**
     * 图片输入的落点。
     *
     * 选中即落盘换引用 —— **字节永远不进消息、不进库**。旧实现把图片编成
     * base64 塞进消息里跟着会话一起加载，一张 1MB 照片编码后约 1.37MB。
     */
    media: MediaStore,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    /** 已落盘、待随下一条消息发出的图片 */
    val pending = remember { mutableStateListOf<MediaRef>() }

    /** 三种来源共用的落盘：失败静默跳过单张，一张读不出来不该让整次选图作废 */
    fun ingest(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            uris.take(MAX_PENDING_IMAGES - pending.size).forEach { uri ->
                runCatching { ingestImage(context, media, uri) }.getOrNull()?.let(pending::add)
            }
        }
    }

    // 来源一：相册
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PENDING_IMAGES),
    ) { uris -> ingest(uris) }

    // 来源二：从文件（SAF）。只收 image/* —— 三种来源最终都是一张图，
    // 收其它类型会让下游多出一条无人处理的分支
    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> ingest(uris) }

    // 来源三：拍照。TakePicture 要求先有一个可写的 content uri，
    // 拍完原图直接落在那儿，再当普通图片 ingest
    var cameraTarget by remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok -> if (ok) cameraTarget?.let { ingest(listOf(it)) } }

    fun launchCamera() {
        val uri = runCatching { createCameraOutputUri(context) }.getOrNull() ?: return
        cameraTarget = uri
        takePicture.launch(uri)
    }

    // 「声明才请求」：CAMERA 声明随音视频通话能力进 manifest，未集成时无声明 = 不能请求
    // （请求未声明的权限会被系统直接拒）。判断在 core/media/CameraPermission
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) launchCamera() }

    var showAttachMenu by remember { mutableStateOf(false) }

    // 距底部 2 条以内视为「在底部」→ 保持跟随；上滑更远就停跟
    val autoFollow by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total == 0 || last >= total - 2
        }
    }

    // 内容变化时跟到底：观察最后一条消息的文本长度而不是逐块监听 ——
    // 块是可变对象且数量在变，逐块观察反而更容易漏
    val tick by remember {
        derivedStateOf { session.messages.size to (session.messages.lastOrNull()?.plainText?.length ?: 0) }
    }
    LaunchedEffect(tick) {
        if (autoFollow && session.messages.isNotEmpty()) {
            listState.animateScrollToItem(session.messages.lastIndex)
        }
    }

    session.pendingConfirmation?.let { batch ->
        MutationConfirmSheet(batch) { approved -> session.resolveConfirmation(approved) }
    }

    Column(modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f)) {
            if (session.messages.isEmpty()) {
                WelcomeBlock(session.welcomeText)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(session.messages, key = { it.id }) { message ->
                        MessageRow(message, media)
                    }
                }
            }
        }

        if (pending.isNotEmpty()) {
            PendingMediaStrip(pending, media) { pending.remove(it) }
        }
        HorizontalDivider()
        Composer(
            input = input,
            onInputChange = { input = it },
            isResponding = session.isResponding,
            canAttach = pending.size < MAX_PENDING_IMAGES,
            onAttach = { showAttachMenu = true },
            showAttachMenu = showAttachMenu,
            onDismissAttachMenu = { showAttachMenu = false },
            onPickCamera = {
                showAttachMenu = false
                if (CameraPermission.needsRequest(context)) {
                    cameraPermission.launch(android.Manifest.permission.CAMERA)
                } else {
                    launchCamera()
                }
            },
            onPickAlbum = {
                showAttachMenu = false
                pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onPickFile = {
                showAttachMenu = false
                pickFiles.launch(arrayOf("image/*"))
            },
            canSend = input.isNotBlank() || pending.isNotEmpty(),
            onSend = {
                val text = input
                val attached = pending.toList()
                input = ""
                pending.clear()
                session.send(text, attached)
            },
            onStop = { session.stop() },
            // 键盘弹出会把可视区压矮，此时既没有新消息也没有流式增量 ——
            // 上面那个 LaunchedEffect(tick) 不触发，最后几行就被键盘盖住。
            // imePadding 只推布局，不会把已滚动的列表带到底
            onFocused = {
                scope.launch {
                    kotlinx.coroutines.delay(300) // 等键盘动画结束，否则滚动量算的是旧高度
                    if (session.messages.isNotEmpty()) {
                        listState.animateScrollToItem(session.messages.lastIndex)
                    }
                }
            },
        )
    }
}

/** 同时最多带几张图 —— 与上下文治理的图片老化额度同量级，别让一条消息就撑满 */
private const val MAX_PENDING_IMAGES = 4

/**
 * 读图 → 落盘 → 换引用。
 *
 * 宽高在这里解码后传给存储层：解码是 UI 层的事，存储层不碰图片格式。
 * 用 `inJustDecodeBounds` 只读文件头，不把整张位图读进内存。
 */
private suspend fun ingestImage(context: Context, media: MediaStore, uri: Uri): MediaRef =
    withContext(Dispatchers.IO) {
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取图片" }
            .use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        media.save(
            data = bytes,
            mime = context.contentResolver.getType(uri) ?: "image/jpeg",
            width = bounds.outWidth.takeIf { it > 0 },
            height = bounds.outHeight.takeIf { it > 0 },
        )
    }

/** 待发送图片的缩略条（可逐张移除） */
@Composable
private fun PendingMediaStrip(
    items: List<MediaRef>,
    media: MediaStore,
    onRemove: (MediaRef) -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }) { ref ->
            Box {
                AsyncImage(
                    model = media.fileFor(ref),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                )
                IconButton(
                    onClick = { onRemove(ref) },
                    modifier = Modifier.align(Alignment.TopEnd).size(20.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "移除",
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageRow(message: ChatDisplayMessage, media: MediaStore) {
    when (message.role) {
        ChatDisplayMessage.Role.USER -> UserBubble(message, media)
        ChatDisplayMessage.Role.ASSISTANT -> AssistantBody(message)
        ChatDisplayMessage.Role.SYSTEM -> SystemNote(message)
    }
}

@Composable
private fun UserBubble(message: ChatDisplayMessage, media: MediaStore) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (message.media.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                message.media.forEach { ref ->
                    // 从文件读 —— 消息里只有引用，图片字节不在其中
                    AsyncImage(
                        model = media.fileFor(ref),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)),
                    )
                }
            }
        }
        if (message.plainText.isNotEmpty()) {
            Text(
                text = message.plainText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun AssistantBody(message: ChatDisplayMessage) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        message.blocks.forEach { block ->
            when (block) {
                is ChatBlock.Text ->
                    if (block.block.isThinking) {
                        AgentThinkingBlock(block.block.text)
                    } else if (block.block.text.isNotEmpty()) {
                        MarkdownText(block.block.text)
                    }
                is ChatBlock.Tool -> AgentToolBlock(block.block)
            }
        }

        if (message.isStreaming && message.blocks.isEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                Text("思考中…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        message.error?.let { ErrorNote(it, message.isResumable) }
    }
}

/**
 * 系统提示：降级通知、上下文压缩。
 *
 * 视觉上刻意弱化 —— 它是解释性的，不该和对话内容抢注意力。
 */
@Composable
private fun SystemNote(message: ChatDisplayMessage) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Info, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.outline)
        Text(
            text = message.plainText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun ErrorNote(text: String, resumable: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (resumable) Icons.Default.Refresh else Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (resumable) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (resumable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun WelcomeBlock(text: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        // 欢迎语是 View 层装饰 —— 绝不作为 assistant 消息进历史，
        // 否则模型会把它当成「自己说过的话」照抄（复读根因）
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
/**
 * 输入区：文本框在上、工具行在下，整体包在一个圆角容器里（对齐 iOS AgentChatView.composerRow）。
 *
 * 按钮**在框内**而不是左右各挂一个 —— 左中右三段平级时，文本多行展开会把两侧按钮
 * 顶得忽上忽下，且横向空间被按钮吃掉。容器内上下分行让文本区永远占满宽度、工具行位置恒定。
 */
private fun Composer(
    input: String,
    onInputChange: (String) -> Unit,
    isResponding: Boolean,
    canAttach: Boolean,
    onAttach: () -> Unit,
    showAttachMenu: Boolean,
    onDismissAttachMenu: () -> Unit,
    onPickCamera: () -> Unit,
    onPickAlbum: () -> Unit,
    onPickFile: () -> Unit,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onFocused: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                enabled = !isResponding,
                maxLines = 5,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .onFocusChanged { if (it.isFocused) onFocused() },
                decorationBox = { inner ->
                    if (input.isEmpty()) {
                        Text(
                            "说点什么…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                },
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = onAttach, enabled = canAttach && !isResponding) {
                        Icon(Icons.Default.Add, contentDescription = "添加")
                    }
                    // 三种来源最终都落成同一种东西 —— 一张图的 MediaRef，差别只在从哪儿拿字节
                    DropdownMenu(expanded = showAttachMenu, onDismissRequest = onDismissAttachMenu) {
                        DropdownMenuItem(
                            text = { Text("拍照") },
                            onClick = onPickCamera,
                            leadingIcon = { Icon(Icons.Default.PhotoCamera, null) },
                        )
                        DropdownMenuItem(
                            text = { Text("从相册选择") },
                            onClick = onPickAlbum,
                            leadingIcon = { Icon(Icons.Default.Image, null) },
                        )
                        DropdownMenuItem(
                            text = { Text("从文件选择") },
                            onClick = onPickFile,
                            leadingIcon = { Icon(Icons.Default.Description, null) },
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (isResponding) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = "停止")
                    }
                } else {
                    IconButton(onClick = onSend, enabled = canSend) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "发送")
                    }
                }
            }
        }
    }
}

/** 拍照落点：cache 下的临时文件，经 FileProvider 暴露成可写 content uri */
private fun createCameraOutputUri(context: Context): Uri {
    val dir = java.io.File(context.cacheDir, "ai-camera").apply { mkdirs() }
    val file = java.io.File(dir, "capture_${System.currentTimeMillis()}.jpg")
    return androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file,
    )
}
