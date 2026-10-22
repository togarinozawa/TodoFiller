package dev.togar.dynasched.ui

import dev.togar.dynasched.api.HobbyItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 検索欄と条件チップの絞り込み。
 *
 * ここがずれると**あるはずのタスクが出てこない**か、関係ないものが混ざる。
 * 同じ形を配置の対象にも使うので、ずれるとカレンダーに置かれる物まで変わる。
 */
class TaskFilterTest {

    private fun t(
        id: Long, name: String, parent: Long? = null, loc: String = "home",
        prio: Int = 5, tags: String = "", routine: Long = 0, note: String = ""
    ) = HobbyItem(
        id = id, name = name, parentId = parent, isCompleted = false, location = loc,
        priority = prio, tags = tags, routineId = routine, note = note
    )

    private val items = listOf(
        t(1, "英語"),
        t(2, "単語帳", 1, loc = "out", prio = 8, tags = "英語"),
        t(3, "長文", 1, prio = 5, tags = "英語"),
        t(4, "筋トレ", prio = 3, routine = 7, tags = "健康"),
        t(5, "買い物", loc = "out", prio = 8, note = "牛乳と卵"),
        t(6, "ストレッチ", loc = "anywhere", routine = 8, tags = "健康")
    )

    private fun ids(f: TaskFilter) = f.apply(items).map { it.id }

    @Test
    fun `条件が無ければ全部`() {
        assertTrue(TaskFilter().isEmpty)
        assertEquals(items.map { it.id }, ids(TaskFilter()))
    }

    @Test
    fun `習慣だけ・習慣以外`() {
        assertEquals(listOf(4L, 6L), ids(TaskFilter(habit = TaskFilter.Habit.ONLY)))
        // 親は習慣ではないが、配下に習慣以外があるので並びのために残る
        assertEquals(listOf(1L, 2L, 3L, 5L), ids(TaskFilter(habit = TaskFilter.Habit.EXCEPT)))
    }

    @Test
    fun `条件どうしは掛け合わせ、条件の中は「または」`() {
        // 外 × 優先度高 … 単語帳と買い物
        assertEquals(listOf(1L, 2L, 5L), ids(TaskFilter(places = setOf("out"), priorities = setOf(8))))
        // 外 × 優先度高 × #英語 … 単語帳だけ
        assertEquals(listOf(1L, 2L),
            ids(TaskFilter(places = setOf("out"), priorities = setOf(8), tags = setOf("英語"))))
        // 優先度 高 または 低
        assertEquals(listOf(1L, 2L, 4L, 5L), ids(TaskFilter(priorities = setOf(8, 3))))
    }

    @Test
    fun `検索はメモにも、親の名前にも当たる`() {
        assertEquals(listOf(5L), ids(TaskFilter(text = "牛乳")))
        // 「英語」はグループの名前。配下ぜんぶ出る
        assertEquals(listOf(1L, 2L, 3L), ids(TaskFilter(text = "英語")))
        assertEquals(emptyList<Long>(), ids(TaskFilter(text = "存在しない")))
    }

    @Test
    fun `グループで絞ると塊そのものは出さない`() {
        assertEquals(listOf(2L, 3L), ids(TaskFilter(groupId = 1L)))
        // 消えたグループで絞ったままでも、全部出る（何も出ないよりまし）
        assertEquals(items.map { it.id }, ids(TaskFilter(groupId = 999L)))
    }

    @Test
    fun `配置に回すのは葉だけ`() {
        assertEquals(setOf(2L, 3L), TaskFilter(tags = setOf("英語")).leafIds(items))
    }

    @Test
    fun `書き出して読み戻すと同じ`() {
        val f = TaskFilter(
            text = "英", habit = TaskFilter.Habit.ONLY, places = setOf("out", "p1"),
            priorities = setOf(8), tags = setOf("英語", "健康"), groupId = 1L
        )
        assertEquals(f, TaskFilter.decode(f.encode()))
        assertEquals(TaskFilter(), TaskFilter.decode("壊れた{"))
        assertEquals(TaskFilter(), TaskFilter.decode(null))
    }
}
