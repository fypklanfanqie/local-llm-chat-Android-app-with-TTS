package com.chatbyyourside.ui.video

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.chatbyyourside.data.model.SeedanceVideo
import com.chatbyyourside.data.model.SeedanceVideoState
import java.io.File
import java.util.Date
import java.util.Locale

/** 邂逅全屏播放器表面 testTag（instrumentation 断言「仅落定页挂载播放器」）。 */
const val SEEDANCE_ENCOUNTER_PLAYER_TAG = "seedance_encounter_player"

/**
 * 邂逅单页布局（Task 9）。
 *
 * 全屏一页：背景为角色参考快照（[SeedanceVideo.characterImagePath]，内部归档文件，经 Coil 加载），
 * 叠加深色渐变保证文字可读；底部浮层显示角色名 / 日期 / 状态 / 会话摘要（快照持久，聊天删除仍可见）/
 * 提示词摘要，并复用 [SeedanceVideoCard] 渲染状态与动作，外加「详情」入口。
 *
 * **播放器挂载门控**：[player] 仅在「落定页 + READY」时由 [EncounterScreen] 传入；
 * 本页据此挂载全屏 [SeedanceVideoPlayer]（与参考图背景互斥），非落定页 / 非 READY 一律不挂载，
 * 保证同一时刻至多一个 PlayerView 表面。
 */
@Composable
fun EncounterVideoPage(
    video: SeedanceVideo,
    settled: Boolean,
    player: Player?,
    onOpenDetails: () -> Unit,
    onExport: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    bottomBarHeight: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    // 仅落定页的 READY + 非空归档路径视频挂载播放器表面（与 settleEncounterPlayback 门控一致，
    // 避免 READY 但缺文件时挂载一个播放空黑的 PlayerView）。
    val attached = settled && video.state == SeedanceVideoState.READY &&
        !video.localVideoPath.isNullOrBlank() && player != null
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (attached) {
            SeedanceVideoPlayer(
                player = player,
                showControls = true,
                testTag = SEEDANCE_ENCOUNTER_PLAYER_TAG,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            EncounterBackdrop(video)
        }

        // 底部信息浮层：角色名 / 日期·状态 / 会话摘要 / 提示词摘要 / 卡片动作 / 详情。
        // 底部预留 bottomBarHeight（浮动 dock 高度），交互内容不被 dock 遮挡。
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp + bottomBarHeight),
        ) {
            Text(
                text = video.characterNameSnapshot,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${formatVideoTimestamp(video.createdAt)} · ${stateText(video)}",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(10.dp))
            // 会话摘要：全部来自任务快照，源头聊天被删除后依然可见。
            video.userTextSnapshot.takeIf { it.isNotBlank() }?.let { user ->
                Text(
                    text = "“$user”",
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            video.assistantTextSnapshot.takeIf { it.isNotBlank() }?.let { assistant ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = assistant,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            video.finalPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "提示词：$prompt",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            // 状态与动作复用视频卡（状态文案 / 取消 / 重试 / 保存到本地）。
            SeedanceVideoCard(
                video = video,
                onExport = onExport,
                onCancel = onCancel,
                onRetry = onRetry,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EncounterPageChip(text = "详情", onClick = onOpenDetails)
            }
        }
    }
}

/** 背景：角色参考快照（Coil）或早期任务的主题渐变兜底，叠加底部压暗保证文字可读。 */
@Composable
private fun EncounterBackdrop(video: SeedanceVideo) {
    Box(Modifier.fillMaxSize()) {
        val imagePath = video.characterImagePath
        if (!imagePath.isNullOrBlank()) {
            AsyncImage(
                model = File(imagePath),
                contentDescription = "${video.characterNameSnapshot} 参考图",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 快照复制前（SNAPSHOT_PENDING 早期）尚无参考图：主题渐变兜底。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF33334A), Color(0xFF0E0E16)),
                        )
                    )
            )
        }
        // 底部压暗（0.55 高度以下渐黑），保证白色文字在任意图片上可读。
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.88f),
                    )
                )
        )
    }
}

/** 页面胶囊按钮（白字半透明白底，深色背景下恒可读）。 */
@Composable
private fun EncounterPageChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** 时间戳 -> `yyyy-MM-dd HH:mm`（邂逅历史流展示用）。 */
internal fun formatVideoTimestamp(ts: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

/**
 * 落定页播放决策（供 [EncounterVideoPager] 与 instrumentation 测试共用）：
 * 落定视频为 READY 且本地归档文件就绪 -> 播放；否则（非 READY / 离开列表 / 文件缺失）暂停。
 */
internal fun settleEncounterPlayback(
    settledVideo: SeedanceVideo?,
    controller: SeedancePlaybackController,
) {
    val path = settledVideo?.localVideoPath
    if (settledVideo != null && settledVideo.state == SeedanceVideoState.READY && !path.isNullOrBlank()) {
        controller.play(File(path))
    } else {
        controller.pause()
    }
}
