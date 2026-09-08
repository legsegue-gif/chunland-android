package com.chunland.app.feature.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chunland.app.core.AppGraph
import com.chunland.app.core.network.userMessage
import com.chunland.app.feature.profile.ServerConfigSheet
import com.chunland.app.ui.OTP_LENGTH
import com.chunland.app.ui.sanitizeOtp
import com.chunland.app.ui.smsOtpAutofill
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 登录层（对齐 iOS 游客模式的 AuthView sheet：浏览自由，账号动作才唤起）。
 * 登录成功由 AuthManager.state 翻转驱动，宿主负责收起本层。
 */
@Composable
fun LoginScreen(graph: AppGraph, onDismiss: () -> Unit) {
    val vm: LoginViewModel = viewModel { LoginViewModel(graph.authManager) }
    val snackbar = remember { SnackbarHostState() }
    var showReset by remember { mutableStateOf(false) }
    var showServerConfig by remember { mutableStateOf(false) }

    BackHandler(onBack = onDismiss)

    LaunchedEffect(vm.toast) {
        vm.toast?.let {
            snackbar.showSnackbar(it)
            vm.toast = null
        }
    }

    Scaffold(
        topBar = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp),
            ) {
                // 服务器配置入口（角标）—— 仅 Debug；Release 锁定 prod、不暴露切换入口。
                // 位置对齐 iOS AuthView：左上角，与右上角的关闭按钮分列两端。
                // 登录前就要能改 —— 否则只能先拿旧地址登一次再去「我的」里切。
                if (graph.serverConfig.canOverride) {
                    IconButton(onClick = { showServerConfig = true }) {
                        Icon(
                            Icons.Filled.Dns,
                            contentDescription = "服务器地址",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Chunland", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                if (vm.mode == "password") "账号密码登录"
                else "验证码登录，未注册将自动创建账号",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            Row {
                FilterChip(
                    selected = vm.channel == "sms",
                    onClick = { vm.channel = "sms" },
                    label = { Text("手机号") },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = vm.channel == "email",
                    onClick = { vm.channel = "email" },
                    label = { Text("邮箱") },
                )
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = vm.target,
                onValueChange = { vm.target = it },
                label = { Text(if (vm.channel == "sms") "手机号" else "邮箱") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (vm.channel == "sms") KeyboardType.Phone else KeyboardType.Email,
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            if (vm.mode == "password") {
                OutlinedTextField(
                    value = vm.password,
                    onValueChange = { vm.password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = vm.code,
                        // 只留数字、最多 6 位（对齐 iOS AuthView 的 onChange 过滤）——
                        // 本页原先没做，粘贴带空格的验证码会直接提交失败
                        onValueChange = { vm.code = sanitizeOtp(it) },
                        label = { Text("验证码") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f).smsOtpAutofill(),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = vm::sendCode,
                        enabled = !vm.busy && vm.cooldown == 0 && vm.target.isNotBlank(),
                    ) {
                        Text(if (vm.cooldown > 0) "${vm.cooldown}s" else "发送验证码")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = vm::login,
                enabled = !vm.busy && vm.target.isNotBlank() &&
                    (if (vm.mode == "password") vm.password.isNotEmpty() else vm.code.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (vm.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("登录")
                }
            }

            // 次要入口：密码 ↔ 验证码切换 + 忘记密码（对齐 iOS AuthView）
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { vm.mode = if (vm.mode == "otp") "password" else "otp" }) {
                    Text(if (vm.mode == "otp") "密码登录" else "验证码登录")
                }
                Spacer(Modifier.weight(1f))
                if (vm.mode == "password") {
                    TextButton(onClick = { showReset = true }) { Text("忘记密码？") }
                }
            }

        }
    }

    if (showReset) {
        ResetPasswordSheet(graph = graph, onDismiss = { showReset = false })
    }
    if (showServerConfig) {
        ServerConfigSheet(graph = graph, onDismiss = { showServerConfig = false })
    }
}

/** 忘记密码（对齐 iOS 重置密码 sheet）：OTP purpose=reset 验证 + 设新密码，成功即登录。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResetPasswordSheet(graph: AppGraph, onDismiss: () -> Unit) {
    var channel by remember { mutableStateOf("sms") }
    var target by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
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

    val mismatch = confirm.isNotEmpty() && newPassword != confirm
    val canSubmit = target.trim().isNotEmpty() && code.length == OTP_LENGTH &&
        newPassword.length >= 6 && newPassword == confirm

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("重置密码", style = MaterialTheme.typography.titleLarge)
            Row {
                FilterChip(
                    selected = channel == "sms",
                    onClick = { channel = "sms" },
                    label = { Text("手机号") },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = channel == "email",
                    onClick = { channel = "email" },
                    label = { Text("邮箱") },
                )
            }
            OutlinedTextField(
                value = target,
                onValueChange = { target = it },
                label = { Text(if (channel == "sms") "手机号" else "邮箱") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (channel == "sms") KeyboardType.Phone else KeyboardType.Email,
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = sanitizeOtp(it) },
                    label = { Text("验证码") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f).smsOtpAutofill(),
                )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    enabled = cooldown == 0 && !busy && target.trim().isNotEmpty(),
                    onClick = {
                        scope.launch {
                            error = null
                            try {
                                val r = graph.authManager.sendOtp(channel, target.trim(), purpose = "reset")
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
                            // 成功即登录（服务端返回登录态），AppRoot 收起登录层
                            graph.authManager.resetPassword(channel, target.trim(), code, newPassword)
                            onDismiss()
                        } catch (e: Exception) {
                            error = e.userMessage
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "提交中…" else "重置并登录")
            }
        }
    }
}
