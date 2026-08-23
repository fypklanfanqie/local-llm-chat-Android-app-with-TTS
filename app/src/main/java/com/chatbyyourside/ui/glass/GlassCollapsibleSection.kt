package com.chatbyyourside.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import com.chatbyyourside.ui.theme.GlassShapes
import androidx.compose.ui.unit.dp

/**
 * 可折叠分组：标题行（点击切换）+ 旋转箭头 + 弹性展开的内容玻璃卡。
 * 标题行样式与 [GlassListSection] 完全同款，折叠时玻璃卡随内容一起进出动画、不残留空壳。
 *
 * @param key 传入则以 rememberSaveable 记忆展开态（进程重建/往返恢复）；null 则纯内存。
 * @param initiallyExpanded 默认展开态（key == null 时的生效起点；key != null 时为首次进入的初值）。
 * @param keepContent true 时折叠仅隐藏不销毁内容组合（animateContentSize 方案），
 *   保住未保存的草稿态（TTS / API / 我的形象等表单分区用）；false 用 AnimatedVisibility，省内存。
 * @param headerExtra 标题行右侧额外槽（如条数徽标、行内开关），不参与点击切换。
 */
@Composable
fun CollapsibleSection(
    title: String,
    modifier: Modifier = Modifier,
    key: String? = null,
    initiallyExpanded: Boolean = true,
    keepContent: Boolean = false,
    headerExtra: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val expanded = if (key != null) {
        rememberSaveable(key) { mutableStateOf(initiallyExpanded) }
    } else {
        remember { mutableStateOf(initiallyExpanded) }
    }

    val rotation by animateFloatAsState(
        targetValue = if (expanded.value) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "collapseArrow",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        // 标题行（样式对齐 GlassListSection 的标题）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded.value = !expanded.value }
                .padding(start = 20.dp, top = 14.dp, bottom = 8.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            if (headerExtra != null) {
                headerExtra()
            }
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = if (expanded.value) "收起" else "展开",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(rotation),
            )
        }

        if (keepContent) {
            // 折叠仅隐藏不销毁组合，保住表单草稿态
            Column(
                Modifier
                    .fillMaxWidth()
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ),
            ) {
                if (expanded.value) {
                    CollapsibleCard(content)
                }
            }
        } else {
            AnimatedVisibility(
                visible = expanded.value,
                enter = expandVertically(
                    spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                ) + fadeIn(tween(160)),
                exit = shrinkVertically(
                    spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                ) + fadeOut(tween(120)),
            ) {
                CollapsibleCard(content)
            }
        }
    }
}

/** 内容玻璃卡（原样搬 GlassListSection 的容器）。 */
@Composable
private fun CollapsibleCard(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .frostedGlass(GlassShapes.large, shadowElevation = 4.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            content()
        }
    }
}
