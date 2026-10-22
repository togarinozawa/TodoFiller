package dev.togar.dynasched.ui

import dev.togar.dynasched.api.HobbyItem

/** 横に並ぶタブ1つ。[done] は中のタスクが全部済んだグループ */
data class TaskTab(val key: String, val label: String, val done: Boolean = false)

/** 中のタスクが全部済んだグループのタブをどう見せるか */
enum class DoneTabs(val label: String) {
    DIM("薄くしてそのまま"),
    END("右端にまとめて薄く"),
    HIDE("隠す");

    companion object {
        fun from(name: String?): DoneTabs = entries.firstOrNull { it.name == name } ?: END
    }
}

/**
 * グループのタブ。上段に一番上のグループ、選んだグループに小グループがあれば下段にそれを出す。
 *
 * 一度は条件チップの「グループ」にまとめたが、どの塊の話かを先に選ぶにはタブの方が速いので戻した。
 * 絞り込み（[TaskFilter]）はタブの中にさらに掛かる。
 *
 * 塊のタブの中で動かしても**塊の外へは出さない**（[TaskList.dropTarget] の rootParentId）。
 * 見えていない場所へタスクが飛んで「消えた」ように見える事故を作らないため。
 *
 * タブの左右の並びは本人が決められる（[applyOrder]）。タスクの並びとは別に持つ。
 */
object GroupTabs {

    /** 「すべて」タブのキー。常に先頭に置く */
    const val ALL = ""

    /** 上段。「すべて」と、子を持つ一番上のタスク */
    fun top(all: List<HobbyItem>): List<TaskTab> =
        listOf(TaskTab(ALL, "すべて")) + groupTabs(all) { it.parentId == null }

    /**
     * 下段。選んでいる塊の直下の小グループ。孫より下は降りない
     * （降りると、下段のタブが上段と同じだけ増えて意味が無くなる）。
     * 先頭は「この塊ぜんぶ」。下段を出した時に全体へ戻れなくなるのを防ぐ。
     */
    fun sub(all: List<HobbyItem>, key: String): List<TaskTab> {
        val id = groupIdOf(key) ?: return emptyList()
        val subs = groupTabs(all) { it.parentId == id }
        if (subs.isEmpty()) return emptyList()
        return listOf(TaskTab(key, "ぜんぶ", isDone(all, id))) + subs
    }

    private fun groupTabs(all: List<HobbyItem>, keep: (HobbyItem) -> Boolean): List<TaskTab> {
        val hasChild = all.mapNotNullTo(HashSet()) { it.parentId }
        return all.filter { it.id in hasChild && keep(it) }
            .sortedWith(TaskList.comparator(TaskSort.MANUAL, false))
            .map { TaskTab("g:${it.id}", it.name, isDone(all, it.id)) }
    }

    /** 中のタスク（葉）が1つも残っていない塊か */
    fun isDone(all: List<HobbyItem>, groupId: Long): Boolean {
        val parents = all.mapNotNullTo(HashSet()) { it.parentId }
        return TaskList.descendantsOf(all, groupId).none { it.id !in parents && !it.isCompleted }
    }

    /** そのタブで見せるタスク。塊のタブなら中身だけ（見出しと同じ名前を繰り返さない）。古いキーなら全部 */
    fun apply(all: List<HobbyItem>, key: String): List<HobbyItem> {
        val id = groupIdOf(key) ?: return all
        if (all.none { it.id == id }) return all
        return TaskList.descendantsOf(all, id)
    }

    fun groupIdOf(key: String): Long? =
        if (key.startsWith("g:")) key.removePrefix("g:").toLongOrNull() else null

    /**
     * 済んだ塊のタブの扱い。先頭（「すべて」「ぜんぶ」）は動かさない。
     * 隠す時も、いま開いているタブ（[selected]）は残す。開いたまま消えると、どこにいるか分からなくなる
     */
    fun arrange(list: List<TaskTab>, mode: DoneTabs, selected: String): List<TaskTab> {
        if (list.size <= 1) return list
        val head = list.first()
        val rest = list.drop(1)
        return listOf(head) + when (mode) {
            DoneTabs.DIM -> rest
            DoneTabs.END -> rest.filterNot { it.done } + rest.filter { it.done }
            DoneTabs.HIDE -> rest.filter { !it.done || it.key == selected }
        }
    }

    /**
     * 決めておいたタブの並びを当てる。先頭の [fixedHead] 個（「すべて」「ぜんぶ」）は動かさない。
     * 並びを決めた後に増えたタブは、元の順のまま後ろに付ける。
     */
    fun applyOrder(list: List<TaskTab>, order: List<String>, fixedHead: Int = 1): List<TaskTab> {
        if (order.isEmpty() || list.size <= fixedHead) return list
        val rank = order.withIndex().associate { it.value to it.index }
        val head = list.take(fixedHead)
        val (known, fresh) = list.drop(fixedHead).partition { rank.containsKey(it.key) }
        return head + known.sortedBy { rank.getValue(it.key) } + fresh
    }

    /**
     * 並べ替えた結果を、保存してある並びへ書き戻す。
     * いま出ていないタブ（別の塊の下段など）の並びは消さずに後ろへ残す。
     */
    fun saveOrder(saved: List<String>, reordered: List<String>): List<String> {
        val now = reordered.toHashSet()
        return reordered + saved.filterNot { it in now }
    }

    fun exists(tabs: List<TaskTab>, key: String): Boolean = tabs.any { it.key == key }
}
