package com.chatbyyourside.ui.affinity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chatbyyourside.AppContainer
import com.chatbyyourside.affinity.OwnedGift
import com.chatbyyourside.affinity.formatAffinity
import com.chatbyyourside.data.model.SpecialEventScript
import com.chatbyyourside.ui.glass.GlassSheet
import kotlinx.coroutines.launch

@Composable
fun DailyCheckinDialog(
    container: AppContainer,
    onDismiss: () -> Unit,
) {
    val checkedIn by container.affinityRepository.observeCheckinClaimed().collectAsState(initial = true)
    val scope = rememberCoroutineScope()
    if (!checkedIn) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("每日签到") },
            text = { Text("今日可领取 10,000 金币。现在领取，或稍后从角色页进入每日签到。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.affinityRepository.claimDailyCheckin() }
                    onDismiss()
                }) { Text("领取") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("稍后再说") } },
        )
    }
}

@Composable
fun GiftInventorySheet(
    gifts: List<OwnedGift>,
    onSend: (OwnedGift) -> Unit,
    onPickAttachment: () -> Unit,
    onDismiss: () -> Unit,
) {
    GlassSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("赠送礼物", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onPickAttachment) { Text("添加附件") }
            }
            if (gifts.none { it.inventory.quantity > 0 }) {
                Text("没有可赠送的礼物，请先到每日签到与商店购买。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                gifts.filter { it.inventory.quantity > 0 }.forEach { gift ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        onClick = { onSend(gift) },
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AffinityGiftImage(gift.definition.imagePath, 48.dp)
                            androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(gift.definition.name, style = MaterialTheme.typography.titleSmall)
                                Text("库存 ${gift.inventory.quantity} · +${formatAffinity(gift.definition.affinityGain)} 好感", fontSize = 12.sp)
                            }
                            Icon(Icons.Filled.Redeem, contentDescription = "赠送", modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 自定义角色特殊邂逅编辑对话框（定制需求）：编辑某档位的事件内容（标题/场景/开场/剧情 prompt）。
 * [current] 为当前已存脚本（null = 尚未自定义，编辑框填入保底/目录脚本值便于微调）。
 * 保存 → [onSave]；恢复默认 → [onReset]。
 */
@Composable
fun EventEditDialog(
    characterName: String,
    threshold: Int,
    current: SpecialEventScript?,
    onSave: (title: String, scene: String, opening: String, systemPrompt: String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(current?.title ?: "$characterName · ${threshold}好感邂逅") }
    var scene by remember { mutableStateOf(current?.scene ?: "") }
    var opening by remember { mutableStateOf(current?.opening ?: "") }
    var systemPrompt by remember { mutableStateOf(current?.systemPrompt ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑特殊邂逅 · ${threshold} 好感") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("仅自定义角色可编辑；保存后立即生效，重进事件采用新文案。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                EventEditField("标题", title, { title = it })
                EventEditField("场景", scene, { scene = it })
                EventEditField("开场白", opening, { opening = it })
                EventEditField("剧情 system prompt", systemPrompt, { systemPrompt = it }, minLines = 4)
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && opening.isNotBlank(),
                onClick = { onSave(title, scene, opening, systemPrompt) },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    TextButton(onClick = onReset) { Text("恢复默认", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun EventEditField(label: String, value: String, onChange: (String) -> Unit, minLines: Int = 1) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = Color(0xFFAAB4C1), fontSize = 10.sp)
        TextField(
            value = value,
            onValueChange = onChange,
            minLines = minLines,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFF2F0EA)),
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D1420), RoundedCornerShape(10.dp)),
        )
    }
}
