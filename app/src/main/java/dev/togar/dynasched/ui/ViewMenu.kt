package dev.togar.dynasched.ui

/**
 * 「並び順と表示」の中身。
 *
 * もとは13行の平らな一覧で、**どこまでが並び順で、どこからが完了の扱いなのかが
 * 見て分からなかった**。押した位置から `i - sorts.size` のように引き算して
 * 何を選ばれたか当てていたので、行を足すたびに壊れる作りでもあった。
 *
 * 区切りと行の種類を型として持たせて、その両方をやめる。
 * ここは純粋な組み立てだけにしてテストで固定し、見た目は [ViewMenuDialog] に置く。
 */
sealed class ViewMenuRow {
    /** 節の見出し。押せない */
    data class Section(val title: String) : ViewMenuRow()

    /**
     * 節の中からひとつだけ選ぶ行。丸印が付く。
     * 丸が縦に並んで節の境目で途切れるので、**どこまでが同じ選択肢か**が目で分かる。
     */
    data class Choice(
        val action: ViewMenuAction,
        val label: String,
        val selected: Boolean
    ) : ViewMenuRow()

    /**
     * 押すと何かが起きる行。丸印は付けない（選ぶものではないと区別するため）。
     * [value] は現在の状態、[opensDialog] は次の画面が開くかどうか。
     */
    data class Action(
        val action: ViewMenuAction,
        val label: String,
        val value: String? = null,
        val opensDialog: Boolean = false
    ) : ViewMenuRow()
}

/** 行を押したときにやること。位置ではなくこれで受け渡す */
sealed class ViewMenuAction {
    data class Sort(val sort: TaskSort) : ViewMenuAction()
    data class Done(val mode: DoneMode) : ViewMenuAction()
    object TagFilter : ViewMenuAction()
    object TabSource : ViewMenuAction()
    object HideGrouped : ViewMenuAction()
    object StatsSpan : ViewMenuAction()
    object CollapseAll : ViewMenuAction()
    object ExpandAll : ViewMenuAction()
}

object ViewMenu {

    const val SORT = "並び順"
    const val DONE = "完了したタスク"
    const val NARROW = "絞り込みとタブ"
    const val COUNT = "数える"
    const val BULK = "まとめて操作"

    /**
     * 上から「並び順」「完了したタスク」「絞り込みとタブ」「まとめて操作」の4節。
     *
     * 前の2つは選択肢、後ろの2つは操作。**選ぶ行と押す行を混ぜない**ように分けてある。
     */
    fun rows(
        sort: TaskSort,
        done: DoneMode,
        tabSource: TabSource,
        tagFilterCount: Int,
        hideGrouped: Boolean = false,
        statsLabel: String = ""
    ): List<ViewMenuRow> {
        val rows = ArrayList<ViewMenuRow>()

        rows.add(ViewMenuRow.Section(SORT))
        TaskSort.entries.forEach {
            rows.add(ViewMenuRow.Choice(ViewMenuAction.Sort(it), it.label, it == sort))
        }

        // 見出しに「完了した」を持たせたので、各行は扱いだけを言えば済む
        rows.add(ViewMenuRow.Section(DONE))
        DoneMode.entries.forEach {
            rows.add(ViewMenuRow.Choice(ViewMenuAction.Done(it), it.label, it == done))
        }

        rows.add(ViewMenuRow.Section(NARROW))
        rows.add(
            ViewMenuRow.Action(
                ViewMenuAction.TagFilter, "タグで絞り込む",
                value = if (tagFilterCount <= 0) "なし" else "${tagFilterCount}個",
                opensDialog = true
            )
        )
        rows.add(
            ViewMenuRow.Action(
                ViewMenuAction.TabSource, "横に並べるタブ",
                value = tabSource.label, opensDialog = true
            )
        )

        rows.add(
            ViewMenuRow.Action(
                ViewMenuAction.HideGrouped, "「すべて」から塊を外す",
                value = if (hideGrouped) "外す" else "出す"
            )
        )

        rows.add(ViewMenuRow.Section(COUNT))
        rows.add(
            ViewMenuRow.Action(ViewMenuAction.StatsSpan, "済んだ数・増えた数",
                value = statsLabel, opensDialog = true)
        )

        rows.add(ViewMenuRow.Section(BULK))
        rows.add(ViewMenuRow.Action(ViewMenuAction.CollapseAll, "すべて畳む"))
        rows.add(ViewMenuRow.Action(ViewMenuAction.ExpandAll, "すべて開く"))

        return rows
    }
}
