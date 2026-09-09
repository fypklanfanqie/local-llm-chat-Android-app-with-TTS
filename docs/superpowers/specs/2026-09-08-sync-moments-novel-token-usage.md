# 设计文档：从「罗德岛通讯终端」同步 v3.1–v3.2 更新（朋友圈 / 小说模式 / Token 用量 / 体验加固）

日期：2026-09-08　状态：已实施　来源仓库：`D:\ai\cc Programm\聊天终端安卓本地`（package `com.rhodesisland.terminal`）

## 背景

两个仓库同源同架构：本仓库（大众版，`com.chatbyyourside`）是上游的**去 IP 化分支**。近期功能都只在上游推进，本仓库落后一截。本次把上游 `2f05af2..bc0c770`（2026-08-31 → 2026-09-08）的更新搬过来，并按本仓库定位剥离全部明日方舟元素。

上游同步区间内相关提交：

| 上游提交 | 内容 |
| --- | --- |
| `2f05af2` | v3.1 细节优化 + 朋友圈设计文档 |
| `010c994` | 朋友圈功能 + 群聊 @ 定向回答 + 礼物删除 + 滚动竞态修复 |
| `95d05f7` | 小说模式 + 朋友圈四项优化 + 中文输出约束 |
| `864496f` | 通讯卡片移除「人设」按钮，修复按钮挤成单字 |
| `01d38a6` | 朋友圈日常化 + 随机 @、测试连接、生图三通道自动回退 |
| `420dc98` | Token 用量统计、朋友圈互动（评论/点赞/删除） |
| `bc0c770` | 云端辅助功能与聊天模式解耦 + 缓存命中计入用量 |

## 1. 朋友圈（新）

角色经云端 LLM + 用户自有生图 API 发帖，用户/角色互相评论点赞。

- 数据：Room **v9→v10** 新增 `moment_post` / `moment_comment` / `moment_like`（`MomentEntities.kt` + `MomentDao`，列表窗口 100 条，帖子级联删除互动）。
- 生成：`MomentGenerationCoordinator`（UI 与后台 Worker 共用）→ `DirectLlmClient.chatOnce` 出 `{caption, imagePrompt}` 严格 JSON，宽松解析兜底 `MomentImageExtractor`。
- 生图：`MomentImageGenClient` 三通道自动回退（OpenAI 聊天格式出图 / gpt-image 类 Responses 端点 / 任务制媒体 API），参考图 = 角色立绘 data URL；失败自动降级纯文字。配置独立于主 LLM（`moment_imagegen_*` 三键 + 总开关）。
- 互动：点赞防重；用户评论后发帖角色必回；用户发圈后从「互动角色」集随机 1~3 评论、1~3 点赞。
- 定时：`MomentScheduler`（15 分钟 PeriodicWork 心跳 + `moment_next_fire_at` 门控，间隔可调 1–72h、±12% 抖动、8–23 点窗口、角色严格轮换），`ChatApp.onCreate` 里 `ensureScheduled`。
- UI：`ui/moment/MomentsScreen`（封面 + 头像昵称 + 九宫格 + 相对时间 + 「···」赞/评论），`util/RelativeTime`；导航 `FeedRoute.MOMENTS`，通讯页顶栏「全部角色」按钮替换为「朋友圈」（角色页仍走底部 Tab）。
- 设置：「朋友圈」区（自动发圈开关/间隔/发圈角色/互动角色/生图 API 三项/生图开关/测试连接真发一条）。

## 2. 小说模式（新）

故事 → 章节（话）→ 对白脚本行 + AI 续写。

- 数据：Room **v10→v11** 新增 `novel_story` / `novel_chapter` / `novel_line`。
- 生成：`NovelPromptBuilder`（成员角色卡 + 主控人设 + 世界观）、`NovelScriptParser`（`说话人：内容` 三态脚本行解析）、`OutputLanguage.ZH_DIRECTIVE` 中文输出约束。
- UI：`ui/novel/NovelScreens.kt`（NovelHome / NovelStory / NovelEditor 三屏）+ `FeedRoute.NOVEL_*` 路由；卡片流底部「小说」按钮进入。

## 3. Token 用量统计（新）

- `TokenUsageConfig`（按角色累计 输入/输出/调用次数/缓存命中，DataStore JSON）+ `LlmTokenUsage` 记账契约。
- `DirectLlmClient.chatOnce/chatOnceStructured` 新增可选 `onUsage` 回调：以 `lastCloudUsage` 调用前后快照变化判定「本次拿到真实 usage」，端点未回报则不记账。
- 记账点：1:1 聊天、主动问候、群聊发言（按发言人）、朋友圈文案/评论/回复。翻译/摘要/连通性测试等无角色上下文的调用不计入。
- 设置页「Token 用量」区：总量四宫格 + 按角色双色堆叠条形图（Top 12）+ 搜索明细 + 缓存命中率。

## 4. 云端辅助功能与聊天模式解耦

朋友圈 / 群聊 / 主动问候不再要求「聊天页切到云端」，只看**是否配置过云端 API**：

- 新判定入口 `SettingsRepository.isCloudApiReady()`（有 key 或内置免费代理端点）。
- 闸门替换：`GreetingScheduler` / `GreetingWorker` / `GroupChatScheduler` / `GroupChatWorker` / `MomentScheduler` / `MomentWorker` 的 `getActiveProviderNow() == CLOUD` → `isCloudApiReady()`。
- 群聊发言固定使用 `container.cloudChatProvider`（不跟随聊天页切换）；`GroupChatScreen` 去掉「仅云端可用」提示与置灰。
- 设置页问候/群聊/朋友圈文案与 Toast 改为「请先在设置中配置云端 AI API」。

## 5. 体验与稳定性加固

- **群聊 @ 定向回答**：`GroupSpeakerPicker.resolveReplySpeakers`（有 @ 仅被 @ 者按提及顺序答，不随机补人；无 @ 随机 1..cap）。旧 `randomReplyCount`/`pickRandom` 删除。
- **礼物档案可删除**：`AffinityDao.deleteInventory` + `AffinityRepository.deleteGift`（事务删定义+库存，返回图片路径供落盘清理）+ 商店卡「删除」入口与确认弹窗。
- **快速上翻被拉回底部（竞态修复）**：`ChatAutoScrollPolicy` 引入跟随锚点 `followAnchorTotal` 与纯函数 `shouldFollowBottom`；单聊 `ChatMessageList` 跟随前校验「旧末项仍在视口内」。
- **群聊列表同构**：`GroupChatMessageList` 从无条件滚底升级为「用户接管优先」+ 锚点校验 + 「回到底部」按钮。
- **横向滚动嵌套闪退**（`c98b3bd`）：`AdaptiveScrollRow` 按测量约束分流，无限宽约束下退化为普通 Row。
- **手机布局加固**（`5d5b077`）：导航项均分权重 + 48dp 触控下限 + 长标题/副标题省略号 + 分段控件可横向滚动；设置页 emoji 换 Material 图标。
- **内存与生命周期边界**（`5737289`）：Seedance 参考图先探测尺寸再解码（8MB 文件 / 4096px 长边双闸）；导出长图 48MB 像素预算前置拒绝；PDF 单页渲染长边 2048px 封顶 + `finally` 关页回收位图；角色语音 `voiceGeneration` 代次闸防迟到回调；视频播放器 `released` 幂等短路。

## 6. 未搬运（本仓库已有等价或更优实现）

| 上游提交 | 原因 |
| --- | --- |
| `11ce736` 统一错误边界 | 本仓库 `util/UserFacingErrorMapper`（新Ports 文件按此改写调用点） |
| `137a11d` 特殊邂逅永久回忆 | 本仓库以 DAO 软关联保护实现（Room 无归档表） |
| `2860987` 群聊串人设 | 本仓库 `GroupChatPromptBuilder.parseSpeakerResponse` 已实现 |
| `3cb11af` 世界书生效范围搜索 | 本仓库 `ui/lorebook/LorebookTargetFilter` |
| 滚动摘要 / 生成参数 / Anthropic cache_control | 本仓库 `RollingSummaryPlanner` + `cloud_temperature`/`cloud_max_tokens` + `a8b80a4`/`bc535fd` |
| `4a59a53`/`010c994` 的 `MAX_PROMPT_SUPPLY` + `PromptWindowAnchor` | 本仓库用 `MAX_HISTORY_PER_CONVERSATION=400` 存储窗口 + `takeLast(MAX_CONTEXT_MESSAGES)` 的另一套缓存方案 |
| `LorebookEngine` 预算「constant 豁免」改法 | 本仓库为两段式静态头/动态尾预算（缓存锚更强），搬上游会回退 |
| `CharacterFeedHost` 导航壳重构 | 本仓库好感度页路由挂在 `AppNavGraph` 外层，等价可用 |
| native `.so`（libMNN/libmnn_jni/libcpu_sys_jni/libbackend_probe）与 `app/src/main/cpp/*` | 两仓库已各自演进（本仓库 800 行 mnn_jni vs 上游 883 行），二进制与 Kotlin 桥须成对重编，另案处理 |
| `95d05f7` 中的 `USER_DISPLAY_NAME` | 已搬（`UserProfileConfig.displayName` + 设置页昵称字段），兜底显示名由「博士」改为「我」 |

## 7. 去明日方舟化处理

- 包名 `com.rhodesisland.terminal` → `com.chatbyyourside`；`RhodesApp` → `ChatApp`。
- 提示词与注释：`「博士」` → 「用户」；生图风格 `明日方舟（Arknights）游戏美术风格` → `日系动画插画风格`（保留干净线稿/赛璐璐上色/游戏 CG 质感/不要写实照片）；昵称兜底 `"博士"` → `"我"`。
- 单测夹具去 IP：`阿米娅/德克萨斯/拉普兰德/能天使/凯尔希/博士` → 本仓库原创角色名（苏晚/阿橙/凛/小鹿/薇拉/小满），`罗德岛` → `工作室`。
- 顺手清理既有残留：删除 `res/values/colors.xml` 里无引用的 `prts_*` 死色板；`ChatBackgroundRepository` / `CharacterRepository` / `AppContainer` 注释里的 PRTS、干员字样。
- 复核结论：`app/src/main` 与 `app/src/test`、`app/src/androidTest` 全域正则扫描 `博士|明日方舟|Arknights|罗德岛|PRTS|干员|阿米娅|凯尔希|源石|泰拉|龙门币` **零命中**。
- **有意保留**：物理存储文件名 `rhodes_chat.db`（Room）与 `rhodes_settings`（DataStore）。改名=老用户数据全丢，需另做迁移（复制 DB + `deleteDatabase` + DataStore 重建），不在本次同步范围。

## 8. 版本与验证

- `app/build.gradle.kts`：versionCode 5 → **6**，versionName `3.1` → **`3.2`**。
- `:app:compileDebugKotlin` ✅；`:app:assembleDebug` ✅。
- `:app:testDebugUnitTest`：935 条，**2 条失败**——`MnnJniContractTest.runtimeInfoParserRecognizesUtf8StreamCapability` 与 `runtimeInfoWithoutUtf8CapabilityIsNotEligible`。已在基线提交 `84f23c4` 单独复现同样 2 条失败（`MnnRuntimeInfo.fromJson` 用 `org.json`，JVM 单测下构造即抛异常 → 返回 null），与本次同步无关。
- `:app:compileDebugAndroidTestKotlin` 在基线即失败（`SpecialEventConversationDaoTest` 位置参数与 `ChatHistoryEntity` 签名漂移、`MnnStreamingIntegrationTest` 的 `cachePath` 参数改名），同为既有欠账。
