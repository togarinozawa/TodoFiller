package dev.togar.dynasched.ui

import dev.togar.dynasched.api.HobbyItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OyasugiSplitTest {

    private fun t(id: Long, parent: Long? = null, done: Boolean = false, min: Int = 30, created: String = "") =
        HobbyItem(id = id, name = "t$id", parentId = parent, isCompleted = done,
            durationMinutes = min, createdAt = created)

    @Test
    fun `葉は1、親を丸ごと片付けると親のぶんも入る`() {
        val before = listOf(t(1), t(2, 1, done = true), t(3, 1))
        assertEquals(listOf(Award(2, 1, false)), Oyasugi.awards(before, 2, emptySet()))
        val after = listOf(t(1), t(2, 1, done = true), t(3, 1, done = true))
        assertEquals(listOf(Award(3, 1, false), Award(1, 2, true)), Oyasugi.awards(after, 3, setOf(2L)))
        assertEquals("+3 オヤスギ（まとまり達成）", Oyasugi.popText(Oyasugi.awards(after, 3, setOf(2L))))
    }

    @Test
    fun `同じタスクで二度は入らない`() {
        val all = listOf(t(1, done = true))
        assertTrue(Oyasugi.awards(all, 1, setOf(1L)).isEmpty())
    }

    @Test
    fun `止まっているタスク`() {
        val today = LocalDate.of(2026, 10, 8)
        assertTrue(Stuck.isStuck(t(1, min = 60), false, today))
        assertTrue(Stuck.isStuck(t(1, created = "2026-09-30 10:00:00"), false, today))
        assertEquals(false, Stuck.isStuck(t(1, created = "2026-10-05 10:00:00"), false, today))
        assertEquals(false, Stuck.isStuck(t(1, min = 60), true, today))
    }

    @Test
    fun `1行1件で分ける。時間の無い行は残りを等分`() {
        val parts = Split.fromLines("- 下書き\n・図を描く 30分\n\n仕上げ", 90)
        assertEquals(listOf(SplitPart("下書き", 30), SplitPart("図を描く", 30), SplitPart("仕上げ", 30)), parts)
    }

    @Test
    fun `機械的に割る。端数は最後`() {
        // 20分ずつ＝個数を決めてから等分する（v46と同じ）。70分なら4つ、端数は最後
        assertEquals(listOf(17, 17, 17, 19), Split.byChunk(t(1, min = 70), 20).map { it.minutes })
        assertEquals(listOf("t1 1/3", "t1 2/3", "t1 3/3"), Split.byCount(t(1, min = 30), 3).map { it.name })
    }

    @Test
    fun `強調の書き方`() {
        assertEquals(listOf("a" to false, "b" to true, "c" to false), Emphasis.segments("a**b**c"))
    }

    @Test
    fun `今日の進み具合と続いた日数`() {
        val today = LocalDate.of(2026, 10, 8)
        fun done(day: String) = StatRow(completedAt = "$day 10:00:00", completed = true, name = "x")
        val rows = listOf(done("2026-10-08"), done("2026-10-07"), done("2026-10-05"), done("2026-10-04"))
        val p = Stats.today(rows, today, 3)
        assertEquals(1, p.done)
        assertEquals(Stats.Emphasis.DONE, p.emphasis)
        // 6日に1日休んだが、7日につき1日までは続いたことにする
        assertEquals(4, Stats.streak(rows, today))
        assertEquals(3, Stats.today(listOf(done("2026-10-08"), done("2026-10-08"), done("2026-10-08")), today, 3).big)
    }
}
