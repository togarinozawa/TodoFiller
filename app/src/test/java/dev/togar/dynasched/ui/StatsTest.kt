package dev.togar.dynasched.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * 片付いた数・増えた数の数え方。
 *
 * **境界で静かにずれる類。**今日の分は「今日の0時以降」であって24時間以内ではないし、
 * 「7日」は今日を含めて7日。1日ずれても画面は普通に数字を出すので、目では気付けない。
 */
class StatsTest {

    private val today = LocalDate.of(2026, 9, 13)

    private fun row(created: String? = null, doneAt: String? = null, done: Boolean = false) =
        StatRow(createdAt = created, completedAt = doneAt, completed = done)

    @Test
    fun 今日は今日の0時から数える() {
        val from = Stats.from(today, Stats.Span.TODAY)
        assertEquals("2026-09-13", from)
        val rows = listOf(
            row(created = "2026-09-13 00:00:01"),          // 今日の始まり。入る
            row(created = "2026-09-12 23:59:59"),          // 昨日の終わり。入らない
            row(created = "2026-09-13 23:59:59")           // 今日の終わり。入る
        )
        assertEquals(2, Stats.count(rows, from).added)
    }

    @Test
    fun 七日は今日を含めて七日() {
        // 7日前からにすると8日分になる
        assertEquals("2026-09-07", Stats.from(today, Stats.Span.WEEK))
        assertEquals("2026-08-15", Stats.from(today, Stats.Span.MONTH))
    }

    @Test
    fun 済んだ数は完了日時で数える() {
        val rows = listOf(
            row(doneAt = "2026-09-13 10:00:00", done = true),
            row(doneAt = "2026-09-01 10:00:00", done = true),   // 期間外
            row(doneAt = "2026-09-13 10:00:00", done = false)   // 日時はあるが未完了
        )
        assertEquals(1, Stats.count(rows, "2026-09-13").done)
    }

    @Test
    fun 残りは期間と関係なく今の数() {
        val rows = listOf(
            row(done = true, doneAt = "2020-01-01 00:00:00"),
            row(done = false),
            row(done = false)
        )
        assertEquals(2, Stats.count(rows, "2026-09-13").remaining)
    }

    @Test
    fun 作成日時が無い行は増えた数に入れない() {
        // 列を後から足したので、それ以前のタスクには記録が無い。
        // 古いタスクで今日の数字を水増ししない
        val rows = listOf(row(created = null), row(created = ""), row(created = "不明"))
        assertEquals(0, Stats.count(rows, "2026-09-13").added)
        assertEquals(3, Stats.count(rows, "2026-09-13").remaining)
    }

    @Test
    fun 何も無ければ全部ゼロ() {
        assertEquals(TaskStats(), Stats.count(emptyList(), "2026-09-13"))
    }

    @Test
    fun 一行の表示() {
        assertEquals("済 2 ・ 追加 3 ・ 残り 5", TaskStats(added = 3, done = 2, remaining = 5).line())
    }
}
