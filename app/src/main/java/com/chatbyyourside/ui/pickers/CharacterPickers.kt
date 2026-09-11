package com.chatbyyourside.ui.pickers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chatbyyourside.data.model.Character
import com.chatbyyourside.ui.glass.GlassTextField

/**
 * 角色选择器共用件。
 *
 * 覆盖全部「添加/选择角色」入口：群聊成员、@ 选择器、朋友圈选角、问候角色、发圈角色、互动角色。
 * 集中一处的原因：这些入口原本各写一份「LazyColumn + Checkbox」，加搜索要在六处同步改；
 * 集中后「搜索匹配口径」与视觉只有一份定义，也不会再出现某个入口漏了搜索框。
 *
 * 过滤只影响「显示哪些行」，不会改变调用方持有的已选集合（被过滤掉的已选项仍然生效）。
 */

/**
 * 角色匹配：名称 / 代号 / id / 职位 / 种族 任一命中即算。
 *
 * 与角色页搜索口径保持一致——角色多时用户常按「医生」「学姐」这类定位词筛人，
 * 这些词不参与匹配的话就等于搜不到。
 */
fun characterMatchesQuery(char: Character, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return char.name.contains(q, ignoreCase = true) ||
        char.code.contains(q, ignoreCase = true) ||
        char.id.contains(q, ignoreCase = true) ||
        char.role.contains(q, ignoreCase = true) ||
        char.race.contains(q, ignoreCase = true)
}

/** 按关键词过滤角色（空关键词返回原列表）。抽成函数便于各处口径一致。 */
fun filterCharactersByQuery(characters: List<Character>, query: String): List<Character> {
    val q = query.trim()
    if (q.isEmpty()) return characters
    return characters.filter { characterMatchesQuery(it, q) }
}

/**
 * 选择器搜索框：玻璃输入框 + 放大镜 + 命中数 + 清空。
 *
 * @param hitCount 过滤后命中数（非空关键词时才显示，避免无意义噪音）
 */
@Composable
fun CharacterSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索角色名 / 代号 / 职位…",
    hitCount: Int? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier) {
        GlassTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder,
            singleLine = true,
            leading = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            },
        )
        if (query.isNotBlank() && hitCount != null) {
            Text(
                "找到 $hitCount 个角色",
                color = scheme.onSurfaceVariant,
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 2.dp, top = 2.dp),
            )
        }
    }
}

/**
 * 单行角色：勾选框（多选）或高亮（单选）+ 名称 + 自定义徽标 + 职位。
 *
 * [selected] 的行加主题色底，让「已选哪些」在长列表里一眼可见。
 */
@Composable
fun CharacterPickerRow(
    char: Character,
    selected: Boolean,
    onClick: () -> Unit,
    showCheckbox: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCheckbox) {
            Checkbox(checked = selected, onCheckedChange = null)
            Spacer(Modifier.width(6.dp))
        } else {
            // 单选：小圆点标识当前项（无 Checkbox 时给一眼可见的选中态）
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (selected) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.3f),
                    ),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            char.name,
            color = if (selected) scheme.primary else scheme.onSurface,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (char.isCustom) {
            Text(
                "自定义",
                color = scheme.onSurfaceVariant,
                fontSize = 9.sp,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        if (char.role.isNotBlank()) {
            Text(
                char.role,
                color = scheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 角色**多选**列表（带搜索）：点击行切换勾选。
 *
 * 列表用固定高度 LazyColumn：AlertDialog 内 heightIn/weight 在无界约束下不滚动的问题不再重演。
 */
@Composable
fun CharacterMultiPicker(
    characters: List<Character>,
    selectedIds: Set<String>,
    onToggle: (Character) -> Unit,
    modifier: Modifier = Modifier,
    listHeight: Dp = 240.dp,
    placeholder: String = "搜索角色名 / 代号 / 职位…",
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(characters, query) { filterCharactersByQuery(characters, query) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CharacterSearchField(
            query = query,
            onQueryChange = { query = it },
            placeholder = placeholder,
            hitCount = filtered.size,
        )
        if (filtered.isEmpty()) {
            Text(
                "没有匹配的角色",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().height(listHeight)) {
                items(filtered, key = { it.id }) { char ->
                    CharacterPickerRow(
                        char = char,
                        selected = char.id in selectedIds,
                        onClick = { onToggle(char) },
                        showCheckbox = true,
                    )
                }
            }
        }
    }
}

/**
 * 角色**单选**列表（带搜索）：点击行即选中。
 *
 * [selectedId] 用于高亮当前项；不传时列表仅作「点谁就是谁」的选择器用。
 */
@Composable
fun CharacterSinglePicker(
    characters: List<Character>,
    onSelect: (Character) -> Unit,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
    listHeight: Dp = 240.dp,
    placeholder: String = "搜索角色名 / 代号 / 职位…",
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(characters, query) { filterCharactersByQuery(characters, query) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CharacterSearchField(
            query = query,
            onQueryChange = { query = it },
            placeholder = placeholder,
            hitCount = filtered.size,
        )
        if (filtered.isEmpty()) {
            Text(
                "没有匹配的角色",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().height(listHeight)) {
                items(filtered, key = { it.id }) { char ->
                    CharacterPickerRow(
                        char = char,
                        selected = char.id == selectedId,
                        onClick = { onSelect(char) },
                        showCheckbox = false,
                    )
                }
            }
        }
    }
}
