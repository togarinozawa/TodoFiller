package dev.togar.dynasched.calendar

import dev.togar.dynasched.calendar.CalendarRepo.CalEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * カレンダー1件の分類。サーバー版(googleCalendar.syncAvailabilityFromPrimary)と
 * 同じ判定になっているかを固定する。
 *
 * **終日予定の時刻はUTCの深夜**で入るので、日本時間で読むと1日ずれる。
 * ここが狂うと枠が丸1日ずれて全部の配置が壊れるため、明示的に固定しておく。
 */
class CalendarRepoTest {

    @Before
    fun setUp() {
        // 端末のタイムゾーンに結果が依存するので、日本に固定して検証する
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
    }

    /** 端末ローカル時刻のエポックミリ秒 */
    private fun jst(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        GregorianCalendar(TimeZone.getTimeZone("Asia/Tokyo")).apply {
            clear(); set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis

    /** 終日予定が持つ「UTCの深夜」 */
    private fun utcMidnight(y: Int, mo: Int, d: Int): Long =
        GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(y, mo - 1, d, 0, 0, 0)
        }.timeInMillis

    @Test
    fun `末尾が家なら家の枠になり、タグは題名から外れる`() {
        val e = CalendarRepo.classify("自習室 家", jst(2026, 9, 1, 9, 0), jst(2026, 9, 1, 12, 0), false, 1)
        assertTrue(e is CalEntry.Window)
        val w = (e as CalEntry.Window).value
        assertEquals("home", w.location)
        assertEquals("自習室", w.title)
        assertEquals("2026-09-01T09:00:00", w.start)
        assertEquals("2026-09-01T12:00:00", w.end)
    }

    @Test
    fun `末尾が外なら外の枠になる`() {
        val e = CalendarRepo.classify("電車 外", jst(2026, 9, 1, 8, 0), jst(2026, 9, 1, 9, 0), false, 2)
        assertEquals("out", (e as CalEntry.Window).value.location)
        assertEquals("電車", e.value.title)
    }

    @Test
    fun `設定で足した場所の名前が末尾なら、その場所の枠になる`() {
        val places = dev.togar.dynasched.Places.DEFAULT + dev.togar.dynasched.Place("p1", "学校")
        val e = CalendarRepo.classify("放課後 学校", jst(2026, 9, 1, 16, 0), jst(2026, 9, 1, 18, 0), false, 20, places)
        val w = (e as CalEntry.Window).value
        assertEquals("p1", w.location)
        assertEquals("放課後", w.title)
        // 足していなければ普通の予定
        assertTrue(CalendarRepo.classify("放課後 学校", 0, 1, false, 21) is CalEntry.Busy)
    }

    @Test
    fun `旧記法の&h &o も使える`() {
        assertEquals("home", (CalendarRepo.classify("自習 &h", 0, 1, false, 3) as CalEntry.Window).value.location)
        assertEquals("out", (CalendarRepo.classify("移動 &o", 0, 1, false, 4) as CalEntry.Window).value.location)
    }

    @Test
    fun `タグ無しの時間指定予定は埋まっている扱い`() {
        val e = CalendarRepo.classify("バイト", jst(2026, 9, 1, 16, 0), jst(2026, 9, 1, 20, 0), false, 5)
        assertTrue(e is CalEntry.Busy)
        assertEquals("2026-09-01T16:00:00", (e as CalEntry.Busy).value.start)
    }

    @Test
    fun `タグ無しの終日予定は無視する（祝日で1日潰れないように）`() {
        val e = CalendarRepo.classify(
            "海の日", utcMidnight(2026, 9, 1), utcMidnight(2026, 9, 2), true, 6
        )
        assertEquals(CalEntry.Ignored, e)
    }

    @Test
    fun `タグ付きの終日予定はその日を丸ごと枠にする（UTCで日付を取る）`() {
        val e = CalendarRepo.classify(
            "終日 家", utcMidnight(2026, 9, 1), utcMidnight(2026, 9, 2), true, 7
        )
        val w = (e as CalEntry.Window).value
        // ローカル時刻として読むと 2026-08-31T09:00 になってしまう。1日ずれないこと
        assertEquals("2026-09-01T00:00:00", w.start)
        assertEquals("2026-09-02T00:00:00", w.end)
        assertEquals("home", w.location)
    }

    @Test
    fun `末尾がパーセントなら自動生成として読み飛ばす`() {
        val e = CalendarRepo.classify("物理ワーク 9問 %", jst(2026, 9, 1, 9, 0), jst(2026, 9, 1, 10, 0), false, 8)
        assertEquals(CalEntry.Generated, e)
    }

    @Test
    fun `テスト期間は終日の終わりを1日戻して両端を含む形にする`() {
        // 9/14〜9/16 の3日間（gcalの終日endは排他なので9/17が入る）
        val e = CalendarRepo.classify(
            "テスト期間", utcMidnight(2026, 9, 14), utcMidnight(2026, 9, 17), true, 9
        )
        val p = (e as CalEntry.Exam).value
        assertEquals("2026-09-14", p.startDate)
        assertEquals("2026-09-16", p.endDate)
    }

    @Test
    fun `実家のように末尾がたまたま家になる予定は枠として扱われる（既知の癖）`() {
        // マニュアルに注意書きがある通りの挙動。直すのではなく、変わっていないことを固定する
        val e = CalendarRepo.classify("実家", jst(2026, 9, 1, 9, 0), jst(2026, 9, 1, 12, 0), false, 10)
        assertTrue(e is CalEntry.Window)
        assertEquals("実", (e as CalEntry.Window).value.title)
    }

    @Test
    fun `日をまたぐ予定はそのまま持つ（日内への切り出しはスケジューラ側の仕事）`() {
        val e = CalendarRepo.classify("夜勤", jst(2026, 9, 1, 22, 0), jst(2026, 9, 2, 6, 0), false, 11)
        val b = (e as CalEntry.Busy).value
        assertEquals("2026-09-01T22:00:00", b.start)
        assertEquals("2026-09-02T06:00:00", b.end)
    }
}
