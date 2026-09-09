package com.chatbyyourside.ui.feed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.chatbyyourside.ui.glass.GlassButton
import com.chatbyyourside.ui.glass.GlassButtonStyle
import com.chatbyyourside.ui.theme.LocalDynamicAccent
import com.chatbyyourside.util.readableForeground
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.pager.VerticalPager
import com.chatbyyourside.AppContainer
import com.chatbyyourside.config.Characters
import com.chatbyyourside.data.model.Character
import com.chatbyyourside.ui.characters.CustomCharacterDialog
import com.chatbyyourside.ui.applySystemBarIcons
import com.chatbyyourside.util.CharacterImageStore
import com.chatbyyourside.util.loadThemeColor
import kotlinx.coroutines.launch

/** 聊天 Tab 内嵌导航的路由常量。
 * 注意：内层 CHAT 不要与外层 BottomTab.Chat.route = "chat" 同名，避免导航日志/条件判断混淆。 */
object FeedRoute {
    const val FEED = "feed"
    const val CHAT = "chat_detail"
    const val CHAT_WITH_CONVERSATION = "chat_detail/{conversationId}"
    fun chatRoute(conversationId: Long): String = "chat_detail/$conversationId"
    const val ENCOUNTER = "encounter"
    /** 群聊列表（微信式：新建/进入已有群）。 */
    const val GROUP_LIST = "group_list"
    /** 群聊会话页路由模板（后接群 id）。 */
    const val GROUP_CHAT = "group_chat/{groupId}"
    /** 朋友圈。 */
    const val MOMENTS = "moments"
    /** 小说模式：故事列表 / 章节管理 / 对白编辑器。 */
    const val NOVEL_HOME = "novel_home"
    const val NOVEL_STORY = "novel_story/{storyId}"
    const val NOVEL_EDITOR = "novel_editor/{chapterId}"

    fun novelStoryRoute(storyId: Long): String = "novel_story/$storyId"
    fun novelEditorRoute(chapterId: Long): String = "novel_editor/$chapterId"

    fun groupChatRoute(groupId: Long): String = "group_chat/$groupId"
}

/**
 * 刷抖音式角色卡片流（首页）。
 * 全屏竖滑浏览所有角色，停到哪个就能直接与它对话；角色会随机问好。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CharacterFeedScreen(
    container: AppContainer,
    bottomBarHeight: Dp = 0.dp,
    /** 上次启动在加载窗口内异常退出（原生崩溃判定）：首页弹出引导用户查看崩溃日志。 */
    crashNotice: Boolean = false,
    onOpenChat: (String) -> Unit,
    onNavigateToCharacters: () -> Unit,
    /** 进入「邂逅」沉浸式视频历史流（顶栏玻璃按钮）。 */
    onOpenEncounter: () -> Unit = {},
    /** 进入「群聊」多人同群聊天（顶栏玻璃按钮，仅需已配置云端 API）。 */
    onOpenGroupChat: () -> Unit = {},
    /** 进入「朋友圈」（顶栏玻璃按钮，替代原「全部角色」入口；角色页仍可从底部 Tab 进）。 */
    onOpenMoments: () -> Unit = {},
    /** 进入「小说」模式（卡片底部操作按钮，好感右侧）。 */
    onOpenNovel: () -> Unit = {},
    /** 进入好感度独立页面。 */
    onOpenAffinity: (String) -> Unit = {},
    /** 当前落定立绘的主题色上报（供 dock 栏等全局着色）；页面销毁时应回传 null 复位。 */
    onAccent: (Color?) -> Unit = {},
) {
    val characters by container.characterRepository.characters.collectAsState(
        initial = Characters.getOrderedList(),
    )
    val activeCharacter by container.settingsRepository.activeCharacter.collectAsState(
        initial = Characters.DEFAULT_CHARACTER_ID,
    )
    val volume by container.settingsRepository.volume.collectAsState(initial = 60)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 立绘背景为全屏深色画面：系统状态栏 / 导航栏图标改为白色，保证在深色立绘上可读。
    applySystemBarIcons(light = true)

    // 顶栏下移量：取「WindowInsets 状态栏高度 / 系统 status_bar_height 资源 / 56dp 保底」的最大值，
    // 再叠加 8dp 余量。个别 ROM inset 异常时也能保证「通讯 / 全部角色 / 新建」文字
    // 完整位于状态栏下方，绝不遮挡。
    val density = LocalDensity.current
    val statusBarInset = WindowInsets.statusBars.getTop(density)
    val statusBarRes = context.resources.getIdentifier("status_bar_height", "dimen", "android")
    val statusBarH = if (statusBarRes > 0) context.resources.getDimensionPixelSize(statusBarRes) else 0
    val topBarPadding = with(density) {
        (maxOf(statusBarInset, statusBarH, 56.dp.roundToPx()) + 8.dp.roundToPx()).toDp()
    }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { characters.size })

    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Character?>(null) }
    // 上次启动异常退出提示：仅当 crashNotice 为 true 时首次进入弹一次（用户点掉后不再弹）。
    var showCrashNotice by remember { mutableStateOf(crashNotice) }

    // settle 检测：滚动停止且落在某页时更新（滚动中保持旧值），驱动回弹 / 图标弹出 / 随机问好
    val settledPage = pagerState.settledPage

    // 当前落定角色的立绘主题色：滑动 / 切角色时自动重提取，上报给全局动态强调色
    // （驱动「开始对话/对话/人设」按钮与 dock 栏着色）；页面销毁时复位为 null。
    val settledCharacter = characters.getOrNull(
        if (settledPage >= 0) settledPage else pagerState.currentPage,
    )
    val settledImageUrl = settledCharacter?.let { char ->
        if (char.isCustom && char.image.isNotBlank()) char.image
        else container.assetRepository.getSelectionPicture(char.id)
    }
    LaunchedEffect(settledImageUrl) {
        onAccent(settledImageUrl?.let { loadThemeColor(context, it) })
    }
    DisposableEffect(Unit) {
        onDispose { onAccent(null) }
    }

    // 起始定位 / 外部切角色（问候通知、网格选角色）时跳到该角色页；只响应 activeCharacter，
    // 避免自定义角色列表刷新时把用户手动浏览的位置强行拉回。
    LaunchedEffect(activeCharacter) {
        val idx = characters.indexOfFirst { it.id == activeCharacter }
        if (idx >= 0 && idx != pagerState.currentPage) {
            pagerState.scrollToPage(idx)
        }
    }

    // 列表收缩 clamp（删除自定义角色后）
    LaunchedEffect(characters.size) {
        if (characters.isEmpty()) return@LaunchedEffect
        if (pagerState.currentPage >= characters.size) {
            pagerState.scrollToPage(characters.size - 1)
        }
    }

    // 根容器铺满整屏：通讯 Tab 已在全屏层（不预留底栏），故立绘背景自然延伸到浮动 dock
    // 与系统导航栏背后、直达屏幕最底部，dock 作为浮层叠在最上。加黑色兜底背景，防止图片
    // 加载前 / 失败时透出浅色窗口底。
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        VerticalPager(
            state = pagerState,
            beyondBoundsPageCount = 1,
            key = { i -> characters.getOrNull(i)?.id ?: i },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val char = characters.getOrNull(page) ?: return@VerticalPager
            CharacterFeedPage(
                character = char,
                isActive = char.id == activeCharacter,
                imageUrl = if (char.isCustom && char.image.isNotBlank())
                    char.image
                else
                    container.assetRepository.getSelectionPicture(char.id),
                pagerState = pagerState,
                pageIndex = page,
                settled = settledPage == page,
                bottomBarHeight = bottomBarHeight,
                onChat = {
                    scope.launch {
                        container.settingsRepository.setActiveCharacter(char.id)
                        onOpenChat(char.id)
                    }
                },
                onAffinity = { onOpenAffinity(char.id) },
                onNovel = onOpenNovel,
                onVoice = container.assetRepository.getVoice(char.id).takeIf { it.isNotBlank() }?.let { url ->
                    { scope.launch { container.audioManager.playVoice(url, volume) } }
                },
                onDelete = if (char.isCustom) ({ deleteTarget = char }) else null,
            )
        }

        // 顶栏：沉浸覆盖，文字整体下移（状态栏高度 + 余量），避免遮挡状态栏
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(start = 20.dp, end = 20.dp, top = topBarPadding, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 顶栏 chip 内容色：磨砂底 ≈ 模糊背景 + 12% 主题 tint，整体偏浅——写死白色在
            // 浅色立绘主题（如浅青绿）上对比度近零。改为按主题色亮度自适应选黑/白：
            // 浅主题 -> 深字，深主题 -> 白字，立绘切换时自动跟随。
            val chipContent = (LocalDynamicAccent.current ?: MaterialTheme.colorScheme.primary)
                .readableForeground()
            Text(
                text = "通讯",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassButton(
                    onClick = onOpenMoments,
                    style = GlassButtonStyle.Glass,
                    horizontalPadding = 12.dp,
                    verticalPadding = 8.dp,
                ) {
                    Text(
                        "朋友圈",
                        color = chipContent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                GlassButton(
                    onClick = onOpenEncounter,
                    style = GlassButtonStyle.Glass,
                    horizontalPadding = 12.dp,
                    verticalPadding = 8.dp,
                ) {
                    Text(
                        "邂逅",
                        color = chipContent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                GlassButton(
                    onClick = onOpenGroupChat,
                    style = GlassButtonStyle.Glass,
                    horizontalPadding = 12.dp,
                    verticalPadding = 8.dp,
                ) {
                    Text(
                        "群聊",
                        color = chipContent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                GlassButton(
                    onClick = { showCreate = true },
                    style = GlassButtonStyle.Glass,
                    horizontalPadding = 12.dp,
                    verticalPadding = 8.dp,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = chipContent,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "新建",
                        color = chipContent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }

    if (showCreate) {
        CustomCharacterDialog(
            onDismiss = { showCreate = false },
            onConfirm = { c ->
                scope.launch { container.characterRepository.addCustom(c) }
                showCreate = false
            },
        )
    }

    deleteTarget?.let { char ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("删除角色") },
            text = { Text("确定删除「${char.name}」？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        CharacterImageStore.delete(context, char.image)
                        container.characterRepository.removeCustom(char.id)
                        if (activeCharacter == char.id) {
                            container.settingsRepository.setActiveCharacter(Characters.DEFAULT_CHARACTER_ID)
                        }
                    }
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    // 上次启动在加载窗口内异常退出：提示用户去 设置 → 崩溃日志 分享日志给开发者。
    // Java 崩溃堆栈已由 CrashReporter 落盘；原生崩溃无法捕堆栈，但仍可提示用户反馈机型/复现步骤。
    if (showCrashNotice) {
        AlertDialog(
            onDismissRequest = { showCrashNotice = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("上次启动异常退出") },
            text = {
                Text(
                    "应用上次启动时异常退出（可能是设备兼容性问题）。\n\n" +
                        "崩溃日志已自动保存，请前往「设置 → 崩溃日志」查看并分享给开发者，帮助修复。",
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            confirmButton = {
                TextButton(onClick = { showCrashNotice = false }) { Text("知道了") }
            },
        )
    }
}
