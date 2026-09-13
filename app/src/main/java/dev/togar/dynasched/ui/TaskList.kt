package dev.togar.dynasched.ui

import dev.togar.dynasched.api.HobbyItem

/**
 * 単発タスクの一覧を「どう並べてどこまで見せるか」だけを決める。
 *
 * 画面（RecyclerView）から切り離してあるのは、並び替え・折りたたみ・完了の
 * まとめ方が絡むと**目で確かめるのが一番あてにならない**ため。ここは純関数だけに
 * して、テストで固定する。
 *
 * 木の形は必ず保つ。完了を下にまとめるのも、折りたたむのも、
 * **兄弟の中での話**に閉じている。親子をまたいで動かすと階層が壊れる。
 */

/** 並び順。どれも「兄弟どうしの比較」に使う */
enum class TaskSort(val label: String) {
    MANUAL("手動（並び替え順）"),
    NEWEST("追加が新しい順"),
    OLDEST("追加が古い順"),
    PRIORITY("優先度が高い順"),
    LONGEST("必要時間が長い順"),
    SHORTEST("必要時間が短い順");

    companion object {
        fun from(name: String?): TaskSort =
            entries.firstOrNull { it.name == name } ?: MANUAL
    }
}

/**
 * 完了したタスクの見せ方。
 *
 * どれが良いかは使い方で変わる（済んだものを実績として見たい人と、
 * 残りだけ見たい人がいる）ので、選べるようにしてある。
 */
enum class DoneMode(val label: String) {
    INLINE("その場に薄く残す"),
    BOTTOM("下にまとめる"),
    HIDDEN("隠す");

    companion object {
        fun from(name: String?): DoneMode = entries.firstOrNull { it.name == name } ?: INLINE
    }
}

/** 画面に出す1行 */
data class TaskRow(
    val item: HobbyItem,
    val level: Int,
    val hasChildren: Boolean,
    val collapsed: Boolean,
    /** 配下の葉タスクの数・完了数・合計分数（折りたたみ中の要約に使う） */
    val leafCount: Int = 0,
    val leafDone: Int = 0,
    val leafMinutes: Int = 0
) {
    /** 折りたたんだ親の1行に出す要約 */
    fun summary(): String {
        if (!hasChildren) return ""
        val h = leafMinutes / 60
        val m = leafMinutes % 60
        val dur = if (h > 0) "${h}時間${if (m > 0) "${m}分" else ""}" else "${m}分"
        return "${leafCount}件（済${leafDone}）・$dur"
    }
}

object TaskList {

    /**
     * 一覧を組む。
     *
     * @param collapsed 折りたたんでいる親のID。配下は行として出さない
     * @param doneMode 完了したものをどう扱うか
     */
    fun build(
        all: List<HobbyItem>,
        sort: TaskSort,
        collapsed: Set<Long>,
        doneMode: DoneMode
    ): List<TaskRow> {
        val source = if (doneMode == DoneMode.HIDDEN) withoutDone(all) else all
        val doneAtBottom = doneMode == DoneMode.BOTTOM
        return buildTree(source, sort, collapsed, doneAtBottom)
    }

    /**
     * 完了したものを取り除く。
     *
     * **配下が全部済んだ親も一緒に消す。** 残すと、中身が空の見出しだけが
     * 並んで「終わったのに片付かない」状態になる。親自身は完了できないので、
     * 子孫がすべて消えたかどうかで判断する。
     */
    private fun withoutDone(all: List<HobbyItem>): List<HobbyItem> {
        val byParent = all.groupBy { it.parentId }
        val keep = HashSet<Long>()

        fun visit(item: HobbyItem, seen: MutableSet<Long>): Boolean {
            if (!seen.add(item.id)) return false
            val kids = byParent[item.id].orEmpty()
            if (kids.isEmpty()) {
                if (item.isCompleted) return false
                keep.add(item.id)
                return true
            }
            var any = false
            for (k in kids) if (visit(k, seen)) any = true
            if (any) keep.add(item.id)
            return any
        }

        val existing = all.mapTo(HashSet()) { it.id }
        for (r in all) {
            if (r.parentId == null || !existing.contains(r.parentId)) visit(r, HashSet())
        }
        return all.filter { keep.contains(it.id) }
    }

    private fun buildTree(
        all: List<HobbyItem>,
        sort: TaskSort,
        collapsed: Set<Long>,
        doneAtBottom: Boolean
    ): List<TaskRow> {
        val byParent = all.groupBy { it.parentId }
        val existing = all.mapTo(HashSet()) { it.id }
        val out = ArrayList<TaskRow>(all.size)
        val visited = HashSet<Long>()

        fun childrenOf(id: Long?): List<HobbyItem> =
            byParent[id].orEmpty().sortedWith(comparator(sort, doneAtBottom))

        /** 配下の葉を数える（自分が葉なら自分を数える） */
        fun leaves(item: HobbyItem, seen: MutableSet<Long>): Triple<Int, Int, Int> {
            if (!seen.add(item.id)) return Triple(0, 0, 0)
            val kids = byParent[item.id].orEmpty()
            if (kids.isEmpty()) {
                return Triple(1, if (item.isCompleted) 1 else 0, item.durationMinutes)
            }
            var n = 0; var done = 0; var min = 0
            for (k in kids) {
                val (a, b, c) = leaves(k, seen)
                n += a; done += b; min += c
            }
            return Triple(n, done, min)
        }

        fun emit(item: HobbyItem, level: Int) {
            if (!visited.add(item.id)) return   // 循環参照ガード
            val kids = childrenOf(item.id)
            val isCollapsed = kids.isNotEmpty() && collapsed.contains(item.id)
            val agg = if (kids.isEmpty()) Triple(0, 0, 0) else leaves(item, HashSet())
            out.add(
                TaskRow(
                    item = item,
                    level = level,
                    hasChildren = kids.isNotEmpty(),
                    collapsed = isCollapsed,
                    leafCount = agg.first,
                    leafDone = agg.second,
                    leafMinutes = agg.third
                )
            )
            if (isCollapsed) return
            for (k in kids) emit(k, level + 1)
        }

        // ルート＝parent_id が null、または親が消えている孤児
        val roots = all.filter { it.parentId == null || !existing.contains(it.parentId) }
            .sortedWith(comparator(sort, doneAtBottom))
        for (r in roots) emit(r, 0)
        return out
    }

    /**
     * 兄弟どうしの比較。
     *
     * 完了を下へ寄せる指定があるときは、**どの並び順よりも先に**効かせる。
     * そうしないと「済んだものが優先度順の途中に居座る」ことになる。
     */
    fun comparator(sort: TaskSort, doneAtBottom: Boolean): Comparator<HobbyItem> {
        val inner = when (sort) {
            TaskSort.MANUAL -> compareBy<HobbyItem>({ it.sortOrder }, { it.id })
            TaskSort.NEWEST -> compareByDescending { it.id }          // idは採番順＝追加順
            TaskSort.OLDEST -> compareBy { it.id }
            TaskSort.PRIORITY -> compareBy<HobbyItem> { -it.priority }
                .thenBy { it.sortOrder }.thenBy { it.id }
            TaskSort.LONGEST -> compareBy<HobbyItem> { -it.durationMinutes }.thenBy { it.id }
            TaskSort.SHORTEST -> compareBy<HobbyItem>({ it.durationMinutes }, { it.id })
        }
        return if (doneAtBottom) compareBy<HobbyItem> { it.isCompleted }.then(inner) else inner
    }

    /**
     * ある親の配下すべて（親自身は含めない）。
     * 親が抜けるので、直下の子が根として並ぶ。
     */
    fun descendantsOf(all: List<HobbyItem>, parentId: Long): List<HobbyItem> {
        val keep = subtreeIds(all, parentId) - parentId
        return all.filter { keep.contains(it.id) }
    }

    /** ドロップ結果：新しい親と、その親の子の新しい並び */
    data class Drop(val parentId: Long?, val siblingIds: List<Long>, val level: Int)

    /** id の子孫すべて（自分も含む）。自分の中へは落とせないので必ず要る */
    fun subtreeIds(all: List<HobbyItem>, id: Long): Set<Long> {
        val byParent = all.groupBy { it.parentId }
        val out = HashSet<Long>()
        fun walk(x: Long) {
            if (!out.add(x)) return
            byParent[x].orEmpty().forEach { walk(it.id) }
        }
        walk(id)
        return out
    }

    /**
     * ドラッグして離した所から、新しい親と並びを決める。
     *
     * 縦の位置だけでは「すぐ上の行の子になりたいのか、隣に並びたいのか」が決まらない。
     * そこで**横のずれで段（level）を指定する**形にしてある。指を右に送れば1段深くなる。
     *
     * @param rows 見た目の並び（動かした後）。moved は dropIndex に居る
     * @param all  全タスク。畳んで見えていない子も含めて数える必要がある
     * @param desiredLevel 横のずれから割り出した段。ここでは上の行より深くならないよう丸める
     */
    fun dropTarget(
        rows: List<TaskRow>, all: List<HobbyItem>, dropIndex: Int, desiredLevel: Int
    ): Drop? {
        val moved = rows.getOrNull(dropIndex)?.item ?: return null
        val subtree = subtreeIds(all, moved.id)

        // 自分の子孫の行は「上の行」として数えない。数えると自分の中へ落ちてしまう
        val aboveRows = rows.take(dropIndex).filter { it.item.id !in subtree }
        val above = aboveRows.lastOrNull()
        // 上の行より2段以上深くはできない（間に親が居ないため）
        val level = desiredLevel.coerceIn(0, if (above == null) 0 else above.level + 1)

        val parentId: Long? =
            if (level == 0) null
            else aboveRows.lastOrNull { it.level == level - 1 }?.item?.id ?: return null
        if (parentId != null && parentId in subtree) return null

        // 兄弟の並び。畳まれていて見えていない子は見える分の後ろへ回す
        val visible = rows.filter { it.item.id == moved.id || it.item.parentId == parentId }
            .map { it.item.id }
            .filter { it == moved.id || it !in subtree }
        val hidden = all.filter { it.parentId == parentId && it.id !in visible && it.id !in subtree }
            .map { it.id }
        return Drop(parentId, visible + hidden, level)
    }

    /**
     * 優先度の並びで動かしたときの、新しい優先度。
     *
     * 優先度は 低3/中5/高8 の3段しかないので、**落とした場所の隣に合わせる**のが
     * 一番素直に効く（高の集まりへ落としたら高になる）。
     */
    fun priorityAfterMove(rows: List<TaskRow>, movedId: Long, newIndex: Int): Int? {
        val others = rows.filter { it.item.id != movedId }
        val above = others.getOrNull(newIndex - 1)?.item?.priority
        val below = others.getOrNull(newIndex)?.item?.priority
        return above ?: below
    }
}

/** タブの中身を何にするか */
enum class TabSource(val label: String) {
    NONE("タブを出さない"),
    /** 最上位のグループだけ。入れ子の小グループはタブにしない */
    TOP("一番上のグループだけ"),
    /** 子を持つタスクは全部タブにする。深い所の小グループも並ぶ */
    GROUP("グループごと（入れ子も）"),
    TAG("タグごと");

    companion object {
        fun from(name: String?): TabSource = entries.firstOrNull { it.name == name } ?: NONE
    }
}

/** 横に並ぶタブ1つ */
data class TaskTab(val key: String, val label: String)

/**
 * タスクをグループ／タグで横に並べる。
 *
 * 木を深く辿るより、まず「どの塊の話か」を選ぶほうが速い場面がある。
 * ただし**塊を選んでいる間は全体が見えない**ので、階層をいじる操作は
 * 「すべて」タブに限る（見えていない場所へタスクが飛ぶ事故を作らないため）。
 */
object TaskTabs {

    /** 「すべて」タブのキー。常に先頭に置く */
    const val ALL = ""

    fun tabs(all: List<HobbyItem>, source: TabSource): List<TaskTab> {
        if (source == TabSource.NONE) return emptyList()
        val head = TaskTab(ALL, "すべて")
        return when (source) {
            // 入れ子の小グループまで並べると、深いほどタブが増えて探しにくくなる。
            // 「一番上だけ」はその逃げ道で、島の数＝タブの数になる
            TabSource.TOP -> listOf(head) + groupTabs(all) { it.parentId == null }
            TabSource.GROUP -> listOf(head) + groupTabs(all) { true }
            TabSource.TAG -> listOf(head) + Tags.known(all).map { TaskTab("t:$it", "#$it") }
            TabSource.NONE -> emptyList()
        }
    }

    /** 子を持つタスクをタブにする。[keep] で範囲を絞る */
    private fun groupTabs(all: List<HobbyItem>, keep: (HobbyItem) -> Boolean): List<TaskTab> {
        val hasChild = all.mapNotNullTo(HashSet()) { it.parentId }
        return all.filter { hasChild.contains(it.id) && keep(it) }
            .sortedWith(TaskList.comparator(TaskSort.MANUAL, false))
            .map { TaskTab("g:${it.id}", it.name) }
    }

    /**
     * そのタブで見せるタスク。キーが古くて当てはまらない時は全部返す。
     *
     * [hideGrouped] を立てると、**「すべて」タブからタブになっている塊を外す。**
     * 塊は自分のタブで見られるので、すべてにも出すと同じものを二度見ることになる。
     * 残るのはどのタブにも属さないタスクだけ。
     */
    fun apply(
        all: List<HobbyItem>,
        key: String,
        source: TabSource = TabSource.NONE,
        hideGrouped: Boolean = false
    ): List<HobbyItem> = when {
        key.startsWith("g:") -> {
            val id = key.removePrefix("g:").toLongOrNull()
            // 親自身は出さない。タブの見出しが親の名前なので、繰り返しても意味が無い
            if (id == null) all else TaskList.descendantsOf(all, id)
        }
        key.startsWith("t:") -> Tags.filterTree(all, setOf(key.removePrefix("t:")))
        hideGrouped -> withoutTabbed(all, source)
        else -> all
    }

    /** タブになっている塊（と、その配下）を落とす */
    private fun withoutTabbed(all: List<HobbyItem>, source: TabSource): List<HobbyItem> {
        val groupIds = tabs(all, source).mapNotNull { it.key.removePrefix("g:").toLongOrNull() }
        if (groupIds.isEmpty()) return all
        val drop = HashSet<Long>(groupIds)
        for (id in groupIds) TaskList.descendantsOf(all, id).forEach { drop.add(it.id) }
        return all.filterNot { drop.contains(it.id) }
    }

    /**
     * 選んでいる塊の中の小グループ。**タブの中のタブ**に使う。
     *
     * 直下の子のうち、さらに子を持つものだけ。孫より下は降りない
     * （降りると、下段のタブが上段と同じだけ増えて意味が無くなる）。
     */
    fun subTabs(all: List<HobbyItem>, key: String): List<TaskTab> {
        val id = key.removePrefix("g:").toLongOrNull()
        if (!key.startsWith("g:") || id == null) return emptyList()
        val hasChild = all.mapNotNullTo(HashSet()) { it.parentId }
        val subs = all.filter { it.parentId == id && hasChild.contains(it.id) }
            .sortedWith(TaskList.comparator(TaskSort.MANUAL, false))
        if (subs.isEmpty()) return emptyList()
        // 先頭は「この塊ぜんぶ」。下段を出した時に全体へ戻れなくなるのを防ぐ
        return listOf(TaskTab(key, "ぜんぶ")) + subs.map { TaskTab("g:${it.id}", it.name) }
    }

    /** そのタブが指している塊のID。塊タブでなければ null（追加先を決めるのに使う） */
    fun groupIdOf(key: String): Long? =
        if (key.startsWith("g:")) key.removePrefix("g:").toLongOrNull() else null

    /** そのキーのタブがまだ存在するか（グループを消した後などに効く） */
    fun exists(tabs: List<TaskTab>, key: String): Boolean = tabs.any { it.key == key }
}
