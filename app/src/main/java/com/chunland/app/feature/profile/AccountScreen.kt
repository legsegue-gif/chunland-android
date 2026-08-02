package com.chunland.app.feature.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.chunland.app.core.AppGraph
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.model.UserProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 账户页（对齐 iOS AccountView）：手机号/邮箱绑定 · 设置/修改密码 · 注销账号。
 * 注销成功 AuthManager 自动登出 → UI 回游客态，本页由调用方弹回。
 */
@Composable
fun AccountScreen(
    graph: AppGraph,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var bindChannel by remember { mutableStateOf<String?>(null) }   // "sms" | "email" | null
    var showPassword by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reloadKey) {
        try {
            profile = graph.authManager.me()
            error = null
        } catch (e: Exception) {
            error = e.userMessage
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Surface {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text("账户", style = MaterialTheme.typography.titleMedium)
                }
            }
        },
    ) { padding ->
        val p = profile
        when {
            p != null -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ContactRow("手机号", p.phone) { bindChannel = "sms" }
                HorizontalDivider()
                ContactRow("邮箱", p.email) { bindChannel = "email" }
                HorizontalDivider()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Text("密码", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showPassword = true }) {
                        Text(if (p.hasPassword) "修改密码" else "设置密码")
                    }
                }
                HorizontalDivider()

                Spacer(Modifier.padding(12.dp))
                TextButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("注销账号", color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "注销后账号将无法登录，手机号 / 邮箱等个人信息会被清除，且不可恢复。订单等交易记录依法保留。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            error != null -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }
            else -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
        }
    }

    bindChannel?.let { channel ->
        BindContactSheet(
            graph = graph,
            channel = channel,
            onDismiss = { bindChannel = null },
            onBound = {
                bindChannel = null
                reloadKey++
                scope.launch { snackbar.showSnackbar("绑定成功") }
            },
        )
    }
    if (showPassword) {
        profile?.let { p ->
            ChangePasswordSheet(
                graph = graph,
                hasPassword = p.hasPassword,
                onDismiss = { showPassword = false },
                onChanged = {
                    showPassword = false
                    reloadKey++
                    scope.launch { snackbar.showSnackbar(if (p.hasPassword) "密码已修改" else "密码已设置") }
                },
            )
        }
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认注销账号？") },
            text = { Text("此操作不可恢复：账号将无法登录，手机号 / 邮箱等个人信息会被清除。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        try {
                            graph.authManager.deleteAccount()   // 成功即登出，AppRoot 回游客态
                            onBack()
                        } catch (e: Exception) {
                            snackbar.showSnackbar(e.userMessage)
                        }
                    }
                }) { Text("注销账号", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ContactRow(title: String, value: String?, onBind: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (!value.isNullOrEmpty()) {
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            TextButton(onClick = onBind) { Text("绑定") }
        }
    }
}

/** 绑定手机号/邮箱（OTP purpose=bind，对齐 iOS BindContactView）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BindContactSheet(
    graph: AppGraph,
    channel: String,
    onDismiss: () -> Unit,
    onBound: () -> Unit,
) {
    val isPhone = channel == "sms"
    var target by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var cooldown by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1000)
            cooldown--
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (isPhone) "绑定手机号" else "绑定邮箱", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = target,
                onValueChange = { target = it },
                label = { Text(if (isPhone) "手机号" else "邮箱") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                    label = { Text("验证码") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = cooldown == 0 && !busy && target.trim().isNotEmpty(),
                    onClick = {
                        scope.launch {
                            error = null
                            try {
                                val r = graph.authManager.sendOtp(channel, target.trim(), purpose = "bind")
                                cooldown = r.cooldown
                            } catch (e: Exception) {
                                error = e.userMessage
                            }
                        }
                    },
                ) {
                    Text(if (cooldown > 0) "${cooldown}s 后重发" else "获取验证码")
                }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                enabled = !busy && target.trim().isNotEmpty() && code.length == 6,
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            graph.authManager.bindContact(channel, target.trim(), code)
                            onBound()
                        } catch (e: Exception) {
                            error = e.userMessage
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "绑定中…" else "绑定")
            }
        }
    }
}

/** 设置/修改密码（对齐 iOS ChangePasswordView）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangePasswordSheet(
    graph: AppGraph,
    hasPassword: Boolean,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val mismatch = confirm.isNotEmpty() && newPassword != confirm
    val canSubmit = newPassword.length >= 6 && newPassword == confirm &&
        (!hasPassword || oldPassword.isNotEmpty())

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (hasPassword) "修改密码" else "设置密码", style = MaterialTheme.typography.titleLarge)
            if (hasPassword) {
                OutlinedTextField(
                    value = oldPassword,
                    onValueChange = { oldPassword = it },
                    label = { Text("当前密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it },
                label = { Text("新密码（至少 6 位）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it },
                label = { Text("确认新密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                isError = mismatch,
                supportingText = if (mismatch) {
                    { Text("两次输入不一致") }
                } else if (!hasPassword) {
                    { Text("设置后可用手机号 / 邮箱 + 密码登录") }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                enabled = canSubmit && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            graph.authManager.setPassword(
                                oldPassword = if (hasPassword) oldPassword else null,
                                newPassword = newPassword,
                            )
                            onChanged()
                        } catch (e: Exception) {
                            error = e.userMessage
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "保存中…" else "保存")
            }
        }
    }
}
