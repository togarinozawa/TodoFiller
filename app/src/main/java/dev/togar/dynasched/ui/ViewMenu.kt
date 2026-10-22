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
    data class DoneTab(val mode: DoneTabs) : ViewMenuAction()
    object GroupsLast : ViewMenuAction()
    object TabOrder : ViewMenuAction()
    object PlaceFiltered : ViewMenuAction()
    object PlaceAll : ViewMenuAction()
    object CollapseAll : ViewMenuAction()
    object BulkPlace : ViewMenuAction()
    object PickTomorrow : ViewMenuAction()
    object ExpandAll : ViewMenuAction()
}

object ViewMenu {

    const val SORT = "並び順"
    const val DONE = "完了したタスク"
    const val DONE_TABS = "済んだグループのタブ"
    const val SHOW = "表示"
    const val PLACE = "配置"
    const val BULK = "まとめて操作"

    /**
     * 上から「並び順」「完了したタスク」「済んだグループのタブ」「表示」「配置」「まとめて操作」。
     *
     * 前の3つは選択肢、後ろの3つは操作。**選ぶ行と押す行を混ぜない**ように分けてある。
     * 絞り込みはメニューから外して「絞り込み」ボタンの画面に置いた。
     */
    fun rows(
        sort: TaskSort,
        done: DoneMode,
        doneTabs: DoneTabs = DoneTabs.END,
        groupsLast: Boolean = false,
        /** グループのタブが出ているか。出ていなければ並べ替える物が無い */
        hasTabs: Boolean = false,
        /** 一覧をいま絞り込んでいるか。絞っていないと「この条件だけ配置」は意味が無い */
        filtering: Boolean = false,
        /** 配置の対象を絞っているか */
        placeFiltered: Boolean = false
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

        // 中が全部済んだグループのタブ。残したままだと、空のタブが並び続ける
        rows.add(ViewMenuRow.Section(DONE_TABS))
        DoneTabs.entries.forEach {
            rows.add(ViewMenuRow.Choice(ViewMenuAction.DoneTab(it), it.label, it == doneTabs))
        }

        rows.add(ViewMenuRow.Section(SHOW))
        rows.add(
            ViewMenuRow.Action(
                ViewMenuAction.GroupsLast, "グループを下に回す",
                value = if (groupsLast) "下に回す" else "そのまま"
            )
        )
        // タブを長押ししても開ける。メニューにも置くのは、長押しに気付かない人のため
        if (hasTabs) rows.add(ViewMenuRow.Action(ViewMenuAction.TabOrder, "タブの並びを変える", opensDialog = true))

        rows.add(ViewMenuRow.Section(PLACE))
        if (filtering) {
            rows.add(
                ViewMenuRow.Action(ViewMenuAction.PlaceFiltered, "いまの絞り込みだけ配置する", opensDialog = true)
            )
        }
        rows.add(
            ViewMenuRow.Action(
                ViewMenuAction.PlaceAll, "全部のタスクを配置する",
                value = if (placeFiltered) "絞っている" else "全部"
            )
        )

        rows.add(ViewMenuRow.Section(BULK))
        rows.add(ViewMenuRow.Action(ViewMenuAction.PickTomorrow, "明日やるものを選ぶ", opensDialog = true))
        rows.add(ViewMenuRow.Action(ViewMenuAction.BulkPlace, "場所をまとめて変える", opensDialog = true))
        rows.add(ViewMenuRow.Action(ViewMenuAction.CollapseAll, "すべて畳む"))
        rows.add(ViewMenuRow.Action(ViewMenuAction.ExpandAll, "すべて開く"))

        return rows
    }
}
