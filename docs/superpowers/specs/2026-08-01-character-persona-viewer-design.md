# 角色「查看人设」设计

日期：2026-08-01
状态：已批准

## 1. 功能概览

在角色页（`CharactersScreen`）每个角色卡底部居中新增 **「ℹ 查看人设」** 按钮，点击后弹出 `GlassSheet` 底部抽屉，展示该角色的 **头像 + 姓名 + 人设正文**（身份 / 核心性格 / 语气特征），不展示代号、定位、种族、开场台词，也不展示「回答要求」等模型指令。所有角色（预设 + 自定义）均可查看。

## 2. 人设正文提取规则

新增纯函数 `extractPersonaBody(systemPrompt: String): String`：

- 定位 `systemPrompt` 中首个「回答要求」，取其之前的部分作为人设正文，去除首尾空白。
- 找不到「回答要求」（自定义角色自由文本）→ 返回全文。
- 放置位置：`ui/characters/` 包内的纯函数，便于复用与后续补单元测试。

## 3. UI 布局

### 3.1 按钮

- 位置：`CharacterCard` 底部居中（姓名 / 定位下方）。
- 样式：`Icons.Filled.Info`（material-icons **core** 内置，无需新增依赖）+ 文字「查看人设」，贴近现有辅助按钮样式。
- 交互：点击打开抽屉；Compose 嵌套 `clickable` 会自动消费点击事件，不会误触卡片的 `onSelect`。

### 3.2 抽屉

- 复用现有 `GlassSheet` 组件。
- 头部：小尺寸 `CharacterPortrait`（复用现有组件）+ 角色姓名。
- 正文：`verticalScroll` 容器内渲染 `extractPersonaBody` 的返回值，支持文本选择。
- 需将卡片当前计算好的 `imageUrl` 传入抽屉。

## 4. 状态与边界

- `CharactersScreen` 新增 `var showPersona by remember { mutableStateOf<Character?>(null) }`；卡片按钮设置它，抽屉读取并展示。
- 关闭方式：下滑手势 / 点击遮罩（`GlassSheet` 自带）。
- 自定义角色无「回答要求」段落 → 展示完整 `systemPrompt`。
- 按钮对所有角色（预设 + 自定义）显示。

## 5. 测试

项目当前无测试基建，按约定默认不加测试依赖。`extractPersonaBody` 保持纯函数，后续如用户要求可补单元测试。

## 6. 不做的事（YAGNI）

- 不在抽屉内加入「开始对话」等操作按钮。
- 不新增角色数据模型字段。
- 不改变角色卡现有布局与点击行为（仅新增按钮）。
