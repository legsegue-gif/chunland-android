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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SwapHoriz
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.chunland.app.BuildConfig
import com.chunland.app.core.AppGraph
import com.chunland.app.core.network.serverOrigin
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.model.UserProfile
import com.chunland.app.feature.ai.AiConfigSheet
import com.chunland.app.feature.report.ReportSheet
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
    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAiConfig by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var identityMenu by remember { mutableStateOf(false) }

    val docsBase = remember { serverOrigin() }
    val openDoc: (String) -> Unit = { path ->
        runCatching { uriHandler.openUri("$docsBase/$path") }
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

        // AI 配置（key 只存本机，对话直连用户 endpoint）。
        // 来源摘要：系统提供 / 自定义 · 模型名 / 未配置（运行状态感知收在配置页内，对齐 iOS aiConfigSummary）
        val aiSummary = when {
            graph.aiSettings.systemActive -> "系统提供"
            graph.aiSettings.isConfigured -> "自定义 · ${graph.aiSettings.model}"
            graph.aiSettings.baseUrl.isNotBlank() -> "自定义"
            else -> "未配置"
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
        AiConfigSheet(graph = graph, onDismiss = { showAiConfig = false })
    }
    if (showReport) {
        ReportSheet(graph = graph, targetType = "general", onDismiss = { showReport = false })
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
