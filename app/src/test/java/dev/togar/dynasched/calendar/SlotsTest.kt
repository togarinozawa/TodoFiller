package dev.togar.dynasched.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** アプリの枠を日の区間へ広げる。毎週・その日だけ・休み（穴）の組み合わせを固定する */
class SlotsTest {

    private val mon = LocalDate.of(2026, 10, 5)   // 月曜

    private fun show(list: List<DaySlot>) =
        list.joinToString(" ") { "${it.date.dayOfMonth}:${it.startMin}-${it.endMin}(${it.location})" }

    @Test
    fun `毎週の枠はその曜日にだけ出る`() {
        val slots = listOf(Slot(id = 1, weekday = 1, startMin = 600, endMin = 720, location = "home"))
        assertEquals("5:600-720(home) 12:600-720(home)", show(Slots.expand(slots, mon, 8)))
    }

    @Test
    fun `その日だけの枠と、休みの穴`() {
        val slots = listOf(
            Slot(id = 1, weekday = 1, startMin = 540, endMin = 720, location = "home"),
            Slot(id = 2, date = "2026-10-05", startMin = 600, endMin = 660, isBlock = true),
            Slot(id = 3, date = "2026-10-06", startMin = 900, endMin = 960, location = "out")
        )
        assertEquals("5:540-600(home) 5:660-720(home) 6:900-960(out)", show(Slots.expand(slots, mon, 2)))
    }

    @Test
    fun `同じ場所で重なる枠はつながる`() {
        val slots = listOf(
            Slot(date = "2026-10-05", startMin = 540, endMin = 600, location = "home"),
            Slot(date = "2026-10-05", startMin = 600, endMin = 660, location = "home"),
            Slot(date = "2026-10-05", startMin = 630, endMin = 700, location = "out")
        )
        assertEquals("5:540-660(home) 5:630-700(out)", show(Slots.expand(slots, mon, 1)))
    }

    @Test
    fun `スケジューラへ渡す形。24時は翌日の0時にする`() {
        val w = Slots.toWindows(listOf(DaySlot(mon, 1380, 1440, "p1"), DaySlot(mon, 60, 90, "anywhere")))
        assertEquals("2026-10-05T23:00:00", w[0].start)
        assertEquals("2026-10-06T00:00:00", w[0].end)
        assertEquals("p1", w[0].location)
        assertEquals("home", w[1].location)   // どこでもの枠は家として扱う
    }

    @Test
    fun `刻みに丸める`() {
        assertEquals(615, Slots.snap(618))
        assertEquals(630, Slots.snap(623))
        assertEquals(1440, Slots.snap(1500))
    }
}
