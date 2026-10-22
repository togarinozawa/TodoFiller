package dev.togar.dynasched

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 優先度の段。段を足し引きした時に**タスクの数字が別の段へずれる**と、
 * 「すぐやれ」にしたはずのタスクが後回しになる。画面からは気付けないので固定する。
 */
class PrioritiesTest {

    private val d = Priorities.DEFAULT   // 8 高 / 5 中 / 3 低

    @Test
    fun `段の間の値は近い段に寄せる。同じ近さなら上`() {
        assertEquals(8, Priorities.levelOf(d, 10).value)
        assertEquals(5, Priorities.levelOf(d, 6).value)
        assertEquals(3, Priorities.levelOf(d, 1).value)
        assertEquals(5, Priorities.levelOf(d, 4).value)   // 5 と 3 の真ん中は上へ
        assertEquals(0, Priorities.rankOf(d, 9))
        assertEquals(2, Priorities.rankOf(d, 2))
    }

    @Test
    fun `名前を変えるだけなら数字は動かない`() {
        val edited = d.map { it.copy(name = "x" + it.name, memo = "目安") }
        val (levels, map) = Priorities.plan(d, edited)
        assertEquals(listOf(8, 5, 3), levels.map { it.value })
        assertTrue(map.all { (k, v) -> k == v })
    }

    @Test
    fun `隙間に足せば今の数字はそのまま`() {
        val edited = listOf(d[0], PriorityLevel(0, "やや高"), d[1], d[2])
        val (levels, map) = Priorities.plan(d, edited)
        assertEquals(listOf(8, 7, 5, 3), levels.map { it.value })
        assertTrue(map.all { (k, v) -> k == v })
    }

    @Test
    fun `隙間が無ければ振り直し、タスクも付いていく`() {
        val tight = listOf(PriorityLevel(9, "1"), PriorityLevel(8, "2"))
        val edited = listOf(tight[0], PriorityLevel(0, "1.5"), tight[1])
        val (levels, map) = Priorities.plan(tight, edited)
        assertEquals(listOf(9, 5, 1), levels.map { it.value })
        assertEquals(9, Priorities.remap(tight, map, 9))
        assertEquals(1, Priorities.remap(tight, map, 8))   // 「2」の段は一番下へ移る
    }

    @Test
    fun `消した段のタスクは選んだ段へ`() {
        val (levels, map) = Priorities.plan(d, listOf(d[0], d[2]), movedTo = mapOf(5 to 8))
        assertEquals(listOf(8, 3), levels.map { it.value })
        assertEquals(8, Priorities.remap(d, map, 5))
        assertEquals(8, Priorities.remap(d, map, 6))   // 段の間の値も、寄せた先の段と一緒に動く
        assertEquals(3, Priorities.remap(d, map, 3))
    }

    @Test
    fun `一番上と一番下にも足せる`() {
        val (levels, _) = Priorities.plan(d, listOf(PriorityLevel(0, "最優先")) + d + PriorityLevel(0, "いつか"))
        assertEquals(listOf(9, 8, 5, 3, 2), levels.map { it.value })
    }

    @Test
    fun `壊れた設定は既定に戻る`() {
        assertEquals(d, Priorities.parse("{壊れ"))
        assertEquals(d, Priorities.parse(null))
        val round = Priorities.parse(Priorities.toJson(listOf(PriorityLevel(9, "1", "すぐやれ"))).toString())
        assertEquals("1（すぐやれ）", round.single().label)
    }
}
