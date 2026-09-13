package dev.togar.dynasched.ui

import dev.togar.dynasched.api.HobbyItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * グループ／タグを横タブに並べる部分。
 *
 * タブは「いま見えている範囲」を決める。ここがずれると、
 * **あるはずのタスクが出てこない**か、逆に関係ないものが混ざる。
 * どちらも画面を触っただけでは原因が分からない類なので固定する。
 */
class TaskTabsTest {

    private fun task(id: Long, name: String, parent: Long? = null, tags: String = "", order: Int = 0) =
        HobbyItem(
            id = id, name = name, parentId = parent, isCompleted = false,
            tags = tags, sortOrder = order
        )

    private val tree = listOf(
        task(1, "買い物リスト", order = 1),
        task(2, "牛乳", parent = 1, tags = "食料"),
        task(3, "洗剤", parent = 1, tags = "日用品"),
        task(4, "大掃除", order = 2),
        task(5, "風呂", parent = 4),
        task(9, "単独タスク", order = 3, tags = "食料")
    )

    /** 入れ子の小グループがある木。TOPとGROUPの違いはここでしか出ない */
    private val nested = listOf(
        task(1, "仕事", order = 1),
        task(2, "資料作り", parent = 1, order = 1),
        task(3, "図を描く", parent = 2),
        task(4, "メール返信", parent = 1, order = 2),
        task(5, "家事", order = 2),
        task(6, "洗濯", parent = 5)
    )

    // ---- タブの並び ----

    @Test
    fun `すべてタブから塊を外すと残るのは属さないタスクだけ`() {
        // 塊は自分のタブで見られるので、すべてにも出すと二度見ることになる
        val shown = TaskTabs.apply(tree, TaskTabs.ALL, TabSource.TOP, hideGrouped = true)
        assertEquals(listOf("単独タスク"), shown.map { it.name })
    }

    @Test
    fun `外す設定が無ければすべてタブは全部出す`() {
        val shown = TaskTabs.apply(tree, TaskTabs.ALL, TabSource.TOP, hideGrouped = false)
        assertEquals(tree.size, shown.size)
    }

    @Test
    fun `タブを出さない設定なら外しようがないので全部出す`() {
        val shown = TaskTabs.apply(tree, TaskTabs.ALL, TabSource.NONE, hideGrouped = true)
        assertEquals(tree.size, shown.size)
    }

    @Test
    fun `塊の中の小グループが下段のタブになる`() {
        val subs = TaskTabs.subTabs(nested, "g:1")
        // 先頭は塊ぜんぶへ戻る口
        assertEquals(listOf("ぜんぶ", "資料作り"), subs.map { it.label })
        assertEquals("g:1", subs.first().key)
    }

    @Test
    fun `小グループが無ければ下段は出さない`() {
        // 「家事」の子は洗濯だけで、洗濯は子を持たない
        assertTrue(TaskTabs.subTabs(nested, "g:5").isEmpty())
        assertTrue(TaskTabs.subTabs(nested, TaskTabs.ALL).isEmpty())
        assertTrue(TaskTabs.subTabs(nested, "t:語学").isEmpty())
    }

    @Test
    fun `タブから追加先の塊を引ける`() {
        assertEquals(7L, TaskTabs.groupIdOf("g:7"))
        assertNull(TaskTabs.groupIdOf(TaskTabs.ALL))
        assertNull(TaskTabs.groupIdOf("t:語学"))
    }

    @Test
    fun `一番上だけなら入れ子の小グループはタブにしない`() {
        // 「資料作り」は子を持つが最上位ではないので出さない
        val tabs = TaskTabs.tabs(nested, TabSource.TOP)
        assertEquals(listOf("すべて", "仕事", "家事"), tabs.map { it.label })
    }

    @Test
    fun `入れ子ありなら小グループもタブになる`() {
        val tabs = TaskTabs.tabs(nested, TabSource.GROUP)
        assertEquals(listOf("すべて", "仕事", "資料作り", "家事"), tabs.map { it.label })
    }

    @Test
    fun `一番上だけでも中身は配下すべて`() {
        // タブを絞っても、その島の中の孫まで見えないと意味が無い
        val tabs = TaskTabs.tabs(nested, TabSource.TOP)
        val work = tabs.first { it.label == "仕事" }
        val shown = TaskTabs.apply(nested, work.key).map { it.name }
        assertTrue(shown.containsAll(listOf("資料作り", "図を描く", "メール返信")))
        assertTrue("別の島は混ざらない", shown.none { it == "洗濯" })
    }

    @Test
    fun `一番上だけでも子を持たない最上位はタブにしない`() {
        // 単独タスクごとにタブが出ると、1件だけのタブが並んで使い物にならない
        val tabs = TaskTabs.tabs(tree, TabSource.TOP)
        assertEquals(listOf("すべて", "買い物リスト", "大掃除"), tabs.map { it.label })
    }

    @Test
    fun `グループは子を持つタスクだけがタブになる`() {
        val tabs = TaskTabs.tabs(tree, TabSource.GROUP)
        assertEquals(listOf("すべて", "買い物リスト", "大掃除"), tabs.map { it.label })
        assertEquals(TaskTabs.ALL, tabs.first().key)
    }

    @Test
    fun `タグのタブは使われているタグから作る`() {
        val tabs = TaskTabs.tabs(tree, TabSource.TAG)
        assertEquals(listOf("すべて", "#食料", "#日用品"), tabs.map { it.label })
    }

    @Test
    fun `出さない指定ならタブは空`() {
        assertTrue(TaskTabs.tabs(tree, TabSource.NONE).isEmpty())
    }

    // ---- タブの中身 ----

    @Test
    fun `すべてタブは全部見せる`() {
        assertEquals(tree.size, TaskTabs.apply(tree, TaskTabs.ALL).size)
    }

    @Test
    fun `グループのタブは配下だけを見せ、親自身は出さない`() {
        // 見出しがグループ名なので、中でもう一度出すと同じ名前が2回並ぶ
        val shown = TaskTabs.apply(tree, "g:1").map { it.name }
        assertEquals(listOf("牛乳", "洗剤"), shown)
    }

    @Test
    fun `タグのタブは当てはまる枝を見せる`() {
        val shown = TaskTabs.apply(tree, "t:食料").map { it.name }
        // 「牛乳」と、親の「買い物リスト」（木を保つため）と「単独タスク」
        assertTrue(shown.contains("牛乳"))
        assertTrue("木が壊れて親が落ちた", shown.contains("買い物リスト"))
        assertTrue(shown.contains("単独タスク"))
        assertTrue("関係ない枝が混ざった", !shown.contains("風呂"))
    }

    @Test
    fun `壊れたキーでも全部返す（何も見えなくなるより良い）`() {
        assertEquals(tree.size, TaskTabs.apply(tree, "g:あいうえお").size)
    }

    @Test
    fun `消えたグループのタブは存在しないと分かる`() {
        val tabs = TaskTabs.tabs(tree, TabSource.GROUP)
        assertTrue(TaskTabs.exists(tabs, "g:1"))
        assertTrue("消したグループが残っている", !TaskTabs.exists(tabs, "g:999"))
        assertTrue("すべては常にある", TaskTabs.exists(tabs, TaskTabs.ALL))
    }

    @Test
    fun `配下は孫まで降りる`() {
        val deep = listOf(
            task(1, "親"), task(2, "子", parent = 1), task(3, "孫", parent = 2)
        )
        assertEquals(listOf("子", "孫"), TaskList.descendantsOf(deep, 1).map { it.name })
    }
}
