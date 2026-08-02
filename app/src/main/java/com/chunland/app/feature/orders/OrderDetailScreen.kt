package com.chunland.app.feature.orders

import android.app.Activity
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chunland.app.core.AppGraph
import com.chunland.app.core.ai.AiContext
import com.chunland.app.core.network.apiCall
import com.chunland.app.core.network.apiCallUnit
import com.chunland.app.core.network.userMessage
import com.chunland.app.core.pay.PayBridgeManager
import com.chunland.app.feature.ai.ScopedAiSheet
import com.chunland.app.feature.common.BitmapViewerDialog
import com.chunland.app.feature.common.decodeSampledBitmap
import com.chunland.app.feature.report.ReportSheet
import com.chunland.app.data.model.AdjustmentDetail
import com.chunland.app.data.model.AdjustmentItem
import com.chunland.app.data.model.DecideAdjustmentRequest
import com.chunland.app.data.model.OrderAction
import com.chunland.app.data.model.OrderAdjustment
import com.chunland.app.data.model.OrderDetail
import com.chunland.app.data.model.OrderEvidence
import com.chunland.app.data.model.ProposeAdjustmentRequest
import com.chunland.app.data.model.UpdateOrderStatusRequest
import com.chunland.app.data.model.adjustmentStatusLabel
import com.chunland.app.data.model.orderStatusLabel
import com.chunland.app.ui.formatPrice
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

@Composable
fun OrderDetailScreen(
    graph: AppGraph,
    orderId: Int,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var detail by remember { mutableStateOf<OrderDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var acting by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    // 采购凭证
    val authState by graph.authManager.state.collectAsState()
    var evidence by remember { mutableStateOf<List<OrderEvidence>>(emptyList()) }
    val evidenceBitmaps = remember { mutableStateMapOf<Int, Bitmap>() }
    var uploading by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 缺货改单
    var adjustments by remember { mutableStateOf<List<OrderAdjustment>>(emptyList()) }
    var showProposeSheet by remember { mutableStateOf(false) }
    var proposing by remember { mutableStateOf(false) }
    var deciding by remember { mutableStateOf(false) }

    // 非阻塞 toast：showSnackbar 是 suspend 到消失（~4s）才返回，串在动作链里会把
    // reload/finally 拖住 4 秒（按钮停留旧状态）—— 一律 fire-and-forget
    fun toast(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    fun decide(adj: OrderAdjustment, accept: Boolean) {
        if (deciding) return
        scope.launch {
            deciding = true
            try {
                apiCallUnit {
                    graph.orderApi.decideAdjustment(orderId, adj.id, DecideAdjustmentRequest(accept))
                }
                reloadKey++
                toast(if (accept) "已接受改单" else "已拒绝改单")
            } catch (e: Exception) {
                toast(e.userMessage)
            } finally {
                deciding = false
            }
        }
    }

    fun propose(items: List<AdjustmentItem>) {
        if (proposing) return
        scope.launch {
            proposing = true
            try {
                apiCall {
                    graph.orderApi.proposeAdjustment(
                        orderId,
                        ProposeAdjustmentRequest(kind = "out_of_stock", detail = AdjustmentDetail(items)),
                    )
                }
                showProposeSheet = false
                reloadKey++
                toast("改单已提交，等待买家确认")
            } catch (e: Exception) {
                toast(e.userMessage)
            } finally {
                proposing = false
            }
        }
    }

    LaunchedEffect(orderId, reloadKey) {
        try {
            detail = apiCall { graph.orderApi.detail(orderId) }
            error = null
        } catch (e: Exception) {
            // 已有内容时的 reload 失败走 toast，不把整页替换成错误态
            if (detail == null) error = e.userMessage else toast("刷新失败：${e.userMessage}")
        }
        // 凭证/改单列表失败不打断详情（参与方之外服务端会拒，静默即可）
        runCatching { evidence = apiCall { graph.orderApi.evidenceList(orderId) } }
        runCatching { adjustments = apiCall { graph.orderApi.adjustments(orderId) } }
    }

    // 凭证图必须经鉴权代理按 id 拉 bytes（凭证桶非公开，绝不当公开 URL 用）
    LaunchedEffect(evidence) {
        evidence.forEach { ev ->
            if (ev.id in evidenceBitmaps) return@forEach
            launch {
                runCatching {
                    val bytes = withContext(Dispatchers.IO) {
                        graph.orderApi.evidenceImage(orderId, ev.id).use { it.bytes() }
                    }
                    // 12MP 小票全尺寸解码 ≈ 48MB/张 —— 降采样到预览够用的尺寸
                    withContext(Dispatchers.IO) { decodeSampledBitmap(bytes, maxSide = 2048) }
                        ?.let { evidenceBitmaps[ev.id] = it }
                }
            }
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            uploading = true
            try {
                val (bytes, mime) = withContext(Dispatchers.IO) {
                    val b = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取图片" }
                        .use { it.readBytes() }
                    b to (context.contentResolver.getType(uri) ?: "image/jpeg")
                }
                apiCall {
                    graph.orderApi.uploadEvidence(orderId, "receipt", bytes.toRequestBody(mime.toMediaType()))
                }
                reloadKey++
                toast("凭证已上传")
            } catch (e: Exception) {
                toast(e.userMessage)
            } finally {
                uploading = false
            }
        }
    }

    // cancel/complete/refund 不可逆（取消放单、完成触发结算、退款转 REFUNDED），
    // 点击先弹确认（对齐 iOS 三个 confirmationDialog）；其余动作直接执行
    var confirmTarget by remember { mutableStateOf<OrderAction?>(null) }

    // 动作只来自服务端 availableActions —— 端上不硬编码状态转换。
    // pay/claim/refund 走专用端点，其余走通用 PATCH status（对齐 iOS handle(action) 分发）。
    fun perform(action: OrderAction) {
        if (acting) return
        scope.launch {
            acting = true
            try {
                when (action.action) {
                    "claim" -> {
                        apiCall { graph.orderApi.claim(orderId) }
                        toast("接单成功")
                    }
                    "refund" -> {
                        apiCall { graph.paymentApi.refund(orderId) }
                        toast("已退款")
                    }
                    "pay" -> {
                        val result = apiCall { graph.paymentApi.create(orderId) }
                        val orderStr = result.orderStr
                        when {
                            result.paid -> toast("支付成功")
                            orderStr != null -> {
                                // 唤起支付宝（PayBridge 未注册 = 未接入 SDK，如实降级）。
                                // 端内回调只用于触发 reload —— 真正订单状态以服务端 notify 为准
                                val activity = context as? Activity
                                val outcome = activity?.let { PayBridgeManager.pay(it, orderStr) }
                                when {
                                    activity == null || outcome == null ->
                                        toast("当前版本不支持支付宝支付")
                                    outcome.first -> toast("支付完成，等待服务端确认")
                                    outcome.second == "6001" -> toast("已取消支付")
                                    else -> toast("支付未完成（${outcome.second ?: "未知状态"}）")
                                }
                            }
                            else -> toast("支付暂不可用")
                        }
                    }
                    else -> apiCallUnit {
                        graph.orderApi.updateStatus(orderId, UpdateOrderStatusRequest(action.toStatus))
                    }
                }
                reloadKey++
            } catch (e: Exception) {
                toast(e.userMessage)
            } finally {
                acting = false
            }
        }
    }

    var showAi by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    Scaffold(
        topBar = {
            Surface {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text("订单详情", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    if (detail != null) {
                        // ✨ scoped AI（对齐 iOS AskAIButton .order 上下文）
                        IconButton(onClick = { showAi = true }) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = "AI 助手")
                        }
                    }
                    if (detail?.agentId != null) {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("举报代购人") },
                                onClick = {
                                    menuOpen = false
                                    showReport = true
                                },
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val d = detail
        when {
            d == null && error == null -> Box(
                Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            error != null -> Box(
                Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { reloadKey++ }) { Text("重试") }
                }
            }
            else -> {
                val order = d!!
                val isMyAgentOrder = order.agentId != null &&
                    order.agentId.toString() == authState.userId
                val canUpload = isMyAgentOrder &&
                    order.status in setOf("PAID", "PURCHASING", "DELIVERING")
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DetailBody(order, acting) { action ->
                        when (action.action) {
                            "cancel", "complete", "refund" -> confirmTarget = action
                            else -> perform(action)
                        }
                    }
                    EvidenceSection(
                        evidence = evidence,
                        bitmaps = evidenceBitmaps,
                        canUpload = canUpload,
                        uploading = uploading,
                        onUpload = {
                            pickImage.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onPreview = { previewBitmap = it },
                    )
                    AdjustmentsSection(
                        order = order,
                        adjustments = adjustments,
                        isConsumer = order.consumerId.toString() == authState.userId,
                        canPropose = isMyAgentOrder && order.status == "PURCHASING",
                        deciding = deciding,
                        onPropose = { showProposeSheet = true },
                        onDecide = ::decide,
                    )
                    Spacer(Modifier.height(24.dp))
                }
                if (showProposeSheet) {
                    ProposeAdjustmentSheet(
                        order = order,
                        submitting = proposing,
                        onDismiss = { showProposeSheet = false },
                        onSubmit = ::propose,
                    )
                }
            }
        }
    }

    confirmTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = { Text("${target.label}？") },
            text = {
                Text(
                    when (target.action) {
                        "cancel" -> "取消后订单不可恢复。"
                        "refund" -> "退款后订单转为已退款，货款将原路退回买家，不可恢复。"
                        else -> "确认收货完成后订单进入结算，不可再发起售后。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmTarget = null
                    perform(target)
                }) { Text(target.label) }
            },
            dismissButton = {
                TextButton(onClick = { confirmTarget = null }) { Text("再想想") }
            },
        )
    }

    if (showAi) {
        detail?.let { d ->
            ScopedAiSheet(
                graph = graph,
                context = AiContext.order(orderId, d.orderNumber),
                onDismiss = { showAi = false },
            )
        }
    }
    if (showReport) {
        detail?.agentId?.let { agentId ->
            ReportSheet(
                graph = graph,
                targetType = "agent",
                targetKey = agentId.toString(),
                onDismiss = { showReport = false },
            )
        }
    }
    previewBitmap?.let { bmp ->
        BitmapViewerDialog(bitmap = bmp, onDismiss = { previewBitmap = null })
    }
}

@Composable
private fun DetailBody(d: OrderDetail, acting: Boolean, onAction: (OrderAction) -> Unit) {
    // 状态 + 单号
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                orderStatusLabel(d.status),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                d.orderNumber,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // 商品（下单冻结的快照）
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            d.items.forEach { item ->
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(item.productSnapshot.name, style = MaterialTheme.typography.bodyMedium)
                        item.selectedSize?.let {
                            Text("规格：$it", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("¥${formatPrice(item.unitPrice)} × ${item.quantity}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("¥${formatPrice(item.totalPrice)}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            HorizontalDivider()
            FeeLine("商品合计", d.itemsTotal)
            FeeLine("平台服务费", d.platformFee)
            FeeLine("代购费", d.agentFee)
            FeeLine("订单总额", d.totalAmount, bold = true)
        }
    }

    // 收货地址快照
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Text(d.deliveryAddress.name, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(d.deliveryAddress.phone, style = MaterialTheme.typography.bodyMedium)
            }
            Text(d.deliveryAddress.address, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    // 服务端下发的可执行动作
    val actions = d.availableActions.orEmpty()
    if (actions.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { action ->
                if (action.style == "destructive") {
                    OutlinedButton(
                        onClick = { onAction(action) },
                        enabled = !acting,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.weight(1f),
                    ) { Text(action.label) }
                } else {
                    Button(
                        onClick = { onAction(action) },
                        enabled = !acting,
                        modifier = Modifier.weight(1f),
                    ) { Text(action.label) }
                }
            }
        }
    }
}

/** 采购凭证：agent 在 PAID/PURCHASING/DELIVERING 可传小票（startDeliver 的服务端 guard 依据） */
@Composable
private fun EvidenceSection(
    evidence: List<OrderEvidence>,
    bitmaps: Map<Int, Bitmap>,
    canUpload: Boolean,
    uploading: Boolean,
    onUpload: () -> Unit,
    onPreview: (Bitmap) -> Unit,
) {
    if (evidence.isEmpty() && !canUpload) return
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("采购凭证", style = MaterialTheme.typography.titleSmall)
            if (evidence.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    evidence.forEach { ev ->
                        val bmp = bitmaps[ev.id]
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "凭证 ${ev.id}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onPreview(bmp) },
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            } else {
                Text(
                    "还未上传采购小票（开始配送前必须上传）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canUpload) {
                OutlinedButton(onClick = onUpload, enabled = !uploading) {
                    Text(if (uploading) "上传中…" else "上传采购小票")
                }
            }
        }
    }
}

/** 缺货改单：agent 在 PURCHASING 可发起（MVP 只下调）；consumer 对 PENDING 提案接受/拒绝 */
@Composable
private fun AdjustmentsSection(
    order: OrderDetail,
    adjustments: List<OrderAdjustment>,
    isConsumer: Boolean,
    canPropose: Boolean,
    deciding: Boolean,
    onPropose: () -> Unit,
    onDecide: (OrderAdjustment, Boolean) -> Unit,
) {
    if (adjustments.isEmpty() && !canPropose) return
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("缺货改单", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                if (canPropose) {
                    TextButton(onClick = onPropose) { Text("发起改单") }
                }
            }
            adjustments.forEach { adj ->
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            adjustmentStatusLabel(adj.status),
                            style = MaterialTheme.typography.labelMedium,
                            color = when (adj.status) {
                                "ACCEPTED" -> MaterialTheme.colorScheme.primary
                                "REJECTED" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.tertiary
                            },
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            adj.createdAt.take(10),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    adj.detail.items.forEach { item ->
                        val name = order.items.firstOrNull { it.id == item.orderItemId }
                            ?.productSnapshot?.name ?: "条目 #${item.orderItemId}"
                        val change = when (item.action) {
                            "remove" -> "缺货移除"
                            "reduce_qty" -> "数量改为 ${item.newQuantity}"
                            else -> item.action
                        }
                        Text("· $name：$change", style = MaterialTheme.typography.bodySmall)
                    }
                    if (adj.amountDelta != 0.0) {
                        val sign = if (adj.amountDelta < 0) "-" else "+"
                        Text(
                            "金额 $sign¥${formatPrice(abs(adj.amountDelta))}" +
                                if (adj.amountDelta < 0) "（接受后按此退款）" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    adj.note?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (adj.status == "PENDING" && isConsumer) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onDecide(adj, true) },
                                enabled = !deciding,
                                modifier = Modifier.weight(1f),
                            ) { Text("接受") }
                            OutlinedButton(
                                onClick = { onDecide(adj, false) },
                                enabled = !deciding,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                                modifier = Modifier.weight(1f),
                            ) { Text("拒绝") }
                        }
                    }
                }
            }
        }
    }
}

/** 提案表单：每个条目 保留/缺货移除/减量（qty>1 才可减，减到 1..qty-1） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProposeAdjustmentSheet(
    order: OrderDetail,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (List<AdjustmentItem>) -> Unit,
) {
    // orderItemId -> (action, newQuantity)；不在 map 中 = 保留
    val choices = remember { mutableStateMapOf<Int, Pair<String, Int>>() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("缺货改单（仅支持下调，买家确认后生效）", style = MaterialTheme.typography.titleMedium)
            order.items.forEach { item ->
                val choice = choices[item.id]
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${item.productSnapshot.name} ×${item.quantity}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = choice == null,
                            onClick = { choices.remove(item.id) },
                            label = { Text("保留") },
                        )
                        FilterChip(
                            selected = choice?.first == "remove",
                            onClick = { choices[item.id] = "remove" to 0 },
                            label = { Text("缺货移除") },
                        )
                        if (item.quantity > 1) {
                            FilterChip(
                                selected = choice?.first == "reduce_qty",
                                onClick = { choices[item.id] = "reduce_qty" to (item.quantity - 1) },
                                label = { Text("减量") },
                            )
                        }
                    }
                    if (choice?.first == "reduce_qty") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("改为", style = MaterialTheme.typography.bodySmall)
                            IconButton(
                                onClick = { choices[item.id] = "reduce_qty" to (choice.second - 1) },
                                enabled = choice.second > 1,
                            ) { Text("−") }
                            Text("${choice.second}", style = MaterialTheme.typography.titleSmall)
                            IconButton(
                                onClick = { choices[item.id] = "reduce_qty" to (choice.second + 1) },
                                enabled = choice.second < item.quantity - 1,
                            ) { Text("+") }
                        }
                    }
                }
            }
            Button(
                onClick = {
                    onSubmit(choices.map { (orderItemId, c) ->
                        AdjustmentItem(
                            orderItemId = orderItemId,
                            action = c.first,
                            newQuantity = if (c.first == "reduce_qty") c.second else null,
                        )
                    })
                },
                enabled = choices.isNotEmpty() && !submitting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (submitting) "提交中…" else "提交改单") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FeeLine(label: String, amount: Double, bold: Boolean = false) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(
            "¥${formatPrice(amount)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = if (bold) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
