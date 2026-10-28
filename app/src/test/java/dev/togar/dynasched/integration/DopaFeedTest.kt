package dev.togar.dynasched.integration

import dev.togar.dynasched.api.SuggestItem
import dev.togar.dynasched.data.NowTasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

/**
 * ドパチルへ渡す時刻。ずれると、向こうの集中が1日早く始まる・9時間ずれる、のような壊れ方をする。
 * どれも UTC のエポック秒。日付だけの物はその日の終わり。
 */
class DopaFeedTest {

    private val jst = ZoneId.of("Asia/Tokyo")

    @Test
    fun `端末の日時はUTC秒に。空白区切りも読む`() {
        // 2026-10-28 09:00 JST = 2026-10-28 00:00 UTC
        assertEquals(1793145600L, DopaFeed.epoch("2026-10-28T09:00:00", jst))
        assertEquals(1793145600L, DopaFeed.epoch("2026-10-28 09:00:00", jst))
        assertEquals(0L, DopaFeed.epoch("", jst))
        assertEquals(0L, DopaFeed.epoch("壊れ", jst))
    }

    @Test
    fun `日付だけの物はその日の終わり`() {
        assertEquals(DopaFeed.epoch("2026-10-28T23:59:59", jst), DopaFeed.endOfDay("2026-10-28", jst))
        assertEquals(0L, DopaFeed.endOfDay("", jst))
    }

    @Test
    fun `教材の締切は分まで書いた形と日付だけの形の両方`() {
        assertEquals(DopaFeed.epoch("2026-11-02T18:30:00", jst), DopaFeed.epochOfDeadline("2026-11-02T18:30", jst))
        assertEquals(DopaFeed.endOfDay("2026-11-02", jst), DopaFeed.epochOfDeadline("2026-11-02", jst))
        assertNull(DopaFeed.epochOfDeadline("", jst))
    }

    @Test
    fun `タスクの締切も理由に出す`() {
        assertEquals("優先度 高・締切まで2日", NowTasks.reason(SuggestItem("hobby", 1, "x", 30, 8, 0, dueDays = 2)))
    }
}
