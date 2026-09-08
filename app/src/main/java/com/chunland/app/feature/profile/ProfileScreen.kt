package com.chunland.app.feature.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.chunland.app.BuildConfig
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.chunland.app.core.AppGraph
import com.chunland.app.core.logging.AiDebugFileLog
import com.chunland.app.core.network.serverOrigin
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.model.UserProfile
import com.chunland.app.feature.ai.AiProviderSettingsSheet
import com.chunland.app.feature.report.ReportSheet
import java.io.File
import kotlinx.coroutines.launch

internal fun identityLabel(identity: String): String = when (identity) {
    "agent" -> "代购人"
    "merchant" -> "商家"
    else -> "消费者"
}

/**
 * 「我的」页（对齐 iOS ProfileView）：头像卡（进账户页）+ 身份切换/开通 + AI 配置 +
 * 购物/代购分区 + 举报与黑名单 + 关于合规 + 退出确认；游客态底部合规链接常驻。
 */
@Composable
fun ProfileScreen(
    graph: AppGraph,
    contentPadding: PaddingValues,
    snackbar: SnackbarHostState,
    onRequireLogin: () -> Unit,
    onOpenOrders: () -> Unit,
    onOpenAddresses: () -> Unit,
    onOpenFollows: () -> Unit,
    onOpenStoreForm: () -> Unit,
    onOpenSettlements: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenBlocks: () -> Unit,
    onOpenAgentSettings: () -> Unit,
) {
    val authState by graph.authManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAiConfig by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var showServerConfig by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var identityMenu by remember { mutableStateOf(false) }

    // 刻意不 remember：Debug 下服务器地址可切，缓存住会让合规页链接一直指向旧服务器。
    // 对齐 iOS —— 那边 docsBase 是计算属性（ProfileView.swift），每次访问现算。
    val openDoc: (String) -> Unit = { path ->
        runCatching { uriHandler.openUri("${serverOrigin()}/$path") }
    }

    LaunchedEffect(authState.isLoggedIn) {
        profile = null
        error = null
        if (authState.isLoggedIn) {
            try {
                profile = graph.authManager.me()
            } catch (e: Exception) {
                error = e.userMessage
            }
        }
    }

    if (!authState.isLoggedIn) {
        // 游客态：CTA 居中 + 底部常驻合规链接（未登录也能访问协议/隐私）
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("登录后查看「我的」", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "登录 / 注册后管理订单、地址与代购设置",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRequireLogin) { Text("登录 / 注册") }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                TextButton(onClick = { openDoc("terms") }) { Text("用户协议", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { openDoc("privacy") }) { Text("隐私政策", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { openDoc("support") }) { Text("帮助", style = MaterialTheme.typography.bodySmall) }
            }
            Text(
                "版本 ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp),
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 头像卡：点卡进「账户」页；身份切换器独立留在右侧（双身份以上才显示）
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = onOpenAccount).padding(14.dp),
            ) {
                Icon(
                    Icons.Filled.AccountCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(52.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    when {
                        profile != null -> {
                            val p = profile!!
                            Text(
                                p.phone ?: p.email ?: "用户 #${p.id}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                        else -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            identityLabel(authState.activeIdentity),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                if (authState.roles.size >= 2) {
                    Box {
                        IconButton(onClick = { identityMenu = true }) {
                            Icon(Icons.Filled.SwapHoriz, contentDescription = "切换身份")
                        }
                        DropdownMenu(expanded = identityMenu, onDismissRequest = { identityMenu = false }) {
                            authState.roles.forEach { role ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            identityLabel(role) +
                                                if (role == authState.activeIdentity) " ✓" else "",
                                        )
                                    },
                                    onClick = {
                                        identityMenu = false
                                        graph.authManager.switchIdentity(role)
                                    },
                                )
                            }
                        }
                    }
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // 开通缺失身份（merchant 只随开店授予，不走 /auth/roles）
        if (!authState.roles.contains("agent")) {
            ProfileRow(Icons.Filled.WorkOutline, "成为代购人") {
                scope.launch {
                    runCatching { graph.authManager.addRole("agent") }
                        .onFailure { snackbar.showSnackbar(it.userMessage) }
                }
            }
        }
        if (!authState.roles.contains("merchant")) {
            ProfileRow(Icons.Filled.Storefront, "我要开店", onClick = onOpenStoreForm)
        }

        // AI 配置（密钥只存本机，对话直连用户 endpoint）。
        // 摘要 = 降级链里首选那一档的名字；运行状态感知收在配置页内。
        var aiSummary by remember { mutableStateOf("") }
        LaunchedEffect(showAiConfig) {
            // 关掉配置页回来要刷新 —— 用户很可能刚改过来源
            if (!showAiConfig) {
                aiSummary = runCatching { graph.aiRuntime.preferredSourceLabel() }.getOrDefault("未配置")
            }
        }
        ProfileRow(Icons.Filled.AutoAwesome, "配置 AI API", value = aiSummary) { showAiConfig = true }


        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // 购物（消费者身份）
        if (authState.activeIdentity == "consumer") {
            ProfileRow(Icons.AutoMirrored.Filled.ReceiptLong, "我的订单", onClick = onOpenOrders)
            ProfileRow(Icons.Filled.Favorite, "收藏与关注", onClick = onOpenFollows)
            ProfileRow(Icons.Filled.Place, "收货地址", onClick = onOpenAddresses)
        }

        // 代购设置（agent 身份）
        if (authState.activeIdentity == "agent") {
            ProfileRow(Icons.Filled.WorkOutline, "接单状态与资料", onClick = onOpenAgentSettings)
            ProfileRow(Icons.Filled.Payments, "待结算 / 收益", onClick = onOpenSettlements)
        }

        // 开发者 —— 仅 Debug 可见（Release 下 canOverride=false，且 ServerConfig 也不读 override）。
        // 位置对齐 iOS ProfileView：代购设置之后、举报之前。
        if (graph.serverConfig.canOverride) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // 行上显示当前地址：改完要跟着变，故用 state 而非直读（iOS 那边靠 @AppStorage 自动刷新）
            var serverUrl by remember { mutableStateOf(graph.serverConfig.baseUrl) }
            LaunchedEffect(showServerConfig) {
                if (!showServerConfig) serverUrl = graph.serverConfig.baseUrl
            }
            ProfileRow(Icons.Filled.Dns, "服务器地址", value = serverUrl) { showServerConfig = true }

            // AI 对话调试日志（完整请求/响应，见 AiDebugFileLog）—— 有内容才显示，
            // 避免分享一个空文件（对齐 iOS）。release 源集里 hasContent() 恒 false。
            // 存 state 而非 body 里直读文件：清空后要立刻消失（iOS 同一个坑，见 ProfileView 注释）。
            var hasAiLog by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { hasAiLog = AiDebugFileLog.hasContent() }
            if (hasAiLog) {
                ProfileRow(Icons.Filled.IosShare, "AI 调试日志") {
                    AiDebugFileLog.file()?.let { shareLogFile(context, it) }
                }
                // 日志只追加不自动清空（见 AiDebugFileLog），手动清空入口
                ProfileRow(Icons.Filled.Delete, "清空 AI 调试日志") {
                    AiDebugFileLog.clear()
                    hasAiLog = false
                }
            }

            // 留证：系统 AI 代理层流异常记录（上游多段生成交错等，命中判定才写）。
            // **按约定路径读文件、不 import 该模块**（对齐 iOS ProfileView 的同一做法）——
            // 开源构建剥离该模块后文件恒不存在，本入口自动隐藏。
            // 这一行出现本身就是「复现了」的信号，故用警示色。
            val anomalyLog = remember { File(context.cacheDir, "shared/ai-stream-anomaly.log") }
            var hasAnomaly by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { hasAnomaly = anomalyLog.exists() && anomalyLog.length() > 0 }
            if (hasAnomaly) {
                ProfileRow(Icons.Filled.WarningAmber, "AI 流异常记录") {
                    shareLogFile(context, anomalyLog)
                }
                ProfileRow(Icons.Filled.Delete, "清空 AI 流异常记录") {
                    runCatching { anomalyLog.delete() }
                    hasAnomaly = false
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // 举报与黑名单 —— 始终可达的投诉举报通道（平台合规）
        ProfileRow(Icons.Filled.Flag, "举报与反馈") { showReport = true }
        ProfileRow(Icons.Filled.PersonOff, "黑名单", onClick = onOpenBlocks)

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // 关于（协议 · 隐私 · 支持 · 版本）
        ProfileRow(null, "用户协议") { openDoc("terms") }
        ProfileRow(null, "隐私政策") { openDoc("privacy") }
        ProfileRow(null, "帮助与支持") { openDoc("support") }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text("版本", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showLogoutConfirm = true }
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Logout,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("退出登录", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
    }

    if (showAiConfig) {
        AiProviderSettingsSheet(graph = graph, onDismiss = { showAiConfig = false })
    }
    if (showReport) {
        ReportSheet(graph = graph, targetType = "general", onDismiss = { showReport = false })
    }
    if (showServerConfig) {
        ServerConfigSheet(graph = graph, onDismiss = { showServerConfig = false })
    }
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("确认退出登录？") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    graph.authManager.logout()
                }) { Text("退出登录", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ProfileRow(
    icon: ImageVector?,
    title: String,
    value: String? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        value?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 把调试日志交给系统分享面板（对齐 iOS 的 UIActivityViewController / ActivityShareView）。
 *
 * 走 FileProvider 传文件 URI 而不是 EXTRA_TEXT 直传内容（iOS 传的是内容字符串）：
 * 日志带完整对话，很快会超过 Binder 事务上限（约 1MB）而抛 TransactionTooLargeException。
 * authority 与暴露面见 AndroidManifest 的 provider 声明与 res/xml/file_paths.xml
 * （只暴露 cache/shared/，日志正落在那里）。
 */
private fun shareLogFile(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "AI 调试日志"))
    }
}
