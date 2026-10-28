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
        doneTabs: DoneTabs = DoneTabs.END,
        groupView: GroupView = GroupView.TABS,
        hasTabs: Boolean = true,
        filtering: Boolean = false,
        placeFiltered: Boolean = false
    ) = ViewMenu.rows(
        sort, done, doneTabs, groupView = groupView, hasTabs = hasTabs,
        filtering = filtering, placeFiltered = placeFiltered
    )

    @Test
    fun 節の順番が決まっている() {
        val titles = rows().filterIsInstance<ViewMenuRow.Section>().map { it.title }
        assertEquals(
            listOf(
                ViewMenu.SORT, ViewMenu.DONE, ViewMenu.GROUP_VIEW, ViewMenu.DONE_TABS,
                ViewMenu.SHOW, ViewMenu.PLACE, ViewMenu.BULK
            ),
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
        val all = rows(filtering = true)
        val sortSection = between(all, ViewMenu.SORT)
        val doneSection = between(all, ViewMenu.DONE)
        assertTrue(sortSection.all { it is ViewMenuRow.Choice })
        assertTrue(doneSection.all { it is ViewMenuRow.Choice })
        assertTrue(between(all, ViewMenu.DONE_TABS).all { it is ViewMenuRow.Choice })
        assertEquals(TaskSort.entries.size, sortSection.size)
        assertEquals(DoneMode.entries.size, doneSection.size)

        assertTrue(between(all, ViewMenu.SHOW).all { it is ViewMenuRow.Action })
        assertTrue(between(all, ViewMenu.PLACE).all { it is ViewMenuRow.Action })
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
        val actions = rows(filtering = true).filterIsInstance<ViewMenuRow.Action>()
        assertEquals(
            listOf(
                ViewMenuAction.GroupsLast, ViewMenuAction.TabOrder,
                ViewMenuAction.PlaceFiltered, ViewMenuAction.PlaceAll,
                ViewMenuAction.PickDay(0), ViewMenuAction.PickDay(1), ViewMenuAction.BulkPlace,
                ViewMenuAction.CollapseAll, ViewMenuAction.ExpandAll
            ),
            actions.map { it.action }
        )
    }

    @Test
    fun `絞っていない時は「絞り込みだけ配置」を出さない`() {
        // 押しても何も絞れないので、出すと「押したのに何も変わらない」になる
        val actions = rows(filtering = false).filterIsInstance<ViewMenuRow.Action>().map { it.action }
        assertFalse(ViewMenuAction.PlaceFiltered in actions)
        assertTrue(ViewMenuAction.PlaceAll in actions)
    }

    @Test
    fun 切り替える行は今の状態を出す() {
        // 押すたびに入れ替わる行なので、いまどちらなのかが見えないと押せない
        fun value(r: List<ViewMenuRow>, a: ViewMenuAction) =
            r.filterIsInstance<ViewMenuRow.Action>().first { it.action == a }.value
        assertEquals("絞っている", value(rows(placeFiltered = true), ViewMenuAction.PlaceAll))
        assertEquals("全部", value(rows(placeFiltered = false), ViewMenuAction.PlaceAll))
        // 畳む・開くは状態を持たないので何も出さない
        assertEquals(null, value(rows(), ViewMenuAction.CollapseAll))
    }

    @Test
    fun 次の画面が開く行にだけ印を付ける() {
        val actions = rows(filtering = true).filterIsInstance<ViewMenuRow.Action>().associateBy { it.action }
        assertTrue(actions.getValue(ViewMenuAction.PlaceFiltered).opensDialog)
        assertTrue(actions.getValue(ViewMenuAction.TabOrder).opensDialog)
        assertFalse(actions.getValue(ViewMenuAction.CollapseAll).opensDialog)
        assertFalse(actions.getValue(ViewMenuAction.GroupsLast).opensDialog)
    }

    @Test
    fun `インデントで見せる時は、済んだタブの節を出さない`() {
        // タブが無いので、タブの見せ方を選ばせても意味が無い
        val titles = rows(groupView = GroupView.INDENT).filterIsInstance<ViewMenuRow.Section>().map { it.title }
        assertFalse(titles.contains(ViewMenu.DONE_TABS))
        assertTrue(titles.contains(ViewMenu.GROUP_VIEW))
    }

    @Test
    fun `グループの見せ方は今の方が丸印になる`() {
        val picked = between(rows(groupView = GroupView.INDENT), ViewMenu.GROUP_VIEW)
            .filterIsInstance<ViewMenuRow.Choice>().filter { it.selected }
        assertEquals(listOf(ViewMenuAction.GroupStyle(GroupView.INDENT)), picked.map { it.action })
    }

    @Test
    fun `タブが無ければ並べ替えの行を出さない`() {
        val actions = rows(hasTabs = false).filterIsInstance<ViewMenuRow.Action>().map { it.action }
        assertFalse(ViewMenuAction.TabOrder in actions)
    }

    @Test
    fun どの選択肢もラベルが空でない() {
        assertTrue(rows(filtering = true).all {
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
