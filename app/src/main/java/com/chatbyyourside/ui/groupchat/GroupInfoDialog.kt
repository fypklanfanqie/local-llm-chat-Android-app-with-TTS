package com.chatbyyourside.ui.groupchat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import android.widget.Toast
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.collectAsState
import com.chatbyyourside.config.AppConfig
import com.chatbyyourside.ui.pickers.CharacterMultiPicker
import com.chatbyyourside.AppContainer
import com.chatbyyourside.data.model.Conversation
import com.chatbyyourside.ui.theme.GlassShapes
import com.chatbyyourside.ui.glass.frostedGlass
import com.chatbyyourside.util.GroupCoverStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 群信息编辑弹窗：改群名称 + 群封面（选择/更换/清除）+ **群成员随时增删**，可选「删除群聊」。
 * 保存时一并落库（名称+封面+成员）；改动后回调 [onSaved]。
 */
@Composable
fun GroupInfoDialog(
    group: Conversation,
    container: AppContainer,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val characters by container.characterRepository.characters.collectAsState(initial = emptyList())

    var name by remember { mutableStateOf(group.title) }
    var coverUri by remember { mutableStateOf(group.coverImagePath ?: "") }
    var pendingCover by remember { mutableStateOf<Uri?>(null) }
    var coverCleared by remember { mutableStateOf(false) }
    var coverError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    // 成员草稿：与名称/封面一起在「保存」时落库；数量约束 2..MAX（移出到 2 人即止）
    var memberIdsDraft by remember { mutableStateOf(group.memberIds) }
    var showMemberPicker by remember { mutableStateOf(false) }
    var memberHint by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingCover = uri
            coverCleared = false
            coverError = null
        }
    }

    val isDark = com.chatbyyourside.ui.theme.LocalDarkTheme.current
    val textColor = if (isDark) androidx.compose.ui.graphics.Color(0xFFE8E4E0) else androidx.compose.ui.graphics.Color(0xFF161616)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .frostedGlass(GlassShapes.card, borderWidth = 1.dp, blurRadius = 20.dp)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("群聊信息", color = scheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)

            Text("群封面", color = scheme.onSurfaceVariant, fontSize = 11.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val preview: Any? = when {
                    pendingCover != null -> pendingCover
                    coverCleared -> null
                    else -> coverUri.takeIf { it.isNotBlank() }
                }
                if (preview != null) {
                    Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp))) {
                        AsyncImage(
                            model = preview,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(scheme.primary.copy(alpha = 0.12f))
                        .border(1.dp, scheme.primary.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .clickable { picker.launch(arrayOf("image/*")) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (preview == null) "＋ 封面" else "更换", color = scheme.primary, fontSize = 11.sp)
                }
                if (preview != null) {
                    TextButton(onClick = {
                        pendingCover = null
                        coverCleared = true
                        coverError = null
                    }) { Text("清除", color = scheme.error, fontSize = 12.sp) }
                }
            }
            coverError?.let { Text(it, color = scheme.error, fontSize = 10.sp) }

            Text("群名称", color = scheme.onSurfaceVariant, fontSize = 11.sp)
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = TextStyle(color = textColor, fontSize = 14.sp),
                cursorBrush = SolidColor(scheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.surface.copy(alpha = 0.6f))
                    .padding(12.dp),
            )

            // ===== 群成员（随时加入 / 移出角色）=====
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "群成员（${memberIdsDraft.size}/${AppConfig.GroupChat.MAX_MEMBERS}）",
                    color = scheme.onSurfaceVariant, fontSize = 11.sp,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showMemberPicker = true }) {
                    Text("增删成员", color = scheme.primary, fontSize = 12.sp)
                }
            }
            // 固定高度列表：成员多时不把弹窗撑爆（弹窗整体不滚动）
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 132.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(memberIdsDraft, key = { it }) { id ->
                    val label = characters.firstOrNull { it.id == id }?.name ?: "已注销角色"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = textColor, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(
                            enabled = memberIdsDraft.size > MIN_GROUP_MEMBERS,
                            onClick = { memberIdsDraft = memberIdsDraft - id },
                        ) { Text("移出", color = scheme.error, fontSize = 12.sp) }
                    }
                }
            }
            if (memberIdsDraft.size <= MIN_GROUP_MEMBERS) {
                Text(
                    "群聊至少保留 $MIN_GROUP_MEMBERS 名成员（要单人聊天请回角色页单聊）",
                    color = scheme.onSurfaceVariant, fontSize = 10.sp,
                )
            }
            memberHint?.let { Text(it, color = scheme.error, fontSize = 10.sp) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onDeleted != null) {
                    TextButton(onClick = { deleteConfirm = true }) {
                        Text("删除群聊", color = scheme.error, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消", color = scheme.onSurfaceVariant, fontSize = 13.sp) }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = {
                        scope.launch {
                            saving = true
                            var finalCover = if (coverCleared) null else coverUri.takeIf { it.isNotBlank() }
                            val chosen = pendingCover
                            if (chosen != null) {
                                val savedCover = withContext(Dispatchers.IO) { GroupCoverStore.save(context, chosen) }
                                if (savedCover == null) {
                                    coverError = "封面保存失败"
                                    saving = false
                                    return@launch
                                }
                                if (coverUri.isNotBlank()) {
                                    withContext(Dispatchers.IO) { GroupCoverStore.delete(context, coverUri) }
                                }
                                finalCover = savedCover
                            }
                            container.groupChatRepository.setGroupName(group.id, name.trim())
                            container.groupChatRepository.setGroupCover(group.id, finalCover)
                            // 成员一并落库（与名称/封面同一「保存」语义，避免两处状态打架）
                            if (memberIdsDraft.size >= MIN_GROUP_MEMBERS) {
                                container.groupChatRepository.setGroupMembers(group.id, memberIdsDraft)
                            }
                            saving = false
                            onSaved()
                            onDismiss()
                        }
                    },
                    enabled = !saving,
                ) {
                    Text(if (saving) "保存中…" else "保存", fontSize = 13.sp)
                }
            }
        }
    }

    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            containerColor = scheme.surfaceContainerHigh,
            title = { Text("删除群聊", color = scheme.onSurface) },
            text = { Text("确定删除「${group.title.ifBlank { "群聊" }}」？该群的全部消息将被清除。", color = scheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    deleteConfirm = false
                    scope.launch {
                        container.groupChatRepository.deleteGroup(group.id)
                        onDeleted?.invoke()
                        onDismiss()
                    }
                }) { Text("删除", color = scheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = false }) { Text("取消", color = scheme.onSurfaceVariant) }
            },
        )
    }

    // 成员增删弹窗：复用全局可搜索多选器（口径与新建群一致：名称/代号/职位/种族）
    if (showMemberPicker) {
        AlertDialog(
            onDismissRequest = { showMemberPicker = false },
            containerColor = scheme.surfaceContainerHigh,
            title = { Text("选择群成员（$MIN_GROUP_MEMBERS–${AppConfig.GroupChat.MAX_MEMBERS} 人）", color = scheme.onSurface) },
            text = {
                CharacterMultiPicker(
                    characters = characters,
                    selectedIds = memberIdsDraft.toSet(),
                    onToggle = { c ->
                        when {
                            c.id in memberIdsDraft -> {
                                if (memberIdsDraft.size > MIN_GROUP_MEMBERS) {
                                    memberIdsDraft = memberIdsDraft - c.id
                                } else {
                                    memberHint = "至少要保留 $MIN_GROUP_MEMBERS 名成员"
                                }
                            }
                            memberIdsDraft.size >= AppConfig.GroupChat.MAX_MEMBERS -> {
                                memberHint = "最多 ${AppConfig.GroupChat.MAX_MEMBERS} 名成员"
                                Toast.makeText(
                                    context,
                                    "最多选择 ${AppConfig.GroupChat.MAX_MEMBERS} 名成员",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            else -> {
                                memberIdsDraft = memberIdsDraft + c.id
                                memberHint = null
                            }
                        }
                    },
                    listHeight = 260.dp,
                    placeholder = "搜索角色名 / 代号 / 职位…",
                )
            },
            confirmButton = {
                TextButton(onClick = { showMemberPicker = false }) { Text("完成") }
            },
        )
    }
}

/** 群聊最少成员数：1 人不成群（单人聊天走角色页单聊），与新建群的下限保持一致。 */
private const val MIN_GROUP_MEMBERS = 2