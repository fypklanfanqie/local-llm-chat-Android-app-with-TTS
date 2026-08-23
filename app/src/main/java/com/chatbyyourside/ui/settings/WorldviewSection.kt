package com.chatbyyourside.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chatbyyourside.AppContainer
import com.chatbyyourside.data.model.Conversation
import com.chatbyyourside.data.model.WorldviewConfig
import com.chatbyyourside.data.model.WorldviewTargetType
import com.chatbyyourside.ui.characters.filterCharacters
import com.chatbyyourside.ui.glass.GlassButton
import com.chatbyyourside.ui.glass.GlassButtonStyle
import com.chatbyyourside.ui.glass.GlassListRow
import com.chatbyyourside.ui.glass.GlassListSection
import com.chatbyyourside.ui.glass.GlassSegmented
import com.chatbyyourside.ui.glass.GlassTextField
import kotlinx.coroutines.launch

/**
 * 设置 · 世界观设定分区：用户自定义叙事设定（一一对应绑定单个角色个人聊天或群聊），
 * 命中目标时按 data/model/WorldviewConfig.kt 的 buildWorldviewDirective 注入 system prompt。
 */
@Composable
fun WorldviewSection(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val worldviews by container.settingsRepository.worldviews.collectAsState(initial = emptyList())
    val characters by container.characterRepository.characters.collectAsState(initial = emptyList())
    val groups by container.groupChatRepository.observeGroups().collectAsState(initial = emptyList())

    var editTarget by remember { mutableStateOf<WorldviewConfig?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<WorldviewConfig?>(null) }

    GlassListSection(title = "世界观设定") {
        // 说明副标题
        Text(
            text = "自定义世界观会注入对应聊天（个人/群聊）的提示词。每条世界观与一个目标一一对应；同一目标可添加多条，按顺序生效。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (worldviews.isEmpty()) {
            Text(
                text = "还没有世界观，点下方「添加世界观」创建一条。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        worldviews.forEach { wv ->
            val targetLabel = resolveTargetLabel(wv, characters, groups)
            GlassListRow(
                title = wv.name,
                subtitle = buildString {
                    append(if (wv.targetType == WorldviewTargetType.CHARACTER) "个人聊天 · " else "群聊 · ")
                    append(targetLabel)
                    append(" · ")
                    append(wv.content.lineSequence().firstOrNull().orEmpty().take(30))
                    if (wv.content.length > 30) append("…")
                },
                showDivider = false,
                trailing = {
                    Row {
                        IconButton(onClick = { editTarget = wv }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Edit, contentDescription = "编辑", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = { deleteTarget = wv }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        }
                    }
                },
            )
        }
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            GlassButton(
                onClick = { showCreate = true },
                modifier = Modifier.fillMaxWidth(),
                style = GlassButtonStyle.Tinted,
            ) {
                Text("添加世界观", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    // 新建弹窗
    if (showCreate) {
        WorldviewEditDialog(
            editing = null,
            characters = characters,
            groups = groups,
            onSave = { wv ->
                scope.launch {
                    container.settingsRepository.updateWorldviews { it + wv }
                }
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }
    // 编辑弹窗（沿用原 id，原位替换）
    editTarget?.let { target ->
        WorldviewEditDialog(
            editing = target,
            characters = characters,
            groups = groups,
            onSave = { wv ->
                scope.launch {
                    container.settingsRepository.updateWorldviews { list ->
                        list.map { if (it.id == target.id) wv.copy(id = target.id) else it }
                    }
                }
                editTarget = null
            },
            onDismiss = { editTarget = null },
        )
    }
    // 删除确认
    deleteTarget?.let { wv ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("删除世界观", color = MaterialTheme.colorScheme.onSurface) },
            text = { Text("确定删除「${wv.name}」？", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.settingsRepository.updateWorldviews { list -> list.filterNot { it.id == wv.id } }
                    }
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            },
        )
    }
}

/** 目标摘要：角色/群名实时解析；悬空条目显式提示。 */
private fun resolveTargetLabel(
    wv: WorldviewConfig,
    characters: List<com.chatbyyourside.data.model.Character>,
    groups: List<Conversation>,
): String {
    return when (wv.targetType) {
        WorldviewTargetType.CHARACTER ->
            characters.firstOrNull { it.id == wv.targetId }?.name ?: "目标不存在（可能已被删除）"
        WorldviewTargetType.GROUP ->
            groups.firstOrNull { it.id.toString() == wv.targetId }?.title?.ifBlank { "群聊" }
                ?: "目标不存在（可能已被删除）"
    }
}

/**
 * 新建/编辑世界观弹窗：名称 + 正文多行 + 目标类型分段 + 目标单选（选角色时带搜索）。
 * [editing] 非空为编辑模式（预填字段）。
 */
@Composable
private fun WorldviewEditDialog(
    editing: WorldviewConfig?,
    characters: List<com.chatbyyourside.data.model.Character>,
    groups: List<Conversation>,
    onSave: (WorldviewConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var name by remember(editing) { mutableStateOf(editing?.name ?: "") }
    var content by remember(editing) { mutableStateOf(editing?.content ?: "") }
    var targetType by remember(editing) {
        mutableStateOf(editing?.targetType ?: WorldviewTargetType.CHARACTER)
    }
    var targetId by remember(editing) { mutableStateOf(editing?.targetId ?: "") }
    var charSearch by remember(editing) { mutableStateOf("") }

    val canSave = name.isNotBlank() && content.isNotBlank() && targetId.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.surfaceContainerHigh,
        titleContentColor = scheme.onSurface,
        title = { Text(if (editing == null) "添加世界观" else "编辑世界观", color = scheme.onSurface) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FieldLabel("名称")
                GlassTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "如：末世废土框架",
                )
                FieldLabel("世界观正文")
                BasicTextField(
                    value = content,
                    onValueChange = { if (it.length <= 4000) content = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(scheme.surface.copy(alpha = 0.6f))
                        .padding(10.dp),
                    textStyle = TextStyle(color = scheme.onSurface, fontSize = 13.sp),
                    cursorBrush = SolidColor(scheme.primary),
                    decorationBox = { inner ->
                        Box {
                            if (content.isEmpty()) {
                                Text(
                                    "描述这个世界的背景设定、规则与氛围…（建议不超过几百字）",
                                    color = scheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                )
                            }
                            inner()
                        }
                    },
                )
                FieldLabel("应用到")
                GlassSegmented(
                    options = listOf(
                        WorldviewTargetType.CHARACTER to "个人聊天",
                        WorldviewTargetType.GROUP to "群聊",
                    ),
                    selected = targetType,
                    onSelect = {
                        targetType = it
                        targetId = ""
                    },
                )

                when (targetType) {
                    WorldviewTargetType.CHARACTER -> {
                        // 选角色：搜索 + 单选列表
                        GlassTextField(
                            value = charSearch,
                            onValueChange = { charSearch = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = "搜索角色",
                            leading = {
                                Icon(Icons.Filled.Search, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            },
                        )
                        val filtered = remember(characters, charSearch) { filterCharacters(characters, charSearch) }
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                            items(filtered) { c ->
                                TargetOptionRow(
                                    label = c.name,
                                    selected = targetId == c.id,
                                    onClick = { targetId = c.id },
                                )
                            }
                        }
                    }
                    WorldviewTargetType.GROUP -> {
                        if (groups.isEmpty()) {
                            Text("还没有群聊，先去通讯页创建一个吧。", color = scheme.onSurfaceVariant, fontSize = 12.sp)
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                                items(groups) { g ->
                                    TargetOptionRow(
                                        label = g.title.ifBlank { "群聊" },
                                        selected = targetId == g.id.toString(),
                                        onClick = { targetId = g.id.toString() },
                                    )
                                }
                            }
                        }
                    }
                }
                if (!canSave) {
                    Text("名称、正文与应用目标均为必填项", color = scheme.tertiary, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = {
                onSave(
                    WorldviewConfig(
                        id = editing?.id ?: ("wv-" + System.currentTimeMillis()),
                        name = name.trim(),
                        content = content.trim(),
                        targetType = targetType,
                        targetId = targetId,
                    ),
                )
            }) { Text(if (editing == null) "创建" else "保存", color = if (canSave) scheme.primary else scheme.onSurfaceVariant) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = scheme.onSurfaceVariant) }
        },
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
}

/** 目标单选行：选中项主色描边高亮。 */
@Composable
private fun TargetOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (selected) Modifier.background(scheme.primary.copy(alpha = 0.12f))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (selected) scheme.primary else scheme.onSurface, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (selected) {
            Spacer(Modifier.width(4.dp))
            Text("✓", color = scheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}
