package dev.togar.dynasched.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RoutinesTest {

    private val wed = LocalDate.of(2026, 10, 7)   // 水曜

    @Test
    fun `毎週は選んだ曜日だけ`() {
        val r = Routine(kind = "weekly", weekdays = setOf(1, 3))
        assertTrue(Routines.matches(r, wed))
        assertFalse(Routines.matches(r, wed.plusDays(1)))
        assertEquals("毎週 月水", r.scheduleLabel())
    }

    @Test
    fun `毎月31日は短い月だと月末`() {
        val r = Routine(kind = "monthly", dayOfMonth = 31)
        assertTrue(Routines.matches(r, LocalDate.of(2026, 2, 28)))
        assertFalse(Routines.matches(r, LocalDate.of(2026, 3, 30)))
    }

    @Test
    fun `前のぶんが残っている間・今日もう作った・止めている時は作らない`() {
        val r = Routine(kind = "daily")
        assertTrue(Routines.isDue(r, wed, hasOpen = false))
        assertFalse(Routines.isDue(r, wed, hasOpen = true))
        assertFalse(Routines.isDue(r.copy(lastMade = wed.toString()), wed, false))
        assertFalse(Routines.isDue(r.copy(isActive = false), wed, false))
    }

    @Test
    fun `次に当たる日`() {
        val r = Routine(kind = "weekly", weekdays = setOf(5))
        assertEquals(LocalDate.of(2026, 10, 9), Routines.nextDate(r, wed))
        assertEquals(null, Routines.nextDate(r.copy(isActive = false), wed))
    }

    @Test
    fun `曜日の書き方`() {
        assertEquals("1,3,7", Routines.encodeWeekdays(setOf(7, 1, 3, 9)))
        assertEquals(setOf(2, 4), Routines.decodeWeekdays(" 2, 4 ,x,8"))
    }
}
