package dev.togar.dynasched.ui

import dev.togar.dynasched.api.HobbyItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 単発タスク一覧の並べ方。
 *
 * 並び順・折りたたみ・完了のまとめ方が絡むと、目で見て確かめるのが一番あてにならない。
 * **木の形が壊れていないこと**を含めてここで固定する。
 */
class TaskListTest {

    private fun task(
        id: Long, name: String, parent: Long? = null, done: Boolean = false,
        minutes: Int = 30, priority: Int = 5, order: Int = 0
    ) = HobbyItem(
        id = id, name = name, parentId = parent, isCompleted = done,
        durationMinutes = minutes, priority = priority, sortOrder = order
    )

    private fun names(rows: List<TaskRow>) = rows.joinToString(",") {
        "  ".repeat(it.level) + it.item.name
    }

    // ---- 並び順 ----

    private val flat = listOf(
        task(1, "古い", minutes = 90, priority = 3, order = 3),
        task(2, "中間", minutes = 15, priority = 8, order = 1),
        task(3, "新しい", minutes = 45, priority = 5, order = 2)
    )

    @Test
    fun `手動の並びは保存した順`() {
        val r = TaskList.build(flat, TaskSort.MANUAL, emptySet(), DoneMode.INLINE)
        assertEquals("中間,新しい,古い", names(r))
    }

    @Test
    fun `追加が新しい順と古い順`() {
        assertEquals("新しい,中間,古い", names(TaskList.build(flat, TaskSort.NEWEST, emptySet(), DoneMode.INLINE)))
        assertEquals("古い,中間,新しい", names(TaskList.build(flat, TaskSort.OLDEST, emptySet(), DoneMode.INLINE)))
    }

    @Test
    fun `優先度と必要時間で並べられる`() {
        assertEquals("中間,新しい,古い", names(TaskList.build(flat, TaskSort.PRIORITY, emptySet(), DoneMode.INLINE)))
        assertEquals("古い,新しい,中間", names(TaskList.build(flat, TaskSort.LONGEST, emptySet(), DoneMode.INLINE)))
        assertEquals("中間,新しい,古い", names(TaskList.build(flat, TaskSort.SHORTEST, emptySet(), DoneMode.INLINE)))
    }

    // ---- 完了の扱い ----

    @Test
    fun `完了を下にまとめる指定はどの並び順より先に効く`() {
        val list = listOf(
            task(1, "済A", done = true, priority = 8),
            task(2, "未B", priority = 3),
            task(3, "未C", priority = 5)
        )
        assertEquals("未C,未B,済A", names(TaskList.build(list, TaskSort.PRIORITY, emptySet(), DoneMode.BOTTOM)))
    }

    @Test
    fun `まとめない指定なら完了もその場に残る`() {
        val list = listOf(
            task(1, "済A", done = true, priority = 8),
            task(2, "未B", priority = 3)
        )
        assertEquals("済A,未B", names(TaskList.build(list, TaskSort.PRIORITY, emptySet(), DoneMode.INLINE)))
    }

    @Test
    fun `隠す指定なら完了したものは行に出ない`() {
        val list = listOf(
            task(1, "済A", done = true),
            task(2, "未B"),
            task(3, "済C", done = true)
        )
        assertEquals("未B", names(TaskList.build(list, TaskSort.MANUAL, emptySet(), DoneMode.HIDDEN)))
    }

    @Test
    fun `配下が全部済んだ親も隠す`() {
        // 残すと、中身が空の見出しだけが並んで「終わったのに片付かない」状態になる
        val list = listOf(
            task(1, "済んだ班"),
            task(2, "子1", parent = 1, done = true),
            task(3, "子2", parent = 1, done = true),
            task(4, "残った班"),
            task(5, "子3", parent = 4, done = true),
            task(6, "子4", parent = 4)
        )
        assertEquals(
            "残った班,  子4",
            names(TaskList.build(list, TaskSort.MANUAL, emptySet(), DoneMode.HIDDEN))
        )
    }

    @Test
    fun `全部済んでいれば何も出ない`() {
        val list = listOf(task(1, "済A", done = true))
        assertTrue(TaskList.build(list, TaskSort.MANUAL, emptySet(), DoneMode.HIDDEN).isEmpty())
    }

    @Test
    fun `隠していても未完了は木の形を保つ`() {
        val list = listOf(
            task(1, "親"),
            task(2, "子", parent = 1, done = true),
            task(3, "孫のいる子", parent = 1),
            task(4, "孫", parent = 3)
        )
        assertEquals(
            "親,  孫のいる子,    孫",
            names(TaskList.build(list, TaskSort.MANUAL, emptySet(), DoneMode.HIDDEN))
        )
    }

    // ---- 階層と折りたたみ ----

    private val tree = listOf(
        task(1, "親", order = 1),
        task(2, "子1", parent = 1, minutes = 30, order = 1),
        task(3, "子2", parent = 1, minutes = 60, done = true, order = 2),
        task(4, "孫", parent = 2, minutes = 20),
        task(9, "単独", order = 2)
    )

    @Test
    fun `階層は深さ優先で段付きになる`() {
        assertEquals("親,  子1,    孫,  子2,単独", names(TaskList.build(tree, TaskSort.MANUAL, emptySet(), DoneMode.INLINE)))
    }

    @Test
    fun `畳んだ親は1行になって配下が消える`() {
        val r = TaskList.build(tree, TaskSort.MANUAL, setOf(1L), DoneMode.INLINE)
        assertEquals("親,単独", names(r))
        assertTrue(r.first().collapsed)
    }

    @Test
    fun `畳んだ親の要約は配下の葉を数える`() {
        val r = TaskList.build(tree, TaskSort.MANUAL, setOf(1L), DoneMode.INLINE)
        // 葉は「孫(20分)」と「子2(60分・済)」の2件。子1は親なので数えない
        assertEquals("2件（済1）・1時間20分", r.first().summary())
    }

    @Test
    fun `親が消えた子はルートとして出す`() {
        val orphan = listOf(task(5, "孤児", parent = 99))
        assertEquals("孤児", names(TaskList.build(orphan, TaskSort.MANUAL, emptySet(), DoneMode.INLINE)))
    }

    @Test
    fun `親子が輪になっても一覧は返る`() {
        // 壊れたデータで無限ループに入ると、アプリが二度と開かなくなる
        val loop = listOf(task(1, "A", parent = 2), task(2, "B", parent = 1))
        val r = TaskList.build(loop, TaskSort.MANUAL, emptySet(), DoneMode.INLINE)
        assertTrue("行が出ない", r.size <= 2)
    }

    // ---- 並び替え ----

    /** ドラッグで from を to の位置へ動かした後の見た目の並び */
    private fun moved(rows: List<TaskRow>, from: Int, to: Int): List<TaskRow> {
        val l = rows.toMutableList()
        l.add(to, l.removeAt(from))
        return l
    }

    @Test
    fun `同じ段に落とせば並びだけが変わる`() {
        val rows = TaskList.build(flat, TaskSort.MANUAL, emptySet(), DoneMode.INLINE)   // 中間,新しい,古い
        val after = moved(rows, 2, 0)                                        // 古い,中間,新しい
        val drop = TaskList.dropTarget(after, flat, 0, 0)!!
        assertNull(drop.parentId)
        assertEquals(listOf(1L, 2L, 3L), drop.siblingIds)
    }

    @Test
    fun `1段深くすると上の行の子になる`() {
        val rows = TaskList.build(flat, TaskSort.MANUAL, emptySet(), DoneMode.INLINE)   // 中間,新しい,古い
        val drop = TaskList.dropTarget(rows, flat, 1, 1)!!
        assertEquals("すぐ上の「中間」の子になる", 2L, drop.parentId)
        assertEquals(1, drop.level)
    }

    @Test
    fun `上に行が無ければ深くできない`() {
        val rows = TaskList.build(flat, TaskSort.MANUAL, emptySet(), DoneMode.INLINE)
        val drop = TaskList.dropTarget(rows, flat, 0, 3)!!
        assertNull("先頭は誰の子にもなれない", drop.parentId)
        assertEquals(0, drop.level)
    }

    @Test
    fun `2段以上は飛べない`() {
        // 「中間」(0段) の下に「新しい」を3段目で落としても、2段目までしか入れない
        val rows = TaskList.build(flat, TaskSort.MANUAL, emptySet(), DoneMode.INLINE)
        assertEquals(1, TaskList.dropTarget(rows, flat, 1, 3)!!.level)
    }

    @Test
    fun `段を0にすれば子をやめて最上位に戻る`() {
        val rows = TaskList.build(tree, TaskSort.MANUAL, emptySet(), DoneMode.INLINE)
        val magoIndex = rows.indexOfFirst { it.item.name == "孫" }
        val drop = TaskList.dropTarget(rows, tree, magoIndex, 0)!!
        assertNull(drop.parentId)
    }

    @Test
    fun `自分の子孫の中には落とせない`() {
        // 「親」を「孫」の下（2段目）へ落とそうとする。許すと木が輪になって一覧が出なくなる
        val rows = TaskList.build(tree, TaskSort.MANUAL, emptySet(), DoneMode.INLINE)
        val after = moved(rows, 0, 2)
        val drop = TaskList.dropTarget(after, tree, 2, 2)
        assertTrue(
            "自分の子孫が親になった",
            drop == null || drop.parentId !in TaskList.subtreeIds(tree, 1L)
        )
    }

    @Test
    fun `畳んで見えていない兄弟も並びから漏らさない`() {
        // 「親」を畳んだ状態で「単独」をその子にする。子1・子2は行に出ていない
        val rows = TaskList.build(tree, TaskSort.MANUAL, setOf(1L), DoneMode.INLINE)   // 親,単独
        val drop = TaskList.dropTarget(rows, tree, 1, 1)!!
        assertEquals(1L, drop.parentId)
        assertEquals(
            "見えていない子が並びから消える",
            setOf(2L, 3L, 9L), drop.siblingIds.toSet()
        )
    }

    @Test
    fun `塊のタブで一番浅く置いても塊の外へは出ない`() {
        // 「親」のタブを開いている。子1(と孫)・子2 が0段目に並ぶ
        val inTab = TaskTabs.apply(tree, "g:1")
        val rows = TaskList.build(inTab, TaskSort.MANUAL, emptySet(), DoneMode.INLINE)  // 子1,孫,子2
        val after = moved(rows, rows.indexOfFirst { it.item.name == "子2" }, 0)        // 子2,子1,孫
        val drop = TaskList.dropTarget(after, tree, 0, 0, rootParentId = 1L)!!
        assertEquals(1L, drop.parentId)
        assertEquals(listOf(3L, 2L), drop.siblingIds)
    }

    @Test
    fun `塊のタブで孫を0段目へ出すと、塊の直下に来る`() {
        val inTab = TaskTabs.apply(tree, "g:1")
        val rows = TaskList.build(inTab, TaskSort.MANUAL, emptySet(), DoneMode.INLINE)  // 子1,孫,子2
        val mago = rows.indexOfFirst { it.item.name == "孫" }
        val drop = TaskList.dropTarget(rows, tree, mago, 0, rootParentId = 1L)!!
        assertEquals(1L, drop.parentId)
        assertEquals(listOf(2L, 4L, 3L), drop.siblingIds)
    }

    @Test
    fun `絞って見えていない兄弟は元の位置のまま残る`() {
        // A,B,C,D のうち A と C だけが見えている（タグで絞った等）。C を A の上へ
        val four = listOf(task(1, "A", order = 1), task(2, "B", order = 2),
            task(3, "C", order = 3), task(4, "D", order = 4))
        val shown = four.filter { it.id == 1L || it.id == 3L }
        val rows = moved(TaskList.build(shown, TaskSort.MANUAL, emptySet(), DoneMode.INLINE), 1, 0)  // C,A
        val drop = TaskList.dropTarget(rows, four, 0, 0)!!
        // B は A の後ろ、D は C の後ろ（元の並びで直前にいた見えている兄弟の後ろ）に付く
        assertEquals(listOf(3L, 1L, 2L, 4L), drop.siblingIds)
    }

    @Test
    fun `場所ごとのタブ。葉の場所で絞り、親は並びのために残す`() {
        val places = dev.togar.dynasched.Places.DEFAULT + dev.togar.dynasched.Place("p1", "寮")
        val tabs = TaskTabs.tabs(tree, TabSource.PLACE, places)
        assertEquals(listOf("", "p:home", "p:out", "p:p1", "p:anywhere"), tabs.map { it.key })
        assertEquals("寮のみ", tabs[3].label)
        val items = listOf(
            HobbyItem(id = 1, name = "帰省", parentId = null, isCompleted = false, location = "anywhere"),
            HobbyItem(id = 2, name = "部屋の片付け", parentId = 1, isCompleted = false, location = "home"),
            HobbyItem(id = 3, name = "レポート", parentId = 1, isCompleted = false, location = "p1"),
            HobbyItem(id = 4, name = "散歩", parentId = null, isCompleted = false, location = "")
        )
        assertEquals(listOf(1L, 2L), TaskTabs.apply(items, "p:home").map { it.id })
        assertEquals(listOf(1L, 3L), TaskTabs.apply(items, "p:p1").map { it.id })
        // 親は葉ではないので「どこでも」には数えない。場所の空はどこでも
        assertEquals(listOf(4L), TaskTabs.apply(items, "p:anywhere").map { it.id })
    }

    @Test
    fun `場所のタブの下段に、入れ子の塊も並ぶ`() {
        fun t(id: Long, name: String, parent: Long? = null, loc: String = "home") =
            HobbyItem(id = id, name = name, parentId = parent, isCompleted = false, location = loc)
        val items = listOf(
            t(1, "帰省"), t(2, "部屋", 1), t(3, "机", 2), t(4, "棚", 2),
            t(5, "庭", 1), t(6, "草取り", 5, loc = "p1"),     // 庭の中身は寮でできる
            t(7, "買い物", loc = "out")
        )
        val subs = TaskTabs.subTabs(items, "p:home")
        assertEquals(listOf("ぜんぶ", "帰省", "帰省 › 部屋"), subs.map { it.label })
        // 塊のタブと同じく、塊そのものは出さず中身だけ
        assertEquals(listOf(3L, 4L), TaskTabs.apply(items, subs[2].key).map { it.id })
        assertEquals(2L, TaskTabs.groupIdOf(subs[2].key))
        // 塊の無い場所には下段を出さない
        assertEquals(emptyList<TaskTab>(), TaskTabs.subTabs(items, "p:out"))
    }

    @Test
    fun `タブの並びを当てる。先頭は動かさず、新しいタブは後ろ`() {
        val tabs = listOf(TaskTab("", "すべて"), TaskTab("g:1", "仕事"),
            TaskTab("g:2", "家事"), TaskTab("g:3", "趣味"))
        val r = TaskTabs.applyOrder(tabs, listOf("g:2", "g:1"))
        assertEquals(listOf("", "g:2", "g:1", "g:3"), r.map { it.key })
        assertEquals(tabs, TaskTabs.applyOrder(tabs, emptyList()))
    }

    @Test
    fun `タブの並びを書き戻しても、出ていないタブの並びは残る`() {
        val saved = listOf("t:買い物", "g:1", "g:2")
        assertEquals(listOf("g:2", "g:1", "t:買い物"), TaskTabs.saveOrder(saved, listOf("g:2", "g:1")))
    }

    @Test
    fun `優先度順で動かすと落とした先の優先度になる`() {
        val list = listOf(
            task(1, "高1", priority = 8), task(2, "高2", priority = 8),
            task(3, "低", priority = 3)
        )
        val rows = TaskList.build(list, TaskSort.PRIORITY, emptySet(), DoneMode.INLINE)
        // 「低」を先頭（高の塊の中）へ持っていく
        assertEquals(8, TaskList.priorityAfterMove(rows, 3L, 1))
        // 末尾へ戻せば下の隣に合わせる（隣が無ければ上の隣）
        assertEquals(8, TaskList.priorityAfterMove(rows, 3L, 2))
    }
}
