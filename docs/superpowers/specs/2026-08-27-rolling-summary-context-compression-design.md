# 滚动摘要上下文压缩（单聊云端）— 设计文档

日期：2026-08-27
状态：已批准（用户确认：云端生成摘要 / 后台自动折叠 / 常开无开关 / 每 40 轮折叠一次）

## 背景与目标

云端前缀缓存要求「前缀只增不变」。现有实现用 `takeLast(300)` 硬截断，会话超过 150 轮后头部开始逐轮滑动 → 缓存整窗失效且信息永久丢失。本设计引入**滚动摘要**：

```
稳定前缀（缓存锚）：
  [system: 人设+世界书静态头]      ← 逐字节不变
  [system: 【前情提要】…]          ← 仅每 40 轮折叠那一刻变一次

追加区（纯增长）：
  [未摘要的历史轮次…]
  [本轮新消息]
```

收益：
1. 两次折叠之间（整整 40 轮）前缀逐字节一致，历史部分缓存命中率 ≈ 97%；
2. 长对话每轮实际 input 从「最多 300 条」降到「摘要 + 最近 ~80 轮」，token 显著变少；
3. 被折叠轮次的信息进摘要而非删除，长程记忆质量提升。

## 折叠节奏（高/低水位）

- 计数锚：消息行的 `databaseId`（对中途删消息天然鲁棒），单位换算按 user+assistant ≈ 一轮 2 行。
- 高水位 **160 行（≈80 轮）**触发；一次折叠**最旧 80 行（≈40 轮）**；折完剩 ≥80 行原文。
- 效果：每约 40 轮发生一次摘要更新与一次前缀断裂，其余时间纯追加。

## 存储（Room v8→v9）

`ConversationEntity` 新增两列（ALTER TABLE 非破坏迁移，旧行默认值回退）：

| 列 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `summaryText` | TEXT NOT NULL | `''` | 当前「前情提要」正文 |
| `summarizedUpToMessageId` | INTEGER NOT NULL | `0` | 已被摘要覆盖的最大 chat_history.id |

新增 `MIGRATION_8_9` 并加入 `.addMigrations(...)` 链（现链尾为 7→8）。Repository 提供读取与写回。

## 组装（ChatViewModel.sendMessage 云端路径）

在现有 `apiMessages` 构建处（`lorebookStaticHead` system 之后、历史之前）插入一条摘要 system 消息：

```kotlin
if (summaryText.isNotBlank()) {
    apiMessages.add(ChatMessage(role = "system", content = "【前情提要】以下是此前对话的脉络概要：\n$summaryText"))
}
```

- 放在 position 1（紧随静态 system）：折叠变化只使其后的内容重算一次，人设头可由公共前缀检测独立持续命中。
- 历史部分改为取 `databaseId > summarizedUpToMessageId` 的行再接现有裁剪逻辑（300 条兜底不变）。
- 本地路径完全不走此分支；群聊 / GreetingWorker / 礼物道谢等旁路调用不受影响。

## 摘要生成（后台协程）

触发点：助手回复落库完成后（finalizeAssistant 之后的 viewModelScope 协程内），条件满足时执行：

1. 取旧摘要 + 待折叠的 80 行文本；
2. 调当前配置的云端模型（DirectLlmClient.chatOnce），提示词要求中文 ≤300 字，
   保留人物关系、承诺、未决伏笔、情绪基调与时空连续性；
3. 成功 → 事务性写回两列（summaryText 新值 + summarizedUpToMessageId=该批最大 id）；
4. 失败/超时（30s）→ Log.w 并放弃本次折叠，下一阈值自然重试；本轮请求不受影响
   （折叠发生在本轮发送完成之后，绝不阻塞聊天或改变本轮 payload）。

竞态防护：单会话折叠用 Mutex 串行化；并发发送时仅首个跨阈值者执行。

## 错误处理

| 场景 | 行为 |
|---|---|
| 摘要 API 失败/超时 | 维持现状硬截断继续聊，下轮再试 |
| summarizedUpToMessageId 对应行已被 DB 裁剪删除 | 以现存最小 id 为准自然收敛，无需特判 |
| 用户删除已摘要的旧消息 | 无影响（这些行本就不进 payload） |
| 新装/旧库升级 | 两列默认值 → 功能静默从零开始累积 |

## 测试要点

- 折叠水位计算（含 id 空洞、极短会话、首次折叠无旧摘要）
- 组装结果快照：折叠前后相邻两次请求，非折叠轮次 messages 头部逐字节一致（缓存契约）
- 迁移 v8→v9 后旧行为默认值、功能正常累积
- 纯 JVM 单测优先（SummaryState 计算 / prompt 组装）；存储层走现有 DAO 测试模式

## 明确不做（YAGNI）

- 不做设置开关（常开）；不做本地推理压缩（PromptWindowPlanner 另有机制）
- 不做群聊/问候/礼物路径；不动 MAX_CONTEXT_MESSAGES=300 与 DB 400 上限（兜底保留）
