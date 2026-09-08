package com.chunland.app.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chunland.app.core.AppGraph
import com.chunland.app.core.network.ServerConfig

/**
 * 服务器地址配置（对齐 iOS `ServerConfigSheet.swift`）：保存 / 恢复默认 / 取消三个动作一一对应。
 *
 * 两个入口都仅 Debug 可达（`ServerConfig.canOverride` 门控，等价 iOS 的 `#if DEBUG`）：
 * 登录页左上角标（对齐 `AuthView.swift:63`）与「我的 → 开发者」（对齐 `ProfileView.swift:132`）。
 *
 * 保存后不登出，只丢内存缓存让各页重拉，理由见 [AppGraph.resetForServerChange]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerConfigSheet(
    graph: AppGraph,
    onDismiss: () -> Unit,
) {
    val config = graph.serverConfig
    var text by remember { mutableStateOf(config.baseUrl) }
    var invalid by remember { mutableStateOf(false) }

    // 保存与恢复默认共用：写配置 → 丢缓存 → 关面板
    val apply: (String?) -> Unit = { value ->
        config.override = value
        graph.resetForServerChange()
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("服务器地址", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    invalid = false
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = invalid,
                placeholder = { Text("http://10.0.2.2:3000/api/v1") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                supportingText = {
                    Text(
                        if (invalid) {
                            "地址无法解析，需形如 http://10.0.2.2:3000/api/v1"
                        } else {
                            // 10.0.2.2 是模拟器访问宿主机 localhost 的固定地址；真机要填局域网 IP
                            "模拟器填 10.0.2.2，真机填开发机局域网地址。默认 ${config.defaultBaseUrl}"
                        },
                    )
                },
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    val normalized = ServerConfig.normalize(text)
                    if (normalized == null) invalid = true else apply(normalized)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存") }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // 清除覆盖，回到随构建走的默认，并立即生效
                TextButton(onClick = { apply(null) }) { Text("恢复默认") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    }
}
