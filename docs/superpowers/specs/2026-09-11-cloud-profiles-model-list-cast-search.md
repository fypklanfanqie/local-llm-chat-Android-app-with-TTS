# 设计文档：多份云端配置 / 模型清单实时获取 / 小说阵容 / 全入口可搜索选角

日期：2026-09-11　状态：已实施

## 背景

四项用户需求：

1. **云端 LLM 自定义配置可保存多份并随时切换**——原来只有单一「自定义」槽位，公司中转站 / 本地 LM Studio / 第三方兼容站会互相覆盖。
2. **内置供应商的模型清单不写死**——预设模型表随服务商发版必然过期，硬编码等于逼用户手打模型名。
3. **小说可指定主角 / 配角，并在写作过程中随时增删角色**。
4. **所有「添加 / 选择角色」的入口都要能搜索**。

参照同源上游仓库（`聊天终端安卓本地`）的同期未提交改动实现，但按本仓库定位做了三处适配：去掉上游新引入的 i18n `t()`/`tf()` 包装（本仓库无多语言层）、去掉明日方舟元素、适配本仓库自有的 Room 版本（本仓库为 v11，上游为 v15）与既有选择器实现。

> 注：上游那份 WIP 当时**编译不过**（`DirectLlmClient.parseModelIds` 的 lambda 末尾是 `val id = …` 声明、漏了返回 `id`，导致 `List<Unit>`）。本实现已修正该缺陷并补单测覆盖。

## 1. 多份自定义云端配置（CloudProfile）

- 模型：`CloudProfile(id, name, baseUrl, apiKey, model)` + `displayTitle`（没起名字退化成端点主机名）。
- 存储：DataStore 新增 `cloud_profiles`（JSON 列表）与 `active_cloud_profile`（当前档 id）。
- 仓储：
  - `saveCloudProfile(profile)`：id 为空即「另存为新配置」（分配 `cp-xxxxxxxx`），非空即「更新当前配置」；保存**同时**写活跃 `ApiConfig` 与「自定义」供应商槽位——用户点保存的语义就是「以后用这份」，分两步会产生「保存了但没生效」的错觉。
  - `activateCloudProfile(id)` / `deleteCloudProfile(id)`：切换即整体切换 baseUrl/key/model；删当前档时清空 active。
  - `getActiveCloudProfileNow()`：未选或已被删除返回 null。
- UI（设置页「LLM API 配置」→ 自定义分支）：
  - 配置档列表：名称 · 端点 · 模型，点击即切换（当前档高亮 + ✓），行尾「删除」。
  - 「另存为新配置」（配名称输入框）与「更新当前配置」两个动作。
  - 保存按钮在「自定义 + 已有生效档」时同时更新该档，避免两处值不一致。

## 2. 模型清单不写死（远程刷新）

- 网络层：`DirectLlmClient.listModels(baseUrl, apiKey)` → `GET {base}/models`。
  - 端点归一化 `buildModelsEndpoint`：剥掉粘贴进来的 `/chat/completions` 尾巴；Anthropic 端点（含 anthropic/claude 或 `/v1/messages` 结尾）走 `{base}/v1/models` 且用 `x-api-key` 鉴权；其余走 `{base}/models`。
  - 解析 `parseModelIds`：兼容 `{"data":[{"id":…}]}`、`{"models":[…]}`、裸数组；剥 `models/` 前缀；去重去空。
  - 失败一律抛异常（含 HTTP 状态与响应片段），由 UI 显示——不做静默回退，否则用户会以为「刷新成功但没变化」。
- 缓存：DataStore `model_list_cache`（`Map<key, CachedModelList(fetchedAt, models)>`）。缓存键 `modelCacheKey(providerId, profileId)`：预设按 `preset:<id>`，自定义按 `custom:<档 id>`（不同中转站清单完全不同，共用 key 会互相覆盖）。
- UI：`ModelFetchRow`（缓存条数 + 拉取时间 + 「从服务商获取」按钮 + 结果/错误提示）挂在两处：预设供应商（模型下拉下方）与自定义端点（配置档下方）。预设模型下拉在原有内置清单之后追加「— 从服务商获取（N）—」分区，点选即用。

## 3. 小说阵容（主角 / 配角 + 随时增删）

- 数据：`novel_story` 新增 `castRolesJson TEXT NOT NULL DEFAULT '{}'`，Room **v11 → v12** 非破坏式迁移。`'{}'` = 尚未设置 → 读取端按成员顺序推导主角，**不写回库**（老故事零迁移成本）。
- 仓储（`NovelRepository`）：
  - `updateStoryCast(storyId, memberIds, npcs, castRoles)`：整包替换（三者互相牵制，逐项 API 会迫使调用方拼装中间态）；剔除已不在阵容里的定位，避免「删掉再加回来时旧定位复活」。
  - `decodeCast` / `resolveCastRoles` / `normalizeRole`：读侧容错 + 默认推导（显式设置 > NPC 内嵌旧 role > 第一位成员顶上主角）；NPC 用名字作 key（小说里名字就是唯一标识）。
  - `encodeCastRoles` 按 key 排序落库，同一份阵容字节一致（可 diff、可幂等重写）。
- 提示词：`NovelPromptBuilder.buildSystem(..., castProtagonists, castSupporting)` 新增 `[角色阵容]` 段（主角：叙事中心、视角与主线由其推动；配角：服务主线、戏份克制）。放在 system 稳定区——阵容只随故事设置变化，这样才能命中服务端前缀缓存，且**改阵容才会刷新缓存**。两个名单都为空时不输出（老故事提示词逐字节不变）。
- VM：`NovelEditorViewModel` 续写前经 `castOf(story)` 一次读全阵容（角色名 / 人设 / 定位），并按「主角在前」排序角色卡；`updateCast` 在故事页与编辑器两处入口都可用。
- UI：新文件 `ui/novel/NovelCastControls.kt`
  - `CastManagerSheet`：当前阵容（主角/配角药丸切换、移除）+ 添加区（搜索 + 勾选）+ 自定义 NPC 区 + 保存。草稿 copy-on-write；主角被删时顺位继任，避免阵容退化成「无主角」。
  - `castSummaryText`：故事页胶囊摘要（「苏晚：主角 · 配角 2 人」），点击即开阵容弹窗。
  - 入口：故事页顶栏「角色阵容」图标 + 摘要胶囊；对白编辑器顶栏「角色阵容」图标。
  - 新建故事弹窗：角色选择改为**有序 + 可搜索**，并可就地切换主角/配角（先选中的默认主角）。

## 4. 全入口可搜索选角

新文件 `ui/pickers/CharacterPickers.kt` 提供唯一一套共用件：

- `characterMatchesQuery(char, q)`：名称 / 代号 / id / 职位 / 种族 任一命中（大小写不敏感）。角色页原有的 `ui.characters.filterCharacters` 已改为委托同一函数，全仓库搜索口径统一。
- `CharacterSearchField`：玻璃搜索框 + 放大镜 + 命中数 + 空关键词不显示噪音。
- `CharacterMultiPicker` / `CharacterSinglePicker`：搜索 + 固定高度列表 + 选中态高亮 + 自定义徽标 + 职位。**过滤只影响显示，不改变调用方持有的已选集合**。
- 接入点（原先无搜索，现已全部可搜索）：
  1. 群聊新建群「选择成员」（多选，保留 2–10 人上限提示）
  2. 群聊「@ 选择器」（保留头像行，加搜索）
  3. 朋友圈「让角色发朋友圈」选角（保留立绘行，加搜索）
  4. 设置页「选择问候角色」（多选）
  5. 设置页「选择发圈角色」（多选）
  6. 设置页「选择互动角色」（原为仅匹配名称，现统一口径）
  7. 小说新建故事选角 + 阵容弹窗选角
- 已有搜索的入口（角色页 / 世界观 / 世界书生效范围）保持原样。

## 5. 音乐入口迁入设置，dock 原位置改为朋友圈

对应上游提交 `debc291 feat(ui): 音乐入口迁入设置，底部 dock 原位置改为朋友圈`，按本仓库结构落地：

- dock：`BottomTab.Music` → `BottomTab.Moments`（route `moments`，label 朋友圈，`PhotoLibrary` 细线/实心图标）。`tabs` 列表同步为 通讯 / 角色 / 朋友圈 / 模型 / 设置。
- 音乐改为二级页：新增 `MUSIC_ROUTE = "music"`，由「设置 → 音乐」进入，返回键回设置页；`MusicScreen(container, onBack: (() -> Unit)? = null)` 用 `GlassLargeTitle` 新增的 `leading` 槽渲染返回键（为 null 时不渲染，布局与旧版一致）。
- 朋友圈作为 dock 根页：`MomentsScreen(onBack = null)` 隐藏顶栏返回键（无上一级）；从卡片流进入时仍传 `popBackStack` 显示返回键。根页不套 `tabBottomPadding`（与通讯 Tab 一样全出血沉浸），底栏留白由页面内部的 `bottomBarHeight` 垫。
- `GlassLargeTitle` 新增 `leading` 槽（二级页返回按钮），尾部 `actions` 槽不变。
- 设置页新增「音乐」行（播放列表 · 本地导入 · 在线搜索），放在「使用指南」下方。
- 使用指南同步：dock 五入口说明改为「通讯 / 角色 / 朋友圈 / 模型 / 设置；音乐在「设置 → 音乐」里」，两处「音乐页…」改为「「设置 → 音乐」…」。
- 音乐后台播放不受影响（`AudioManager` 与入口位置无关）；卡片流顶栏的「朋友圈」按钮保留（等于第二条进入路径，带返回键）。

## 6. 小说：用户发言后按该角色的人设推进剧情（开关控制，默认关）

问题：原先在编辑器里选角色写一行只是「往正文里塞一行字」，不触发任何推进——用户想的是「我用 A 发言，剧情就该顺着 A 的路子往前走」。

**默认保持原逻辑**（发送只追加该行、不触发生成），把「发送后自动续写」做成用户可选的开关并**持久化**（DataStore 键 `novel_auto_continue`，默认 false）——否则离开编辑器又变回去，就不算「让用户选择」。

- **开关**：编辑器发言人条下方一行药丸，默认显示「仅添加，不自动续写」，打开后显示「发送后自动续写」；经 `NovelEditorViewModel.setAutoContinue()` 写入 DataStore，跨会话生效。
- **打开后发送即推进**：`addLine(..., continueAfter = true)` 落库后立刻 `continuePlot()`。
- **手动点 ✨AI 同样顺着你刚写的那行推进**：该行会记入 VM 的 `pendingDirected`，所以「写完那句再点 AI」也是「按这个角色的人设往下写」；**只有真正把续写内容落库后才清空**，失败/停止时保留以便重试。（若希望关掉开关时连这个也回到完全原样，说一声即可改成只对自动续写生效。）
- **按发言身份差异化推进**（`NovelPromptBuilder.DirectedLine` + `appendDirectedLine`，单独成段 `[用户刚写下的一行 · 本次要顺着它推进]`）：
  - **角色发言**（用户选 A → 就是 A 的人设说了算）：这一行是 A 亲口说的**既定立场**，后续言行必须严格贴合其人设（性格/语气/立场/说话习惯），**不得 OOC、不得自相矛盾，也不要让他马上改口反悔**；剧情由这一行往下走，其他角色按各自人设反应（赞同/反对/追问/沉默皆可）并引出新的行动、信息或冲突，而不是原地重复气氛。若 A 在阵容里是**主角** → 追加「剧情重心与描写视角围绕他展开，这一行是本段推进主轴」；是**配角** → 追加「戏份保持克制、不抢主角主导权，但要让主线确实向前一步」（与 system 的「角色阵容」主次约定一致）。
  - **旁白**（用户写的场景）：当作**已经发生的事实**，从这个场景继续写，角色按人设对场景变化做出反应。
  - **主控**（用户本人）：各角色按各自人设回应，围绕他的行动推进主线。
  - 结尾统一加「不要复述用户这一行、不要解释或总结它，直接写新内容」。
- **避免复述**：续写指令改为「从「A：<用户那行前 40 字>…」之后接着写」，否则模型容易把用户刚写的那行当成待补全上下文而复述。
- 阵容定位在续写开始时从当前故事读取（用户在弹窗里改完阵容，下一次续写即生效）；定位取不到（未设阵容）时不加主角/配角附加要求。

## 7. 小说行可改 / 可移 / 可删后再续写

原先脚本行只能改内容或删除，**顺序无法调整**（章节有上移/下移，行没有）。补齐：

- `NovelDao.swapLineOrder`：相邻两行 lineOrder 互换，`@Transaction` 原子完成，且与章节的 `swapChapterOrder` 同一套「先把 first 挪到 -1 错开」写法（值本身无唯一索引，但保持确定性便于排查）。`novel_line` 无 updatedAt 列，故不传时间戳。
- `NovelRepository.moveLine(chapterId, lineId, delta)`：按 `getLines` 的**当前显示顺序**取相邻行，所以「先删几行再移动」也不会因序号空洞而错位；越界/非法 delta 一律 no-op。
- 纯函数 `NovelRepository.swapTargetId(orderedLineIds, lineId, delta)` 承载目标行计算，JVM 单测覆盖首行上移、尾行下移、非法 delta、未知 id、空章、单行章。
- 编辑器行编辑弹窗底部新增「↑ 上移 / ↓ 下移」（首/尾自动置灰）；改完/移完再点 ✨AI 即按新顺序续写（续写每次现读 `getLines`）。
- **方向与编辑的一致性**（`pendingDirected` 卫生）：
  - 新增 `pendingDirectedLineId` 记住方向对应的行；改写这一行 → 方向文本同步更新（改完错字再点 AI，模型收到的锚点不是旧文案）；删除这一行 → 方向一并清空。
  - `continuePlot` 增加**尾部校验**：只有「用户写的那行仍排在正文最后」时才注入「从它之后接着写」的锚点；若用户已把它移走/删掉、或把别的行移到它后面，则退化为普通续写并把方向清掉——避免把模型引回错误位置。

## 8. 群聊：@ 用法提醒 + 成员随时增删

- **@ 提醒**：输入框为空时显示一行提示「输入 @ 可以指定成员回答：@ 谁谁答，其余成员不抢答」——把定向回答的语义说清楚（旧 UI 只在输入 @ 时弹选择器，用户不知道 @ 等于「只让谁答」）；开始打字后提示自动让位给内容，不长期占版面。
- **成员随时增删**：群信息弹窗（点群名/成员数进入）新增成员区——
  - 成员列表（固定高度内滚动，成员多不撑爆弹窗）+ 每行「移出」；`MIN_GROUP_MEMBERS = 2` 时移出按钮置灰并给文案（单人聊天走角色页单聊）。
  - 「增删成员」打开全局可搜索多选器（`CharacterMultiPicker`，口径与新建群一致：名称/代号/职位/种族）；超过 `AppConfig.GroupChat.MAX_MEMBERS`(=10) 时 Toast + 行内提示。
  - 成员与名称/封面同一个「保存」语义落库：`GroupChatRepository.setGroupMembers`（去重 + 截断到上限，数据层不做「最少 2 人」硬校验以免历史数据打不开）→ VM `updateMembers` → `reloadGroup()`，成员条与后续发言名单立即更新。
  - 已发出的历史消息按行级 `characterId` 快照渲染，移出成员不影响旧消息（未知成员回退「群聊成员」）。

## 验证

- `:app:compileDebugKotlin` ✅、`:app:assembleDebug` ✅
- `:app:testDebugUnitTest`：**960 条，2 条失败**——均为 `MnnJniContractTest`（`MnnRuntimeInfo.fromJson` 走 `org.json`，JVM 单测下构造即抛异常），已在基线提交 `84f23c4` 单独复现，属既有欠账，与本次改动无关。
- 新增单测：
  - `DirectLlmModelListTest`（7 条）：端点归一化（OpenAI / 尾斜杠 / 粘贴完整端点 / Anthropic 两种写法）、三种响应形态解析、去重、垃圾输入返回空表。
  - `CharacterPickersQueryTest`（4 条）：五字段匹配 + 大小写、空查询返回原列表、不命中排除、查询去空格。
  - `NovelPromptBuilderTest` 新增 6 条：不传用户发言时无推进段、角色发言的贴人设与「不得 OOC + 必须引出新推进」约束、主角/配角差异化提示、未设阵容时不加定位提示、旁白 vs 主控语义区分、空白内容不产生段落。
  - `NovelLineMoveTest`（8 条）：行上移/下移与四类 no-op 边界（首行上移、尾行下移、未知 id、非法 delta、空章、单行章）。
