package dev.togar.dynasched.engine

import dev.togar.dynasched.calendar.AvailabilityWindow
import dev.togar.dynasched.calendar.BusyBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * 配置ロジック。サーバー版で見つけて直した不具合が、移植後も直ったままかを固定する。
 *
 * ここが狂うと「予定は出るのに量が足りない」「同じ時刻に2件入る」という、
 * **気付きにくい壊れ方**をする。前回のテストが失敗した原因もそこにあった。
 */
class SchedulerTest {

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
    }

    private fun w(s: String, e: String, loc: String = "home") =
        AvailabilityWindow("2026-09-01T$s", "2026-09-01T$e", loc, "自習", 1)

    private fun b(s: String, e: String) =
        BusyBlock("2026-09-01T$s", "2026-09-01T$e", "バイト", 2)

    private fun show(list: List<FreeSlot>) =
        list.joinToString(" ") { "${it.start}-${it.end}(${it.location})" }

    // ---- 空き時間の算出 ----

    @Test
    fun `枠がそのまま空きになる`() {
        assertEquals("540-720(home)", show(Scheduler.computeFree("2026-09-01", emptyList(), listOf(w("09:00:00", "12:00:00")))))
    }

    @Test
    fun `足した場所の枠は場所のidのまま残る`() {
        // 以前は家と外以外を全部「家」に丸めていた。丸めると学校の枠に家用のタスクが入る
        val free = Scheduler.computeFree(
            "2026-09-01", emptyList(),
            listOf(w("09:00:00", "12:00:00", "p1"), w("13:00:00", "14:00:00", "out"))
        )
        assertEquals("540-720(p1) 780-840(out)", show(free))
    }

    @Test
    fun `終日と具体の枠が重なっても二重にしない`() {
        // 「終日 家」と「自習室 家」を両方書くと重なる。マニュアルが両方認めているので普通に起きる。
        // 潰さないと同じ時刻に2件配置される。
        val free = Scheduler.computeFree(
            "2026-09-01", emptyList(),
            listOf(w("06:00:00", "23:00:00"), w("09:00:00", "12:00:00"))
        )
        assertEquals("360-1380(home)", show(free))
    }

    @Test
    fun `部分的に重なる枠は続きだけを持つ`() {
        val free = Scheduler.computeFree(
            "2026-09-01", emptyList(),
            listOf(w("09:00:00", "12:00:00"), w("11:00:00", "14:00:00"))
        )
        assertEquals("540-720(home) 720-840(home)", show(free))
    }

    @Test
    fun `予定ありの部分は枠から抜ける`() {
        val free = Scheduler.computeFree(
            "2026-09-01", listOf(b("10:00:00", "11:00:00")), listOf(w("09:00:00", "12:00:00"))
        )
        assertEquals("540-600(home) 660-720(home)", show(free))
    }

    @Test
    fun `15分未満の細切れは捨てる`() {
        assertEquals("", show(Scheduler.computeFree("2026-09-01", emptyList(), listOf(w("09:00:00", "09:10:00")))))
    }

    @Test
    fun `起床時間の外は切り落とす`() {
        // 6:00〜23:00 の外は使わない
        val free = Scheduler.computeFree("2026-09-01", emptyList(), listOf(w("03:00:00", "08:00:00")))
        assertEquals("360-480(home)", show(free))
    }

    @Test
    fun `就寝時刻を早めるとそこで打ち切られる`() {
        // 設定した就寝時刻より後には置かない（アプリ全体で共有する設定）
        val free = Scheduler.computeFree(
            "2026-09-01", emptyList(), listOf(w("18:00:00", "23:00:00")),
            wakeStart = 5 * 60, wakeEnd = 21 * 60
        )
        assertEquals("1080-1260(home)", show(free))
    }

    @Test
    fun `早起きにすると朝の枠が使えるようになる`() {
        val free = Scheduler.computeFree(
            "2026-09-01", emptyList(), listOf(w("04:00:00", "08:00:00")),
            wakeStart = 4 * 60, wakeEnd = 22 * 60
        )
        assertEquals("240-480(home)", show(free))
    }

    @Test
    fun `就寝時刻より後には配置しない`() {
        val base = jst(2026, 9, 1)
        val d = ymd(base, 0)
        val m = MaterialRow(
            id = 1, name = "ワーク", totalProblems = 300, targetRounds = 1,
            deadline = "${ymd(base, 10)}T23:59:00", sessionMinutes = 50
        )
        val placed = Scheduler.run(
            listOf(m), emptyList(),
            listOf(AvailabilityWindow("${d}T09:00:00", "${d}T23:00:00", "home", "自習", 1)),
            emptyList(), emptyList(), StudyEngine(), 1, jst(2026, 9, 1, 8, 0),
            wakeStart = 6 * 60, wakeEnd = 21 * 60
        )
        assertTrue("枠が出なかった", placed.isNotEmpty())
        val last = placed.maxOf { it.end }
        assertTrue("就寝(21:00)より後に置かれた: $last", last <= "${d}T21:00:00")
    }

    // ---- 配置 ----

    private fun jst(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        GregorianCalendar(TimeZone.getTimeZone("Asia/Tokyo")).apply {
            clear(); set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis

    private fun ymd(base: Long, offsetDays: Int): String {
        val c = Calendar.getInstance().apply { timeInMillis = base + offsetDays * 86400000L }
        return String.format(
            Locale.US, "%04d-%02d-%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** 土日だけ8時間の枠がある、という偏った状況を作る */
    private fun weekendWindows(base: Long, days: Int): List<AvailabilityWindow> {
        val out = ArrayList<AvailabilityWindow>()
        for (i in 0 until days) {
            val c = Calendar.getInstance().apply { timeInMillis = base + i * 86400000L }
            val dow = c.get(Calendar.DAY_OF_WEEK)
            if (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY) continue
            val d = ymd(base, i)
            out.add(AvailabilityWindow("${d}T09:00:00", "${d}T17:00:00", "home", "自習", i.toLong()))
        }
        return out
    }

    @Test
    fun `枠が土日に偏っていても必要な量を置ける`() {
        // 全日数で割ると1日あたりが過小になり、必要量の半分ほどしか置かれない。
        // 実際に枠のある日で割ること。
        val base = jst(2026, 9, 1)   // 火曜
        val m = MaterialRow(
            id = 1, name = "ワーク", totalProblems = 200, targetRounds = 2,
            deadline = "${ymd(base, 27)}T23:59:00", sessionMinutes = 50, priority = 7
        )
        val placed = Scheduler.run(
            listOf(m), emptyList(), weekendWindows(base, 28), emptyList(), emptyList(),
            StudyEngine(), 28, base
        )
        val minutes = placed.size * 50
        // 200問×2周を仮ペース(1周目3.0分, 2周目1.8分)で = 960分。9割は置けていること
        assertTrue("置かれたのは${minutes}分（必要960分）", minutes >= 860)
        assertEquals("study", placed.first().eventType)
    }

    @Test
    fun `同じ時刻に二重配置しない`() {
        val base = jst(2026, 9, 1)
        val m = MaterialRow(
            id = 1, name = "ワーク", totalProblems = 200, targetRounds = 2,
            deadline = "${ymd(base, 27)}T23:59:00", sessionMinutes = 50
        )
        // 終日と具体の枠を重ねて置く
        val windows = ArrayList<AvailabilityWindow>()
        for (i in 0 until 7) {
            val d = ymd(base, i)
            windows.add(AvailabilityWindow("${d}T06:00:00", "${d}T23:00:00", "home", "終日", 1))
            windows.add(AvailabilityWindow("${d}T09:00:00", "${d}T12:00:00", "home", "自習室", 2))
        }
        val placed = Scheduler.run(
            listOf(m), emptyList(), windows, emptyList(), emptyList(), StudyEngine(), 7, base
        ).sortedBy { it.start }
        for (i in 1 until placed.size) {
            assertTrue(
                "重なり: ${placed[i - 1].end} と ${placed[i].start}",
                placed[i].start >= placed[i - 1].end
            )
        }
        assertTrue(placed.isNotEmpty())
    }

    @Test
    fun `単発タスクは教材より先に置かれる`() {
        val base = jst(2026, 9, 1)
        val m = MaterialRow(
            id = 1, name = "ワーク", totalProblems = 100, targetRounds = 1,
            deadline = "${ymd(base, 10)}T23:59:00", sessionMinutes = 50
        )
        val h = HobbyRow(9, "買い物", 30, 8, "anywhere")
        val d = ymd(base, 0)
        val placed = Scheduler.run(
            listOf(m), listOf(h),
            listOf(AvailabilityWindow("${d}T09:00:00", "${d}T17:00:00", "home", "自習", 1)),
            emptyList(), emptyList(), StudyEngine(), 1, jst(2026, 9, 1, 8, 0)
        )
        assertEquals("買い物", placed.first().title)
        assertEquals("hobby", placed.first().eventType)
    }

    @Test
    fun `外の枠には机が要る教材を置かない`() {
        val base = jst(2026, 9, 1)
        val m = MaterialRow(
            id = 1, name = "ワーク", totalProblems = 100, targetRounds = 1,
            deadline = "${ymd(base, 10)}T23:59:00", sessionMinutes = 50, needs = "desk"
        )
        val d = ymd(base, 0)
        val placed = Scheduler.run(
            listOf(m), emptyList(),
            listOf(AvailabilityWindow("${d}T09:00:00", "${d}T17:00:00", "out", "電車", 1)),
            emptyList(), emptyList(), StudyEngine(), 1, jst(2026, 9, 1, 8, 0)
        )
        assertTrue("外の枠に机が要る教材が置かれた", placed.isEmpty())
    }

    @Test
    fun `前提の教材が1周終わるまで出さない`() {
        val base = jst(2026, 9, 1)
        val work = MaterialRow(
            id = 1, name = "ワーク", totalProblems = 100, targetRounds = 1,
            deadline = "${ymd(base, 20)}T23:59:00", sessionMinutes = 50
        )
        val past = MaterialRow(
            id = 2, name = "過去問", totalProblems = 30, targetRounds = 1,
            deadline = "${ymd(base, 20)}T23:59:00", sessionMinutes = 50, prereqMaterialId = 1
        )
        val d = ymd(base, 0)
        val placed = Scheduler.run(
            listOf(work, past), emptyList(),
            listOf(AvailabilityWindow("${d}T09:00:00", "${d}T17:00:00", "home", "自習", 1)),
            emptyList(), emptyList(), StudyEngine(), 1, jst(2026, 9, 1, 8, 0)
        )
        assertTrue("過去問が前提未達で置かれた", placed.none { it.materialId == 2L })
        assertTrue(placed.any { it.materialId == 1L })
    }

    @Test
    fun `テスト期間中は定期テストの教材だけ置く`() {
        val base = jst(2026, 9, 1)
        val d = ymd(base, 0)
        val exam = MaterialRow(
            id = 1, name = "物理", totalProblems = 100, targetRounds = 1,
            deadline = "${ymd(base, 10)}T23:59:00", sessionMinutes = 50, isExam = true
        )
        val other = MaterialRow(
            id = 2, name = "趣味の本", totalProblems = 100, targetRounds = 1,
            deadline = "${ymd(base, 10)}T23:59:00", sessionMinutes = 50, isExam = false
        )
        val placed = Scheduler.run(
            listOf(exam, other), listOf(HobbyRow(9, "買い物", 30, 8, "anywhere")),
            listOf(AvailabilityWindow("${d}T09:00:00", "${d}T17:00:00", "home", "自習", 1)),
            emptyList(),
            listOf(dev.togar.dynasched.calendar.ExamPeriod(d, d, "テスト期間", 3)),
            StudyEngine(), 1, jst(2026, 9, 1, 8, 0)
        )
        assertTrue("テスト期間に対象外の教材が置かれた", placed.none { it.materialId == 2L })
        assertTrue("テスト期間に単発タスクが置かれた", placed.none { it.eventType == "hobby" })
        assertTrue(placed.all { it.materialId == 1L })
    }

    @Test
    fun `予定名に周と問数が入る`() {
        val base = jst(2026, 9, 1)
        val d = ymd(base, 0)
        val m = MaterialRow(
            id = 1, name = "物理ワーク", totalProblems = 100, targetRounds = 2,
            deadline = "${ymd(base, 20)}T23:59:00", sessionMinutes = 50
        )
        val placed = Scheduler.run(
            listOf(m), emptyList(),
            listOf(AvailabilityWindow("${d}T09:00:00", "${d}T17:00:00", "home", "自習", 1)),
            emptyList(), emptyList(), StudyEngine(), 1, jst(2026, 9, 1, 8, 0)
        )
        assertTrue(placed.first().title, placed.first().title.matches(Regex("物理ワーク 1周目 \\d+問")))
    }
}
