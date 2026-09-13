package dev.togar.dynasched.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「並び順と表示」の組み立て。
 *
 * 前は平らな一覧で、押した位置から `i - sorts.size` と引き算して何を選ばれたか
 * 当てていた。**行を1つ足すと別の設定が変わる**壊れ方をするので、
 * 節の切れ目と、行と操作の対応をここで固定する。
 */
class ViewMenuTest {

    private fun rows(
        sort: TaskSort = TaskSort.MANUAL,
        done: DoneMode = DoneMode.INLINE,
        tab: TabSource = TabSource.NONE,
        tags: Int = 0,
        hide: Boolean = false
    ) = ViewMenu.rows(sort, done, tab, tags, hide, "7日")

    @Test
    fun 節の順番が決まっている() {
        val titles = rows().filterIsInstance<ViewMenuRow.Section>().map { it.title }
        assertEquals(
            listOf(ViewMenu.SORT, ViewMenu.DONE, ViewMenu.NARROW, ViewMenu.COUNT, ViewMenu.BULK),
            titles
        )
    }

    @Test
    fun 先頭は必ず節の見出し() {
        // 見出しの無い行から始まると、最初の塊が何の話か分からなくなる
        assertTrue(rows().first() is ViewMenuRow.Section)
    }

    @Test
    fun 並び順と完了はすべて選択肢で他は操作() {
        val all = rows()
        val sortSection = between(all, ViewMenu.SORT)
        val doneSection = between(all, ViewMenu.DONE)
        assertTrue(sortSection.all { it is ViewMenuRow.Choice })
        assertTrue(doneSection.all { it is ViewMenuRow.Choice })
        assertEquals(TaskSort.entries.size, sortSection.size)
        assertEquals(DoneMode.entries.size, doneSection.size)

        assertTrue(between(all, ViewMenu.NARROW).all { it is ViewMenuRow.Action })
        assertTrue(between(all, ViewMenu.BULK).all { it is ViewMenuRow.Action })
    }

    @Test
    fun 選ばれている印は節ごとに1つだけ() {
        val all = rows(sort = TaskSort.PRIORITY, done = DoneMode.HIDDEN)
        val sortSel = between(all, ViewMenu.SORT).filterIsInstance<ViewMenuRow.Choice>()
            .filter { it.selected }
        val doneSel = between(all, ViewMenu.DONE).filterIsInstance<ViewMenuRow.Choice>()
            .filter { it.selected }
        assertEquals(1, sortSel.size)
        assertEquals(1, doneSel.size)
        assertEquals(ViewMenuAction.Sort(TaskSort.PRIORITY), sortSel[0].action)
        assertEquals(ViewMenuAction.Done(DoneMode.HIDDEN), doneSel[0].action)
    }

    @Test
    fun 操作の行に丸印用の状態を持たせない() {
        // 選ぶ行と押す行を見た目で分けるのが目的なので、混ざっていないこと
        val actions = rows().filterIsInstance<ViewMenuRow.Action>()
        assertEquals(
            listOf(
                ViewMenuAction.TagFilter, ViewMenuAction.TabSource, ViewMenuAction.HideGrouped,
                ViewMenuAction.StatsSpan, ViewMenuAction.CollapseAll, ViewMenuAction.ExpandAll
            ),
            actions.map { it.action }
        )
    }

    @Test
    fun 塊を外す設定は今の状態を出す() {
        // 押すたびに入れ替わる行なので、いまどちらなのかが見えないと押せない
        val on = rows(hide = true).filterIsInstance<ViewMenuRow.Action>()
            .first { it.action == ViewMenuAction.HideGrouped }
        val off = rows(hide = false).filterIsInstance<ViewMenuRow.Action>()
            .first { it.action == ViewMenuAction.HideGrouped }
        assertEquals("外す", on.value)
        assertEquals("出す", off.value)
    }

    @Test
    fun 次の画面が開く行にだけ印を付ける() {
        val actions = rows().filterIsInstance<ViewMenuRow.Action>().associateBy { it.action }
        assertTrue(actions.getValue(ViewMenuAction.TagFilter).opensDialog)
        assertTrue(actions.getValue(ViewMenuAction.TabSource).opensDialog)
        assertFalse(actions.getValue(ViewMenuAction.CollapseAll).opensDialog)
        assertFalse(actions.getValue(ViewMenuAction.ExpandAll).opensDialog)
    }

    @Test
    fun 今の状態を右に出す() {
        val none = rows(tags = 0).filterIsInstance<ViewMenuRow.Action>().associateBy { it.action }
        assertEquals("なし", none.getValue(ViewMenuAction.TagFilter).value)

        val some = rows(tags = 3, tab = TabSource.GROUP)
            .filterIsInstance<ViewMenuRow.Action>().associateBy { it.action }
        assertEquals("3個", some.getValue(ViewMenuAction.TagFilter).value)
        assertEquals(TabSource.GROUP.label, some.getValue(ViewMenuAction.TabSource).value)

        // 畳む・開くは状態を持たないので何も出さない
        assertEquals(null, some.getValue(ViewMenuAction.CollapseAll).value)
    }

    @Test
    fun どの選択肢もラベルが空でない() {
        assertTrue(rows().all {
            when (it) {
                is ViewMenuRow.Section -> it.title.isNotBlank()
                is ViewMenuRow.Choice -> it.label.isNotBlank()
                is ViewMenuRow.Action -> it.label.isNotBlank()
            }
        })
    }

    /** ある節の見出しの次から、次の見出しの手前まで */
    private fun between(all: List<ViewMenuRow>, title: String): List<ViewMenuRow> {
        val start = all.indexOfFirst { it is ViewMenuRow.Section && it.title == title } + 1
        val rest = all.drop(start)
        return rest.takeWhile { it !is ViewMenuRow.Section }
    }
}
