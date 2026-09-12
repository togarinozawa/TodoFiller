package dev.togar.dynasched.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Notionとの突き合わせ。
 *
 * ここで守りたいのは「静かに壊れる」類ばかりで、どれも実機では再現しにくい。
 * **消したタスクが復活する・圏外の編集が消える・親子が環になって一覧が止まる。**
 * 通信もSQLも要らない形にしてあるので、全部ここで潰す。
 */
class NotionSyncTest {

    private fun rem(
        id: String, name: String = "やること", parent: String? = null,
        done: Boolean = false, archived: Boolean = false, minutes: Int = 30
    ) = NotionTask(
        pageId = id, parentPageId = parent, name = name,
        completed = done, archived = archived, durationMinutes = minutes
    )

    private fun loc(
        id: Long, page: String = "", name: String = "やること",
        done: Boolean = false, dirty: Boolean = false, minutes: Int = 30
    ) = LocalTask(
        id = id, notionPageId = page, name = name,
        completed = done, dirty = dirty, durationMinutes = minutes
    )

    // ---- 取り込み ----

    @Test
    fun Notionにしか無いものは端末に作る() {
        val p = NotionSync.plan(local = emptyList(), remote = listOf(rem("p1")), full = true)
        assertEquals(listOf("p1"), p.insertLocal.map { it.pageId })
        assertTrue(p.updateLocal.isEmpty())
        assertTrue(p.deleteLocal.isEmpty())
    }

    @Test
    fun 中身が変わっていれば端末を上書きする() {
        val p = NotionSync.plan(
            local = listOf(loc(1, "p1", name = "古い名前")),
            remote = listOf(rem("p1", name = "新しい名前")),
            full = true
        )
        assertEquals(listOf(1L), p.updateLocal.map { it.localId })
        assertEquals("新しい名前", p.updateLocal[0].from.name)
    }

    @Test
    fun 同じ中身なら何もしない() {
        val p = NotionSync.plan(
            local = listOf(loc(1, "p1")), remote = listOf(rem("p1")), full = true
        )
        assertTrue("差が無いのに書き込むと最終更新が動いて差分取得が壊れる", p.isEmpty())
    }

    // ---- 書き戻し ----

    @Test
    fun 完了はスキマスが正でNotionへ返す() {
        val p = NotionSync.plan(
            local = listOf(loc(1, "p1", done = true)),
            remote = listOf(rem("p1", done = false)),
            full = true
        )
        assertEquals(listOf(1L), p.updateRemote)
        assertTrue("完了でNotionの値を取り込んではいけない", p.updateLocal.isEmpty())
    }

    @Test
    fun 端末で作られたものはNotionへ送る() {
        val p = NotionSync.plan(
            local = listOf(loc(1), loc(2, "p2")),
            remote = listOf(rem("p2")), full = true
        )
        assertEquals(listOf(1L), p.insertRemote)
    }

    @Test
    fun 圏外で直した行はその回だけ端末が勝つ() {
        val p = NotionSync.plan(
            local = listOf(loc(1, "p1", name = "端末で直した", dirty = true)),
            remote = listOf(rem("p1", name = "Notionの名前")),
            full = true
        )
        assertEquals(listOf(1L), p.updateRemote)
        assertTrue("押し返す前に上書きすると編集が消える", p.updateLocal.isEmpty())
    }

    // ---- 削除 ----

    @Test
    fun Notionでアーカイブされたら端末からも消す() {
        val p = NotionSync.plan(
            local = listOf(loc(1, "p1")),
            remote = listOf(rem("p1", archived = true)), full = true
        )
        assertEquals(listOf(1L), p.deleteLocal)
        assertTrue(p.insertLocal.isEmpty())
    }

    @Test
    fun 一覧に出てこない行はまだ消さず確認に回す() {
        // **出てこない＝消された、ではない。**Notionは作ったばかりのページを
        // すぐ返さないことがあり、それで端末のタスクを全部飛ばした（v36）
        val p = NotionSync.plan(
            local = listOf(loc(1, "p1"), loc(2, "p2")),
            remote = listOf(rem("p2")), full = true
        )
        assertTrue("確かめる前に消してはいけない", p.deleteLocal.isEmpty())
        assertEquals(listOf(MissingPage(1L, "p1")), p.verifyMissing)
    }

    @Test
    fun 差分取得では行方不明扱いすらしない() {
        // 絞って取った結果に出てこないのは当たり前なので、確認にも回さない
        val p = NotionSync.plan(
            local = listOf(loc(1, "p1"), loc(2, "p2")),
            remote = listOf(rem("p2")), full = false
        )
        assertTrue(p.deleteLocal.isEmpty())
        assertTrue(p.verifyMissing.isEmpty())
    }

    @Test
    fun アーカイブ済みと分かっている行は確認に回さない() {
        // Notionが名指しで「消えた」と言っている分は、もう訊き直す必要がない
        val p = NotionSync.plan(
            local = listOf(loc(1, "p1")),
            remote = listOf(rem("p1", archived = true)), full = true
        )
        assertEquals(listOf(1L), p.deleteLocal)
        assertTrue(p.verifyMissing.isEmpty())
    }

    @Test
    fun ページIDが無い行は行方不明にならない() {
        // まだ送っていないだけ。ここを混ぜると、初回同期で全部消える
        val p = NotionSync.plan(
            local = listOf(loc(1), loc(2)), remote = emptyList(), full = true
        )
        assertTrue(p.verifyMissing.isEmpty())
        assertTrue(p.deleteLocal.isEmpty())
        assertEquals(listOf(1L, 2L), p.insertRemote)
    }

    @Test
    fun 端末で消したページはNotionでも畳む() {
        val p = NotionSync.plan(
            local = emptyList(), remote = listOf(rem("p1")),
            full = true, deletedPageIds = listOf("p1")
        )
        assertEquals(listOf("p1"), p.archiveRemote)
        assertTrue("消したものを取り込み直してはいけない", p.insertLocal.isEmpty())
    }

    @Test
    fun 墓標は差分に出てこなくても畳む指示を出す() {
        val p = NotionSync.plan(
            local = emptyList(), remote = emptyList(),
            full = false, deletedPageIds = listOf("p1")
        )
        assertEquals(listOf("p1"), p.archiveRemote)
    }

    @Test
    fun 消す行に対しては上書きも書き戻しもしない() {
        val p = NotionSync.plan(
            local = listOf(loc(1, "p1", done = true)),
            remote = listOf(rem("p1", archived = true)), full = true
        )
        assertEquals(listOf(1L), p.deleteLocal)
        assertTrue(p.updateRemote.isEmpty())
        assertTrue(p.updateLocal.isEmpty())
    }

    // ---- 親子 ----

    @Test
    fun 親はページIDのまま返す() {
        val p = NotionSync.plan(
            local = emptyList(),
            remote = listOf(rem("oya"), rem("ko", parent = "oya")), full = true
        )
        assertNull(p.parentOf["oya"])
        assertEquals("oya", p.parentOf["ko"])
    }

    @Test
    fun 環になっていたら断ち切る() {
        val p = NotionSync.plan(
            local = emptyList(),
            remote = listOf(rem("a", parent = "b"), rem("b", parent = "a")), full = true
        )
        assertEquals(1, p.brokenCycles.size)
        // どちらか一方が最上位に上がり、木として辿れる形になる
        assertTrue(noLoops(p.parentOf))
    }

    @Test
    fun 自分を親にしていたら外す() {
        val p = NotionSync.plan(
            local = emptyList(), remote = listOf(rem("a", parent = "a")), full = true
        )
        assertEquals(listOf("a"), p.brokenCycles)
        assertNull(p.parentOf["a"])
    }

    @Test
    fun 長い環も断ち切る() {
        val p = NotionSync.plan(
            local = emptyList(),
            remote = listOf(
                rem("a", parent = "c"), rem("b", parent = "a"), rem("c", parent = "b")
            ),
            full = true
        )
        assertEquals(1, p.brokenCycles.size)
        assertTrue(noLoops(p.parentOf))
    }

    @Test
    fun まともな木は触らない() {
        val (out, broken) = NotionSync.breakCycles(
            mapOf("a" to null, "b" to "a", "c" to "b", "d" to "a")
        )
        assertTrue(broken.isEmpty())
        assertEquals("b", out["c"])
        assertEquals("a", out["d"])
    }

    @Test
    fun 取得漏れの親は最上位扱いにせず値を残す() {
        // 差分取得だと親のページが今回の分に入っていないことがある。
        // 適用側が既存の対応から引けるよう、IDはそのまま残す
        val p = NotionSync.plan(
            local = emptyList(), remote = listOf(rem("ko", parent = "soto")), full = false
        )
        assertEquals("soto", p.parentOf["ko"])
    }

    // ---- 数え方 ----

    @Test
    fun 取り込みと送信の数を分けて数える() {
        val p = NotionSync.plan(
            local = listOf(loc(1), loc(2, "p2", name = "古い")),
            remote = listOf(rem("p2", name = "新しい"), rem("p3")),
            full = false, deletedPageIds = listOf("p9")
        )
        assertEquals(2, p.pulled())   // p3を作る + p2を上書き
        assertEquals(2, p.pushed())   // 端末の1件を送る + p9を畳む
    }

    /** 親を辿って最上位に着くか。着かなければ環が残っている */
    private fun noLoops(parentOf: Map<String, String?>): Boolean {
        for (start in parentOf.keys) {
            val seen = HashSet<String>()
            var cur: String? = start
            while (cur != null) {
                if (!seen.add(cur)) return false
                cur = parentOf[cur]
            }
        }
        return true
    }
}
