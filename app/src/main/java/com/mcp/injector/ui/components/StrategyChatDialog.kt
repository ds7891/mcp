package com.mcp.injector.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mcp.injector.ai.ChatTurn
import com.mcp.injector.ui.theme.semanticColors

/**
 * AI 策略对话弹窗（长按工程 / 已注入卡片打开）。
 *
 * 用户在输入框反馈问题 → [onSend] 走 AiPlanner.reviseStrategy 产出策略补丁；
 * 弹窗展示对话与「待应用的策略调整」，确认后 [onApply] 应用补丁并重新注入。
 * 纯展示组件：状态与动作全部由调用方（HomeViewModel）提供，便于复用与测试。
 */
@Composable
fun StrategyChatDialog(
    appName: String,
    contextNote: String,
    turns: List<ChatTurn>,
    input: String,
    thinking: Boolean,
    applying: Boolean,
    changes: List<String>,
    error: String?,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val busy = thinking || applying
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "AI 调试对话",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = appName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = contextNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
                ) {
                    if (turns.isEmpty() && !thinking) {
                        item {
                            Text(
                                text = "把遇到的问题告诉我，例如：打开就闪退 / 拿不到工具 / 端口连不上。\n" +
                                    "我会据此调整注入策略，确认后可一键应用并重新注入。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(turns) { turn -> MessageBubble(turn) }
                    if (thinking) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "AI 分析中…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(6.dp))
                }

                if (changes.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "待应用的策略调整",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            changes.forEach {
                                Text("• $it", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    label = { Text("描述遇到的问题") },
                    enabled = !busy,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") }
                    Spacer(Modifier.weight(1f))
                    if (changes.isNotEmpty()) {
                        val colors = MaterialTheme.semanticColors
                        Text(
                            text = "将重新注入",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.warning,
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onApply, enabled = !busy && changes.isNotEmpty()) {
                            Text(if (applying) "注入中…" else "应用并重新注入")
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(
                        onClick = onSend,
                        enabled = input.isNotBlank() && !busy,
                    ) {
                        Text(if (thinking) "发送中…" else "发送")
                    }
                }
            }
        }
    }
}

/** 单条消息气泡：user 靠右（主色容器），assistant 靠左（浅层级容器）。 */
@Composable
private fun MessageBubble(turn: ChatTurn) {
    val fromUser = turn.role == "user"
    Row(modifier = Modifier.fillMaxWidth()) {
        if (fromUser) Spacer(Modifier.weight(0.15f))
        Surface(
            modifier = Modifier.weight(0.85f),
            shape = RoundedCornerShape(12.dp),
            color = if (fromUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ) {
            Text(
                text = turn.text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(10.dp),
            )
        }
        if (!fromUser) Spacer(Modifier.weight(0.15f))
    }
}