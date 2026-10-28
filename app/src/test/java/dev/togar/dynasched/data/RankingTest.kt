package dev.togar.dynasched.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「いまやること」の並べ方。いまやるタブ・ドパチルの警告画面・ウィジェットがこの順で受け取る。
 * ずれると「命令」の1件目が別の物になる。
 */
class RankingTest {

    private fun order(vararg xs: Pair<String, Ranking.Input>) =
        xs.sortedWith(compareBy(Ranking.ORDER) { it.second }).map { it.first }

    @Test
    fun `今日やると決めた物が先。絶対はなるべくより先`() {
        assertEquals(listOf("絶対", "なるべく", "優先度最上位"), order(
            "優先度最上位" to Ranking.Input(priority = 1.0, dueDays = 0),
            "なるべく" to Ranking.Input(wantToday = Want.PREFER, priority = 0.0),
            "絶対" to Ranking.Input(wantToday = Want.MUST, priority = 0.0)
        ))
    }

    @Test
    fun `先の日に決めた物は最後`() {
        assertEquals(listOf("いつもの", "明日の物"), order(
            "明日の物" to Ranking.Input(wantLater = true, priority = 1.0),
            "いつもの" to Ranking.Input(priority = 0.0)
        ))
    }

    @Test
    fun `優先度と締切の近さを同じ重みで足す`() {
        // 優先度が上ほど、締切が近いほど先。締切が14日以上先なら締切の点は0
        assertEquals(10.0, Ranking.score(Ranking.Input(priority = 1.0)), 1e-9)
        assertEquals(10.0, Ranking.score(Ranking.Input(priority = 0.0, dueDays = 0)), 1e-9)
        assertEquals(0.0, Ranking.score(Ranking.Input(priority = 0.0, dueDays = 30)), 1e-9)
        assertEquals(listOf("高・締切3日", "高・締切なし", "低・締切なし"), order(
            "低・締切なし" to Ranking.Input(priority = 0.0),
            "高・締切なし" to Ranking.Input(priority = 1.0),
            "高・締切3日" to Ranking.Input(priority = 1.0, dueDays = 3)
        ))
    }

    @Test
    fun `同点なら古い物から。日時の無い物は後`() {
        assertEquals(listOf("古い", "新しい", "日時なし"), order(
            "日時なし" to Ranking.Input(),
            "新しい" to Ranking.Input(createdAt = "2026-10-02 10:00:00"),
            "古い" to Ranking.Input(createdAt = "2026-09-01 10:00:00")
        ))
    }

    @Test
    fun `優先度を0から1に`() {
        assertEquals(1.0, Ranking.taskPriority(0, 3), 1e-9)
        assertEquals(0.5, Ranking.taskPriority(1, 3), 1e-9)
        assertEquals(0.0, Ranking.taskPriority(2, 3), 1e-9)
        assertEquals(1.0, Ranking.taskPriority(0, 1), 1e-9)
        assertEquals(0.0, Ranking.materialPriority(3), 1e-9)
        assertEquals(1.0, Ranking.materialPriority(8), 1e-9)
    }
}
