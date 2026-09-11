package com.chatbyyourside.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 小说脚本行「上移 / 下移」的目标行计算契约（纯函数）。
 *
 * 覆盖三类边界：首行上移、尾行下移、非法 delta 与不存在的 id 都必须是 no-op（返回 null）。
 */
class NovelLineMoveTest {

    private val lines = listOf(11L, 22L, 33L, 44L)

    @Test
    fun moveUp_swapsWithPreviousLine() {
        assertEquals(22L, NovelRepository.swapTargetId(lines, lineId = 33L, delta = -1))
        // 等价于：33 与 22 交换 → 顺序变成 11,33,22,44
    }

    @Test
    fun moveDown_swapsWithNextLine() {
        assertEquals(44L, NovelRepository.swapTargetId(lines, lineId = 33L, delta = 1))
    }

    @Test
    fun firstLineCannotMoveUp() {
        assertNull(NovelRepository.swapTargetId(lines, lineId = 11L, delta = -1))
    }

    @Test
    fun lastLineCannotMoveDown() {
        assertNull(NovelRepository.swapTargetId(lines, lineId = 44L, delta = 1))
    }

    @Test
    fun unknownLineIdIsNoOp() {
        assertNull(NovelRepository.swapTargetId(lines, lineId = 999L, delta = 1))
        assertNull(NovelRepository.swapTargetId(lines, lineId = 999L, delta = -1))
    }

    @Test
    fun invalidDeltaIsNoOp() {
        // 只接受 ±1：0 或跳两格都被拒绝，避免把顺序搅乱
        assertNull(NovelRepository.swapTargetId(lines, lineId = 33L, delta = 0))
        assertNull(NovelRepository.swapTargetId(lines, lineId = 33L, delta = 2))
        assertNull(NovelRepository.swapTargetId(lines, lineId = 33L, delta = -2))
    }

    @Test
    fun emptyChapterIsNoOp() {
        assertNull(NovelRepository.swapTargetId(emptyList(), lineId = 1L, delta = 1))
    }

    @Test
    fun singleLineChapterCannotMoveEitherWay() {
        val one = listOf(7L)
        assertNull(NovelRepository.swapTargetId(one, 7L, -1))
        assertNull(NovelRepository.swapTargetId(one, 7L, 1))
    }
}
