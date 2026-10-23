package dev.togar.dynasched.ui

import dev.togar.dynasched.api.HobbyItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * グループのタブ（上段＝一番上のグループ、下段＝その中の小グループ）。
 *
 * タブは「いま見えている範囲」を決める。ずれると**あるはずのタスクが出てこない**か、
 * 中が全部済んだ空のタブが並び続ける。
 */
class GroupTabsTest {

    private fun t(id: Long, name: String, parent: Long? = null, done: Boolean = false, order: Int = 0) =
        HobbyItem(id = id, name = name, parentId = parent, isCompleted = done, sortOrder = order)

    private val tree = listOf(
        t(1, "仕事", order = 1),
        t(2, "資料作り", 1, order = 1),
        t(3, "図を描く", 2),
        t(4, "メール返信", 1, order = 2),
        t(5, "引っ越し", order = 2),
        t(6, "段ボール", 5, done = true),
        t(7, "単独", order = 3)
    )

    @Test
    fun `上段は一番上のグループだけ。先頭はすべて`() {
        val tabs = GroupTabs.top(tree)
        assertEquals(listOf("", "g:1", "g:5"), tabs.map { it.key })
        assertEquals("すべて", tabs.first().label)
    }

    @Test
    fun `下段はその中の小グループ。先頭はぜんぶ`() {
        val subs = GroupTabs.sub(tree, "g:1")
        assertEquals(listOf("g:1", "g:2"), subs.map { it.key })
        assertEquals("ぜんぶ", subs.first().label)
        assertTrue(GroupTabs.sub(tree, "g:5").isEmpty())
        assertTrue(GroupTabs.sub(tree, GroupTabs.ALL).isEmpty())
    }

    @Test
    fun `塊のタブは中身だけ。消えた塊なら全部`() {
        assertEquals(listOf(2L, 3L, 4L), GroupTabs.apply(tree, "g:1").map { it.id })
        assertEquals(tree.size, GroupTabs.apply(tree, "g:999").size)
        assertEquals(tree.size, GroupTabs.apply(tree, GroupTabs.ALL).size)
    }

    @Test
    fun `中が全部済んだ塊は済んだタブになる`() {
        val tabs = GroupTabs.top(tree).associateBy { it.key }
        assertTrue(tabs.getValue("g:5").done)
        assertTrue(!tabs.getValue("g:1").done)
    }

    @Test
    fun `済んだタブは右端へ、隠す時も開いているタブは残す`() {
        val list = listOf(TaskTab("", "すべて"), TaskTab("g:5", "引っ越し", done = true), TaskTab("g:1", "仕事"))
        assertEquals(listOf("", "g:1", "g:5"), GroupTabs.arrange(list, DoneTabs.END, "").map { it.key })
        assertEquals(listOf("", "g:5", "g:1"), GroupTabs.arrange(list, DoneTabs.DIM, "").map { it.key })
        assertEquals(listOf("", "g:1"), GroupTabs.arrange(list, DoneTabs.HIDE, "").map { it.key })
        assertEquals(listOf("", "g:5", "g:1"), GroupTabs.arrange(list, DoneTabs.HIDE, "g:5").map { it.key })
    }

    @Test
    fun `タブの並びを当てる。先頭は動かさず、新しいタブは後ろ`() {
        val tabs = listOf(TaskTab("", "すべて"), TaskTab("g:1", "仕事"), TaskTab("g:2", "家事"), TaskTab("g:3", "趣味"))
        assertEquals(listOf("", "g:2", "g:1", "g:3"), GroupTabs.applyOrder(tabs, listOf("g:2", "g:1")).map { it.key })
        assertEquals(tabs, GroupTabs.applyOrder(tabs, emptyList()))
        // 出ていないタブの並びは残す
        assertEquals(listOf("g:2", "g:1", "g:9"), GroupTabs.saveOrder(listOf("g:9", "g:1", "g:2"), listOf("g:2", "g:1")))
    }

    @Test
    fun `塊を別の塊へ動かせるのは、自分の中へ入れない時だけ`() {
        assertTrue(GroupTabs.canMoveInto(tree, 4L, 5L))      // メール返信 → 引っ越し（別の島）
        assertTrue(GroupTabs.canMoveInto(tree, 4L, null))    // 最上位へはいつでも
        assertFalse(GroupTabs.canMoveInto(tree, 1L, 1L))     // 自分の下へ
        assertFalse(GroupTabs.canMoveInto(tree, 1L, 3L))     // 自分の孫の下へ（輪になる）
        assertFalse(GroupTabs.canMoveInto(tree, 1L, 2L))     // 自分の子の下へ
    }

    @Test
    fun `塊のキーからID`() {
        assertEquals(7L, GroupTabs.groupIdOf("g:7"))
        assertNull(GroupTabs.groupIdOf(GroupTabs.ALL))
    }
}
