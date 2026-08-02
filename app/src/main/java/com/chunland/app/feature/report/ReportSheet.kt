package com.chunland.app.feature.report

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chunland.app.core.AppGraph
import com.chunland.app.core.network.apiCallUnit
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.model.CreateReportRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 与服务端 reason_code / iOS ReportReason 对齐
private val reportReasons = listOf(
    "illegal" to "违法违禁",
    "fraud" to "虚假欺诈",
    "porn" to "色情低俗",
    "infringement" to "侵权",
    "harassment" to "骚扰辱骂",
    "other" to "其他",
)

/**
 * 可复用举报面板（对齐 iOS ReportSheet）：理由单选 + 选填说明 + 提交，
 * 成功反馈后自动关闭，自包含不依赖父级 toast。调用方负责登录前置（requireLogin）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(
    graph: AppGraph,
    targetType: String,
    targetKey: String? = null,
    snapshot: String? = null,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf("illegal") }
    var detail by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (submitted) {
            LaunchedEffect(Unit) {
                delay(1400)
                onDismiss()
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(52.dp),
                )
                Text("举报已提交", style = MaterialTheme.typography.titleMedium)
                Text(
                    "感谢反馈，我们会尽快核实处理",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@ModalBottomSheet
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp),
        ) {
            Text("举报", style = MaterialTheme.typography.titleLarge)
            Text(
                "举报理由",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            reportReasons.forEach { (code, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !submitting) { reason = code },
                ) {
                    RadioButton(selected = reason == code, onClick = { reason = code }, enabled = !submitting)
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedTextField(
                value = detail,
                onValueChange = { detail = it },
                label = { Text("补充说明（选填）") },
                minLines = 2,
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    scope.launch {
                        submitting = true
                        error = null
                        try {
                            apiCallUnit {
                                graph.reportApi.create(
                                    CreateReportRequest(
                                        targetType = targetType,
                                        reasonCode = reason,
                                        targetKey = targetKey,
                                        detail = detail.trim().ifEmpty { null },
                                        snapshot = snapshot,
                                    ),
                                )
                            }
                            submitted = true
                        } catch (e: Exception) {
                            error = e.userMessage
                        } finally {
                            submitting = false
                        }
                    }
                },
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (submitting) "提交中…" else "提交")
            }
        }
    }
}
