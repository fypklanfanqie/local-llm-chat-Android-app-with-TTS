package com.chatbyyourside.data.repository

import com.chatbyyourside.config.AppConfig
import com.chatbyyourside.data.local.NovelChapterEntity
import com.chatbyyourside.data.local.NovelDao
import com.chatbyyourside.data.local.NovelLineEntity
import com.chatbyyourside.data.local.NovelStoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 小说模式仓库：故事/章节/脚本行的业务包装。
 * 行追加自动接 max(lineOrder)+1；章节新建自动接尾（maxChapterOrder+1）。
 *
 * 阵容（主角/配角）读写也在本类：定位表以「应用角色 id / 自定义 NPC 名字」为 key，
 * 读取端负责推导默认值（见 [resolveCastRoles]），落库端只存显式设置。
 */
class NovelRepository(
    private val dao: NovelDao,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // 显式 reified 编码器：Map<String, String> 无需 @Serializable 包装类
    private val rolesSerializer = MapSerializer(String.serializer(), String.serializer())

    // ===== 故事 =====

    fun observeStories(): Flow<List<NovelStoryEntity>> = dao.observeStories()

    suspend fun getStory(storyId: Long): NovelStoryEntity? = dao.getStory(storyId)

    fun observeStory(storyId: Long): Flow<NovelStoryEntity?> = dao.observeStory(storyId)

    suspend fun createStory(
        title: String,
        background: String,
        memberIds: List<String>,
        customNpcs: List<CustomNpc>,
        protagonistName: String,
        protagonistPersona: String,
        /** 角色阵容定位（角色 id / NPC 名 → 主角|配角）；空 = 未指定，读取端按成员顺序推导。 */
        castRoles: Map<String, String> = emptyMap(),
    ): Long {
        require(title.isNotBlank()) { "故事名不能为空" }
        val now = System.currentTimeMillis()
        return dao.insertStory(
            NovelStoryEntity(
                title = title.trim(),
                background = background.trim(),
                memberIdsJson = json.encodeToString(memberIds),
                customNpcsJson = json.encodeToString(customNpcs),
                castRolesJson = encodeCastRoles(castRoles),
                protagonistName = protagonistName.trim(),
                protagonistPersona = protagonistPersona.trim(),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun updateStory(story: NovelStoryEntity) {
        dao.updateStory(story.copy(updatedAt = System.currentTimeMillis()))
    }

    /**
     * 写作过程中一次性改阵容：成员 / 自定义 NPC / 主角配角定位。
     *
     * 为什么整包替换而不是逐项增删：三者互相牵制（删角色要清定位、换主角要降级旧主角），
     * 拆成多个 API 会让调用方各自拼装中间态；整包写一次、只 touch 一次 updatedAt，
     * UI 侧观察 story Flow 即拿到最终一致状态。
     *
     * 历史行不受影响：novel_line.speakerName 是显示名快照，删角色不会回改既有正文。
     */
    suspend fun updateStoryCast(
        storyId: Long,
        memberIds: List<String>,
        npcs: List<CustomNpc>,
        castRoles: Map<String, String>,
    ) {
        val story = dao.getStory(storyId) ?: throw IllegalStateException("故事不存在")
        val cleanMembers = memberIds.distinct()
        val cleanNpcs = npcs.filter { it.name.isNotBlank() }
        // 剔除已不在阵容里的定位：否则删掉角色再加入时，旧定位会意外「复活」
        val keptKeys = cleanMembers.toSet() + cleanNpcs.map { it.name.trim() }
        val normalized = castRoles.filterKeys { it in keptKeys }.mapValues { normalizeRole(it.value) }
        dao.updateStory(
            story.copy(
                memberIdsJson = json.encodeToString(cleanMembers),
                customNpcsJson = json.encodeToString(cleanNpcs),
                castRolesJson = encodeCastRoles(normalized),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteStory(storyId: Long) = dao.deleteStoryCascade(storyId)

    fun decodeMemberIds(story: NovelStoryEntity): List<String> =
        runCatching { json.decodeFromString<List<String>>(story.memberIdsJson) }.getOrDefault(emptyList())

    fun decodeCustomNpcs(story: NovelStoryEntity): List<CustomNpc> =
        runCatching { json.decodeFromString<List<CustomNpc>>(story.customNpcsJson) }.getOrDefault(emptyList())

    // ===== 阵容（主角 / 配角）=====

    /**
     * 定位映射：key 对应用角色用 id、自定义 NPC 用名字。
     *
     * 读侧容错：castRolesJson 为 '{}' / 缺失 / 损坏时统一返回空表，由 [resolveCastRoles] 推导默认值，
     * **不写回库**——老故事（迁移默认 '{}'）因此无需任何补偿写入。
     */
    fun decodeCastRoles(story: NovelStoryEntity): Map<String, String> =
        runCatching { json.decodeFromString<Map<String, String>>(story.castRolesJson) }
            .getOrDefault(emptyMap())
            .filterKeys { it.isNotBlank() }
            .mapValues { normalizeRole(it.value) }

    /**
     * 阵容定位（含默认推导）：
     * - 显式设置优先（castRolesJson）；
     * - NPC 的旧式内嵌 role（[CustomNpc.role]）作为次选，兼容只写 NPC 侧字段的历史数据；
     * - 仍无定位者：未指定主角时第一位成员顶上主角，其余配角。
     *
     * 纯函数，读时不落库，同一份数据每次调用结果一致（system 稳定区因此逐字节稳定）。
     */
    fun resolveCastRoles(
        memberIds: List<String>,
        npcs: List<CustomNpc>,
        castRoles: Map<String, String>,
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        memberIds.forEach { id -> result[id] = castRoles[id] ?: ROLE_SUPPORTING }
        npcs.forEach { npc ->
            val name = npc.name.trim()
            if (name.isNotEmpty()) result[name] = castRoles[name] ?: normalizeRole(npc.role)
        }
        if (result.isNotEmpty() && result.values.none { it == ROLE_PROTAGONIST }) {
            val firstMember = memberIds.firstOrNull()
            if (firstMember != null) {
                result[firstMember] = ROLE_PROTAGONIST
            } else {
                // 只有自定义 NPC 的故事：第一位 NPC 顶上，避免「无主角」的退化阵容
                npcs.firstOrNull { it.name.isNotBlank() }?.let { result[it.name.trim()] = ROLE_PROTAGONIST }
            }
        }
        return result
    }

    /** 便利封装：[resolveCastRoles] 的「从故事读取」版本。 */
    fun resolveCastRoles(story: NovelStoryEntity): Map<String, String> =
        resolveCastRoles(decodeMemberIds(story), decodeCustomNpcs(story), decodeCastRoles(story))

    /** 一步读全阵容（成员 / NPC / 定位），UI 与提示词构建共用，避免三处各解一次 JSON。 */
    fun decodeCast(story: NovelStoryEntity): StoryCast {
        val memberIds = decodeMemberIds(story)
        val npcs = decodeCustomNpcs(story)
        return StoryCast(
            memberIds = memberIds,
            npcs = npcs,
            roles = resolveCastRoles(memberIds, npcs, decodeCastRoles(story)),
        )
    }

    /**
     * 规范化定位字符串：只认「主角 / 配角」两态。
     *
     * role 字段在 [CustomNpc] 里是可空 String（而非枚举）——JSON 里可能是任意历史值，
     * 这里统一兜底为配角，避免脏值渗进提示词。
     */
    fun normalizeRole(raw: String?): String =
        if (raw?.trim() == ROLE_PROTAGONIST) ROLE_PROTAGONIST else ROLE_SUPPORTING

    fun isProtagonist(castRoles: Map<String, String>, key: String): Boolean =
        castRoles[key] == ROLE_PROTAGONIST

    /** 编码定位表：按 key 排序保证同一份阵容每次落库字节一致（便于 diff 与幂等写入）。 */
    private fun encodeCastRoles(castRoles: Map<String, String>): String =
        json.encodeToString(
            rolesSerializer,
            castRoles.filterKeys { it.isNotBlank() }
                .mapValues { normalizeRole(it.value) }
                .toSortedMap(),
        )

    // ===== 章节 =====

    fun observeChapters(storyId: Long): Flow<List<NovelChapterEntity>> = dao.observeChapters(storyId)

    fun observeChapterWithLines(chapterId: Long): Flow<com.chatbyyourside.data.local.NovelChapterWithLines?> =
        dao.observeChapterWithLines(chapterId)

    suspend fun getChapter(chapterId: Long): NovelChapterEntity? = dao.getChapter(chapterId)

    suspend fun createChapter(storyId: Long, title: String = ""): Long {
        val now = System.currentTimeMillis()
        val nextOrder = (dao.maxChapterOrder(storyId) ?: -1) + 1
        return dao.insertChapter(
            NovelChapterEntity(
                storyId = storyId,
                orderIndex = nextOrder,
                title = title.ifBlank { "第 ${nextOrder + 1} 话" },
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /** 保存本话设定；若本话无正文且开场白非空，开场白自动落为第一行旁白。 */
    suspend fun saveChapterSetting(chapter: NovelChapterEntity) {
        dao.updateChapter(chapter.copy(updatedAt = System.currentTimeMillis()))
        val existing = dao.getLines(chapter.id)
        if (existing.isEmpty() && chapter.opening.isNotBlank()) {
            appendLine(
                NovelLineEntity(
                    chapterId = chapter.id,
                    lineOrder = 0,
                    speakerType = com.chatbyyourside.llm.NovelScriptParser.TYPE_NARRATION,
                    speakerName = "旁白",
                    content = chapter.opening.trim(),
                ),
            )
        }
    }

    /** 上移/下移：与相邻章节换 orderIndex。 */
    suspend fun moveChapter(chapter: NovelChapterEntity, chapters: List<NovelChapterEntity>, delta: Int) {
        val index = chapters.indexOfFirst { it.id == chapter.id }
        val targetIndex = index + delta
        if (index < 0 || targetIndex !in chapters.indices) return
        val target = chapters[targetIndex]
        dao.swapChapterOrder(chapter.id, target.id, chapter.orderIndex, target.orderIndex)
    }

    suspend fun deleteChapter(chapterId: Long) {
        dao.deleteChapterCascade(chapterId)
        // 删除后可重排剩余章节 orderIndex 为 0..n（保持列表紧凑；读侧本就按 orderIndex 排序，可选优化）
    }

    // ===== 行 =====

    suspend fun getLines(chapterId: Long): List<NovelLineEntity> = dao.getLines(chapterId)

    /** 追加一行（自动接尾）。超出每话行数上限时拒绝。 */
    suspend fun appendLine(line: NovelLineEntity): Long {
        val count = dao.getLines(line.chapterId).size
        if (count >= AppConfig.Novel.MAX_LINES_PER_CHAPTER) {
            throw IllegalStateException("本话已达 ${AppConfig.Novel.MAX_LINES_PER_CHAPTER} 行上限")
        }
        val nextOrder = (dao.maxLineOrder(line.chapterId) ?: -1) + 1
        return dao.insertLine(
            line.copy(
                lineOrder = nextOrder,
                content = line.content.take(AppConfig.Novel.LINE_MAX_CHARS),
            ),
        )
    }

    suspend fun updateLine(line: NovelLineEntity) {
        dao.updateLine(line.copy(content = line.content.take(AppConfig.Novel.LINE_MAX_CHARS)))
    }

    suspend fun deleteLine(lineId: Long) = dao.deleteLine(lineId)

    /**
     * 上移/下移一行：与相邻行交换 lineOrder（同事务原子）。
     *
     * [delta] 只接受 ±1；越界（已在首/尾）静默 no-op——UI 侧同样把按钮置灰，
     * 这里再兜一次避免越界时把顺序搅乱。顺序按 [NovelDao.getLines] 的当前排序取，
     * 所以「先删几行再移动」也不会因为序号有空洞而错位。
     */
    suspend fun moveLine(chapterId: Long, lineId: Long, delta: Int) {
        val lines = dao.getLines(chapterId)
        val targetId = swapTargetId(lines.map { it.id }, lineId, delta) ?: return
        val current = lines.first { it.id == lineId }
        val target = lines.first { it.id == targetId }
        dao.swapLineOrder(current.id, target.id, current.lineOrder, target.lineOrder)
    }

    /** 批量追加 AI 续写产物（保持相对顺序）。 */
    suspend fun appendLines(chapterId: Long, lines: List<NovelLineEntity>) {
        lines.forEach { appendLine(it.copy(chapterId = chapterId)) }
    }

    @Serializable
    data class CustomNpc(
        val name: String,
        val persona: String = "",
        /**
         * 定位（主角 / 配角）——可空字符串而非枚举：旧数据没有这个字段，
         * `ignoreUnknownKeys` + 默认值让老 JSON 照常解析；空值由 [resolveCastRoles] 兜底。
         */
        val role: String? = null,
    )

    /** 故事阵容快照：成员 id、自定义 NPC（含内嵌定位）、解析后的定位表（key = 角色 id / NPC 名）。 */
    data class StoryCast(
        val memberIds: List<String> = emptyList(),
        val npcs: List<CustomNpc> = emptyList(),
        val roles: Map<String, String> = emptyMap(),
    )

    companion object {
        /** 主角：叙事中心，视角/心理描写以他为主并推动主线。 */
        const val ROLE_PROTAGONIST = "protagonist"

        /** 配角：服务主线，戏份克制。 */
        const val ROLE_SUPPORTING = "supporting"

        /**
         * 计算「与 [lineId] 相邻、需要交换顺序」的那一行的 id（纯函数，JVM 可测）。
         *
         * @param orderedLineIds 本章行 id，**按当前显示顺序**排列
         * @param delta -1 = 上移（与上一行交换）、+1 = 下移；其余值视为非法
         * @return 相邻行 id；越界（已是首/尾）、非法 delta、id 不存在时返回 null（调用方 no-op）
         */
        fun swapTargetId(orderedLineIds: List<Long>, lineId: Long, delta: Int): Long? {
            if (delta != 1 && delta != -1) return null
            val index = orderedLineIds.indexOf(lineId)
            if (index < 0) return null
            val targetIndex = index + delta
            if (targetIndex !in orderedLineIds.indices) return null
            return orderedLineIds[targetIndex]
        }
    }
}
