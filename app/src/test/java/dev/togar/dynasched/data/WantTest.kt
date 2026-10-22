package dev.togar.dynasched.data

import dev.togar.dynasched.Places
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.calendar.AvailabilityWindow
import dev.togar.dynasched.calendar.BusyBlock
import dev.togar.dynasched.engine.HobbyRow
import dev.togar.dynasched.engine.Scheduler
import dev.togar.dynasched.engine.StudyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * やる日と、曜日ごとのいつもの場所。
 *
 * やる日がずれると「明日やる」と決めた物が今日の空きに入るか、決めた日に入らない。
 * どちらも決めた意味が無くなる。
 */
class WantTest {

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
    }

    private fun jst(d: Int, h: Int) = GregorianCalendar(TimeZone.getTimeZone("Asia/Tokyo")).apply {
        clear(); set(2026, 8, d, h, 0, 0)
    }.timeInMillis

    private fun win(day: String, s: String, e: String) =
        AvailabilityWindow("${day}T$s:00", "${day}T$e:00", "home", "自習", 1)

    private fun run(hobbies: List<HobbyRow>, windows: List<AvailabilityWindow>, busy: List<BusyBlock> = emptyList(), days: Int = 2) =
        Scheduler.run(emptyList(), hobbies, windows, busy, emptyList(), StudyEngine(), days, jst(1, 8),
            wakeStart = 6 * 60, wakeEnd = 23 * 60)

    @Test
    fun `決めた日より前には置かない`() {
        val pinned = HobbyRow(1, "明日の物", 30, 5, "anywhere", wantDate = "2026-09-02", wantLevel = Want.PREFER)
        val placed = run(listOf(pinned), listOf(win("2026-09-01", "09:00", "12:00"), win("2026-09-02", "09:00", "12:00")))
        assertEquals(listOf("2026-09-02"), placed.map { it.start.take(10) })
    }

    @Test
    fun `その日はほかのタスクより先`() {
        val normal = HobbyRow(1, "いつもの", 30, 8, "anywhere")
        val pinned = HobbyRow(2, "今日の物", 30, 3, "anywhere", wantDate = "2026-09-01", wantLevel = Want.PREFER)
        val placed = run(listOf(normal, pinned), listOf(win("2026-09-01", "09:00", "10:00")), days = 1)
        assertEquals("今日の物", placed.first().title)
    }

    @Test
    fun `絶対は枠に入らなくても空いた時間に入れる。予定とは重ねない`() {
        val must = HobbyRow(1, "提出", 60, 5, "p1", wantDate = "2026-09-01", wantLevel = Want.MUST)
        // 枠は家だけ（提出は寮のみ）。10〜12時はバイト
        val placed = run(listOf(must), listOf(win("2026-09-01", "18:00", "19:00")),
            listOf(BusyBlock("2026-09-01T08:00:00", "2026-09-01T12:00:00", "バイト", 2)), days = 1)
        assertEquals(1, placed.size)
        assertEquals("2026-09-01T12:00:00", placed[0].start)
    }

    @Test
    fun `なるべくは枠に入らなければ置かない（次の日へ回る）`() {
        val prefer = HobbyRow(1, "読書", 60, 5, "p1", wantDate = "2026-09-01", wantLevel = Want.PREFER)
        assertTrue(run(listOf(prefer), listOf(win("2026-09-01", "18:00", "19:00")), days = 1).isEmpty())
    }

    @Test
    fun `一覧の一言`() {
        val today = LocalDate.of(2026, 9, 1)
        assertEquals("📌 明日・絶対", Want.badge("2026-09-02", Want.MUST, today))
        assertEquals("📌 今日・なるべく", Want.badge("2026-09-01", Want.PREFER, today))
        assertEquals("📌 8/31(月)の残り・絶対", Want.badge("2026-08-31", Want.MUST, today))
        assertEquals("", Want.badge("", Want.MUST, today))
    }

    // ---- 曜日ごとのいつもの場所 ----

    private val ids = listOf(Places.HOME, Places.OUT, "jikka", "ryo")
    private val week = listOf("ryo", "ryo", "ryo", "ryo", "ryo", "jikka", "jikka")
    private val tue = LocalDate.of(2026, 9, 1)   // 火曜

    @Test
    fun `手で選んでいなければ曜日の場所`() {
        assertEquals("ryo", Prefs.currentPlace(ids, week, "jikka", "2026-08-30", tue))
    }

    @Test
    fun `今日手で選んだならそちら`() {
        assertEquals("jikka", Prefs.currentPlace(ids, week, "jikka", "2026-09-01", tue))
    }

    @Test
    fun `曜日を決めていなければ最後に選んだ場所。消した場所なら家`() {
        val none = List(7) { "" }
        assertEquals("jikka", Prefs.currentPlace(ids, none, "jikka", "2026-08-30", tue))
        assertEquals(Places.HOME, Prefs.currentPlace(ids, none, "gone", "2026-08-30", tue))
        assertEquals(Places.HOME, Prefs.currentPlace(ids, List(7) { "gone" }, null, null, tue))
    }
}
