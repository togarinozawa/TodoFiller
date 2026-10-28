package dev.togar.dynasched.ui

import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import dev.togar.dynasched.data.Repo
import dev.togar.dynasched.AddTaskActivity
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.R
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.api.HobbyItem
import dev.togar.dynasched.sync.NotionSyncer

/**
 * 単発タスク（階層Todo）画面。
 * 最上位タスク＝グループ、その下に子タスク（分割）をぶら下げられる（2階層）。
 */
class HobbyFragment : Fragment() {

    private lateinit var adapter: HobbyAdapter
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var empty: TextView
    private lateinit var dragHintText: TextView
    private lateinit var sortButton: Button
    private lateinit var filterButton: com.google.android.material.button.MaterialButton
    private lateinit var tabs: com.google.android.material.tabs.TabLayout
    private lateinit var subTabs: com.google.android.material.tabs.TabLayout
    private lateinit var placeFilterText: TextView
    private lateinit var pointPop: TextView
    private lateinit var touchHelper: ItemTouchHelper
    private lateinit var recycler: RecyclerView

    /** 絞り込み。変えるたびに覚える（[Prefs.taskFilter]） */
    private var filter = TaskFilter()

    /** タブを差し替えている最中か。差し替え中の選択通知で二重描画しないため */
    private var rebuildingTabs = false

    /** 下段で選んでいる小グループ。空なら塊ぜんぶ。画面を離れたら忘れてよい */
    private var subTabKey: String = ""

    /** 数えるための控え。**描画のたびにDBを読まない**（メインスレッドが止まる） */
    private var statRowsCache: List<StatRow> = emptyList()

    /** 直近に読み込んだ生データ。並び替え・折りたたみは読み直さずに組み直す */
    private var loaded: List<HobbyItem> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_hobby, container, false)

        empty = root.findViewById(R.id.emptyText)
        swipe = root.findViewById(R.id.swipeRefresh)
        dragHintText = root.findViewById(R.id.dragHintText)
        sortButton = root.findViewById(R.id.sortButton)
        filterButton = root.findViewById(R.id.filterButton)
        tabs = root.findViewById(R.id.taskTabs)
        subTabs = root.findViewById(R.id.taskSubTabs)
        placeFilterText = root.findViewById(R.id.placeFilterText)
        pointPop = root.findViewById(R.id.pointPop)
        recycler = root.findViewById(R.id.recycler)
        // 掴んでいる間の指の位置（画面の座標）を拾う。ItemTouchHelper は座標を教えてくれないので。
        // false を返すので、一覧の動きには口を出さない
        recycler.setOnTouchListener { _, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_MOVE) {
                fingerX = e.rawX
                fingerY = e.rawY
                fingerKnown = true
            }
            false
        }
        val addButton = root.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(
            R.id.addTaskButton
        )

        adapter = HobbyAdapter(
            onToggle = { item, checked -> toggle(item, checked) },
            onAddChild = { item -> openAddChild(item) },
            onDelete = { item -> confirmDelete(item) },
            onEdit = { item -> openEdit(item) },
            onCollapse = { item -> toggleCollapse(item) },
            onSplit = { item -> splitTask(item) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        touchHelper = ItemTouchHelper(DragCallback())
        touchHelper.attachToRecyclerView(recycler)

        addButton.setOnClickListener {
            // 絞り込んでいる条件で始める。条件と違う物を足すと、足した物がその場に出てこない
            val intent = Intent(requireContext(), AddTaskActivity::class.java)
            groupOfCurrentTab()?.let { g ->
                intent.putExtra(AddTaskActivity.EXTRA_PARENT_ID, g.id)
                intent.putExtra(AddTaskActivity.EXTRA_PARENT_NAME, g.name)
            }
            // 場所を1つだけ選んでいれば、最初からその場所にしておく
            filter.places.singleOrNull()?.let { intent.putExtra(AddTaskActivity.EXTRA_LOCATION, it) }
            if (filter.habit == TaskFilter.Habit.ONLY) intent.putExtra(AddTaskActivity.EXTRA_REPEAT, true)
            startActivity(intent)
        }
        sortButton.setOnClickListener { showViewMenu() }
        filterButton.setOnClickListener { openFilterPage() }
        placeFilterText.setOnClickListener { confirmPlaceAll() }

        filter = Prefs.taskFilter(requireContext())
        // v50 ではグループも絞り込みの条件だった。タブに戻したので、そちらへ移す
        filter.groupId?.let { id ->
            Prefs.setTaskTab(requireContext(), "g:$id")
            filter = filter.copy(groupId = null)
            Prefs.setTaskFilter(requireContext(), filter)
        }
        // 絞り込みの画面で「絞り込む」を押された
        parentFragmentManager.setFragmentResultListener(TaskFilterPage.RESULT, viewLifecycleOwner) { _, _ ->
            filter = Prefs.taskFilter(requireContext())
            updateFilterButton()
            render()
        }

        tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                if (rebuildingTabs) return
                Prefs.setTaskTab(requireContext(), tab.tag as? String ?: GroupTabs.ALL)
                subTabKey = ""   // 上段が変われば下段は別物になる
                rebuildSubTabs()
                render()
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
        })
        subTabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                if (rebuildingTabs) return
                subTabKey = tab.tag as? String ?: ""
                render()
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
        })

        // 引き下ろしはNotionから取り直す合図にもする。
        // 開いている間にNotion側で書いたものを、閉じずに拾えるように
        swipe.setOnRefreshListener { syncThenLoad() }
        updateSortLabel()
        updateFilterButton()
        return root
    }

    override fun onResume() {
        super.onResume()
        load()
        syncInBackground()
    }

    // ---- Notion同期 ----

    /**
     * 画面を開いたら裏で同期して、終わったら黙って描き直す。
     *
     * **待たせない。**繋がらない場所でも一覧は端末のDBから出るので、
     * 同期の成否で画面を止める理由が無い。失敗も出さない（設定画面に残る）。
     */
    private fun syncInBackground() {
        val ctx = requireContext().applicationContext
        if (!Prefs.notionReady(ctx)) return
        Api.async({ NotionSyncer.sync(ctx) }, { r ->
            if (isAdded && r.pulled > 0) load()
        }, { })
    }

    /** 引き下ろした時。こちらは結果を見せる */
    private fun syncThenLoad() {
        val ctx = requireContext().applicationContext
        if (!Prefs.notionReady(ctx)) { load(); return }
        Api.async({ NotionSyncer.sync(ctx) }, { r ->
            if (!isAdded) return@async
            load()
            Toast.makeText(requireContext(), r.message(), Toast.LENGTH_SHORT).show()
        }, { e ->
            if (!isAdded) return@async
            load()
            Toast.makeText(requireContext(), Api.friendlyMessage(e), Toast.LENGTH_SHORT).show()
        })
    }

    // ---- 表示の設定 ----

    private fun sortMode(): TaskSort = TaskSort.from(Prefs.taskSort(requireContext()))

    private fun doneMode(): DoneMode = DoneMode.from(Prefs.taskDoneMode(requireContext()))

    /**
     * 「並び替え」の字。完了を隠している間はそれを見せる。気付かないまま
     * 「終わったものが消えた」と思わないように（実際には隠れているだけ）
     */
    private fun updateSortLabel() {
        sortButton.text = if (doneMode() == DoneMode.HIDDEN) "並び替え・済×" else "並び替え"
    }

    /** 絞り込んでいる間は色を付けて、条件の数を出す。気付かないまま「タスクが消えた」と思わないように */
    private fun updateFilterButton() {
        val n = filter.count
        val ctx = requireContext()
        filterButton.text = if (n == 0) "絞り込み" else "絞り込み・$n"
        val on = n > 0
        filterButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (on) ctx.getColor(R.color.primary) else android.graphics.Color.TRANSPARENT
        )
        filterButton.setTextColor(ctx.getColor(if (on) R.color.white else R.color.primary))
    }

    /** 絞り込みの画面。右から重ねて出す（戻ると今の一覧がそのまま残っている） */
    private fun openFilterPage() {
        parentFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.filter_enter, R.anim.filter_exit, R.anim.filter_enter, R.anim.filter_exit)
            .add(R.id.container, TaskFilterPage.forTasks())
            .addToBackStack(TaskFilterPage.RESULT)
            .commit()
    }

    /** 並び順・完了の見せ方・済んだタブ・配置などをまとめて選ぶ */
    private fun showViewMenu() {
        val ctx = requireContext()
        val rows = ViewMenu.rows(
            sort = sortMode(),
            done = doneMode(),
            doneTabs = DoneTabs.from(Prefs.doneTabs(ctx)),
            groupView = groupView(),
            groupsLast = Prefs.taskGroupsLast(ctx),
            hasTabs = tabs.visibility == View.VISIBLE,
            filtering = !placementFilter().isEmpty,
            placeFiltered = Prefs.placeFilter(ctx) != null
        )
        ViewMenuDialog.show(ctx, rows) { action ->
            when (action) {
                is ViewMenuAction.Sort -> Prefs.setTaskSort(ctx, action.sort.name)
                is ViewMenuAction.Done -> Prefs.setTaskDoneMode(ctx, action.mode.name)
                is ViewMenuAction.DoneTab -> {
                    Prefs.setDoneTabs(ctx, action.mode.name)
                    rebuildTabs()
                }
                is ViewMenuAction.GroupStyle -> {
                    Prefs.setGroupView(ctx, action.mode.name)
                    subTabKey = ""
                    // インデントに変えたら、タブで開いていた塊は「すべて」に戻す（中身が一覧の全体になる）
                    if (action.mode == GroupView.INDENT) Prefs.setTaskTab(ctx, GroupTabs.ALL)
                    rebuildTabs()
                }
                ViewMenuAction.TabOrder -> { showTabOrder(subRow = false); return@show }
                ViewMenuAction.PlaceFiltered -> { confirmPlaceFiltered(); return@show }
                ViewMenuAction.PlaceAll -> { confirmPlaceAll(); return@show }
                ViewMenuAction.GroupsLast ->
                    Prefs.setTaskGroupsLast(ctx, !Prefs.taskGroupsLast(ctx))
                ViewMenuAction.BulkPlace -> { bulkPlace(); return@show }
                is ViewMenuAction.PickDay -> { pickDay(action.daysAhead); return@show }
                ViewMenuAction.CollapseAll -> Prefs.setCollapsed(ctx, allParentIds())
                ViewMenuAction.ExpandAll -> Prefs.setCollapsed(ctx, emptySet())
            }
            updateSortLabel()
            render()
        }
    }

    private fun allParentIds(): Set<Long> =
        loaded.mapNotNull { it.parentId }.toSet()

    private fun toggleCollapse(item: HobbyItem) {
        val ctx = requireContext()
        val cur = Prefs.collapsed(ctx).toMutableSet()
        if (!cur.add(item.id)) cur.remove(item.id)
        Prefs.setCollapsed(ctx, cur)
        render()
    }

    // ---- 絞り込み ----

    private fun placeLabel(id: String): String {
        val ctx = requireContext()
        return dev.togar.dynasched.Places.taskChoices(dev.togar.dynasched.Places.all(ctx))
            .firstOrNull { it.first == id }?.second ?: id
    }

    private fun priorityName(p: Int): String =
        dev.togar.dynasched.Priorities.name(dev.togar.dynasched.Priorities.all(requireContext()), p)

    private fun filterSummary(f: TaskFilter): String =
        f.summary(::placeLabel, { id -> loaded.firstOrNull { it.id == id }?.name }, ::priorityName)
            .joinToString(" × ")

    /** 配置に回す条件。絞り込みに、いま開いているグループのタブも足す */
    private fun placementFilter(): TaskFilter = filter.copy(groupId = groupOfCurrentTab()?.id)

    // ---- 配置の対象 ----

    private fun renderPlaceFilter() {
        val pf = Prefs.placeFilter(requireContext())
        if (pf == null) {
            placeFilterText.visibility = View.GONE
            return
        }
        placeFilterText.visibility = View.VISIBLE
        placeFilterText.text = "📅 カレンダーに置くのはこの条件だけ: ${filterSummary(pf)}（押すと解除）"
    }

    private fun confirmPlaceFiltered() {
        val ctx = requireContext()
        val filter = placementFilter()
        if (filter.isEmpty) return
        AlertDialog.Builder(ctx)
            .setTitle("いまの絞り込みだけ配置する")
            .setMessage(
                "条件: ${filterSummary(filter)}\n\n" +
                    "この条件に当てはまるタスクだけをカレンダーに置きます。毎朝の自動配置も同じです。\n" +
                    "予定（教材）はこれまで通り置きます。"
            )
            .setPositiveButton("配置する") { _, _ ->
                Prefs.setPlaceFilter(ctx, filter)
                renderPlaceFilter()
                runPlacement()
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun confirmPlaceAll() {
        val ctx = requireContext()
        val pf = Prefs.placeFilter(ctx)
        if (pf == null) {
            Toast.makeText(ctx, "いまは全部のタスクを配置しています", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle("全部のタスクを配置する")
            .setMessage("いまは「${filterSummary(pf)}」だけを置いています。絞り込みをやめて組み直しますか？")
            .setPositiveButton("全部置く") { _, _ ->
                Prefs.setPlaceFilter(ctx, null)
                renderPlaceFilter()
                runPlacement()
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    /** 組み直す。結果は短く言うだけ（詳しくは設定の「予定の自動配置」） */
    private fun runPlacement() {
        val app = requireContext().applicationContext
        Toast.makeText(app, "組み直しています…", Toast.LENGTH_SHORT).show()
        Api.async({ Repo.current(app).runScheduler(app, Prefs.fillDays(app)) }, { r ->
            if (!isAdded) return@async
            Toast.makeText(requireContext(), "タスク${r.placedHobby}件・予定${r.placedStudy}件を置きました", Toast.LENGTH_LONG).show()
        }, { e ->
            if (isAdded) Toast.makeText(requireContext(), "組み直せませんでした: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show()
        })
    }

    // ---- 読み込みと描画 ----

    private fun load() {
        swipe.isRefreshing = true
        val ctx = requireContext().applicationContext
        Api.async(
            work = {
                val repo = Repo.current(ctx)
                repo.getHobby(ctx) to repo.statRows(ctx)
            },
            onSuccess = { (all, stats) ->
                if (!isAdded) return@async
                swipe.isRefreshing = false
                loaded = all
                statRowsCache = stats
                rebuildTabs()
                render()
                celebrateIfReached(stats)
            },
            onError = { e ->
                if (!isAdded) return@async
                swipe.isRefreshing = false
                empty.visibility = View.VISIBLE
                empty.text = "読み込みエラー: ${Api.friendlyMessage(e)}"
            }
        )
    }

    // ---- グループのタブ ----

    private fun groupView(): GroupView = GroupView.from(Prefs.groupView(requireContext()))

    /** いま選んでいるタブ。無くなっていたら「すべて」に戻す */
    private fun currentTab(): String {
        // インデントで見せる時は、タブで塊を選ぶ先が無いので常に「すべて」
        if (groupView() == GroupView.INDENT) return GroupTabs.ALL
        val key = Prefs.taskTab(requireContext())
        return if (GroupTabs.exists(GroupTabs.top(loaded), key)) key else GroupTabs.ALL
    }

    /** 下段で小グループを選んでいればそちら、無ければ上段 */
    private fun effectiveTab(): String {
        val top = currentTab()
        return if (subTabKey.isNotEmpty() && subTabKey != top) subTabKey else top
    }

    /** いま開いている塊。塊のタブでなければ null */
    private fun groupOfCurrentTab(): HobbyItem? {
        val id = GroupTabs.groupIdOf(effectiveTab()) ?: return null
        return loaded.firstOrNull { it.id == id }
    }

    private fun doneTabsMode(): DoneTabs = DoneTabs.from(Prefs.doneTabs(requireContext()))

    /**
     * タブを作り直す。グループは増減するので、読み込みのたびに組み直す。
     * 差し替え中の選択通知は無視する（作り直しただけで描画が走ると点滅する）。
     */
    private fun rebuildTabs() {
        val ctx = requireContext()
        if (groupView() == GroupView.INDENT) {
            tabs.visibility = View.GONE
            tabs.removeAllTabs()
            rebuildSubTabs()
            return
        }
        val selected = currentTab()
        val list = GroupTabs.arrange(
            GroupTabs.applyOrder(GroupTabs.top(loaded), Prefs.taskTabOrder(ctx)), doneTabsMode(), selected
        )
        if (list.size <= 1) {
            // グループが1つも無ければタブは要らない
            tabs.visibility = View.GONE
            tabs.removeAllTabs()
            rebuildSubTabs()
            return
        }
        fillTabs(tabs, list, selected) { showTabOrder(subRow = false) }
        tabs.visibility = View.VISIBLE
        rebuildSubTabs()
    }

    /** 塊の中に小グループがある時だけ、下段を出す */
    private fun rebuildSubTabs() {
        val ctx = requireContext()
        val list = GroupTabs.arrange(
            GroupTabs.applyOrder(GroupTabs.sub(loaded, currentTab()), Prefs.taskTabOrder(ctx)),
            doneTabsMode(), subTabKey
        )
        if (list.size <= 1) {
            subTabs.visibility = View.GONE
            subTabs.removeAllTabs()
            subTabKey = ""
            return
        }
        if (list.none { it.key == subTabKey }) subTabKey = ""
        // 空 = 塊ぜんぶ。先頭がそれに当たる
        fillTabs(subTabs, list, subTabKey.ifEmpty { list.first().key }) { showTabOrder(subRow = true) }
        subTabs.visibility = View.VISIBLE
    }

    /** 済んだ塊のタブは薄くする。長押しで並びを変える */
    private fun fillTabs(
        bar: com.google.android.material.tabs.TabLayout, list: List<TaskTab>, selected: String,
        onLongPress: () -> Unit
    ) {
        rebuildingTabs = true
        bar.removeAllTabs()
        for (t in list) {
            val tab = bar.newTab().setText(t.label)
            tab.tag = t.key
            bar.addTab(tab, t.key == selected)
            tab.view.alpha = if (t.done) 0.4f else 1f
            tab.view.setOnLongClickListener { onLongPress(); true }
        }
        rebuildingTabs = false
    }

    /**
     * タブの左右の並びを変える。上段と下段は別々に並べる。
     * 先頭の「すべて」「ぜんぶ」は動かさない（いつも同じ場所にある方が戻りやすい）。
     */
    private fun showTabOrder(subRow: Boolean) {
        val ctx = requireContext()
        val order = Prefs.taskTabOrder(ctx)
        val list = GroupTabs.applyOrder(
            if (subRow) GroupTabs.sub(loaded, currentTab()) else GroupTabs.top(loaded), order
        )
        val movable = list.drop(1)
        if (movable.size < 2) {
            Toast.makeText(ctx, "並べ替えるほどタブがありません", Toast.LENGTH_SHORT).show()
            return
        }
        TabOrderDialog.show(requireActivity(), if (subRow) "下段のタブの並び" else "タブの並び", movable) { keys ->
            Prefs.setTaskTabOrder(ctx, GroupTabs.saveOrder(Prefs.taskTabOrder(ctx), keys))
            if (subRow) rebuildSubTabs() else rebuildTabs()
        }
    }

    /** 読み直さずに並べ直す。並び順・折りたたみ・絞り込み・タブを変えたときはこちらだけ */
    private fun render() {
        if (!isAdded) return
        val ctx = requireContext()
        val levels = dev.togar.dynasched.Priorities.all(ctx)
        val visible = filter.apply(GroupTabs.apply(loaded, effectiveTab()), levels)
        renderPlaceFilter()
        val mode = doneMode()
        // グループを下に回すのは「すべて」だけ。塊のタブの中は中身そのものが一つのグループなので
        val rows = TaskList.build(visible, sortMode(), Prefs.collapsed(ctx), mode,
            groupsLast = Prefs.taskGroupsLast(ctx) && currentTab() == GroupTabs.ALL)
        adapter.places = dev.togar.dynasched.Places.all(ctx)
        adapter.levels = levels
        adapter.submit(rows)
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            // 全部終わっている時に「タスクはありません」だと消えたように見える
            mode == DoneMode.HIDDEN && visible.isNotEmpty() -> "残っているタスクはありません"
            !filter.isEmpty -> "この条件に当てはまるタスクはありません"
            currentTab() != GroupTabs.ALL -> "この中にタスクはありません"
            else -> "タスクはありません"
        }
    }

    // ---- つかんで動かす ----

    /** 一番浅く置いた時の親。塊のタブならその塊（下段で選んでいればそちら）、それ以外は最上位 */
    private fun dropRoot(): Long? = groupOfCurrentTab()?.id

    /** タブの上で離した時の入れ先。[view] は大きく見せて、離れたら戻すために持つ */
    private class TabHit(val key: String, val label: String, val view: View)

    /** 掴んでいる行がいま重なっているタブ（無ければ null）。離した時にそこへ入れる */
    private var hoverTab: TabHit? = null

    /** この掴みでタブへ入れるのを受け付けるか（タブで選ぶ見せ方で、タブが出ている時だけ） */
    private var dropToTabs = false

    /**
     * 指の位置（画面の座標）。**行の真ん中ではなく指で当てる。**
     * 行の端を掴むと、指はタブの上なのに行の真ん中はまだ一覧の中、ということが起きる
     */
    private var fingerX = 0f
    private var fingerY = 0f
    /** 掴んでから指が動いたか。動く前の座標は前の掴みの残りなので使わない */
    private var fingerKnown = false

    /** つかんだ時の段。横のずれと足して落とし先の段を出す */
    private var dragBaseLevel = 0
    /** いま指が示している段 */
    private var dragLevel = 0

    /**
     * 1段変えるのに必要な横のずれ。
     *
     * 表示のインデント(24dp)より広く取ってある。縦にドラッグする指は自然に横へも
     * 振れるので、インデント幅と同じにすると触っただけで段が変わってしまう。
     * 40dpなら、半分の20dpまでの振れは段を変えない。
     */
    private fun stepPx(): Float = 40 * resources.displayMetrics.density

    /**
     * 長押しでそのまま掴んで動かす。モードに入る手順は挟まない。
     *
     * 縦の位置だけでは「すぐ上の行の子になりたいのか、隣に並びたいのか」が決まらないので、
     * **横のずれで段を指定する**（指を右に送ると1段深くなる）。掴んだ板が指に付いてくるので、
     * 右にずらしている実感がそのまま階層の指定になる。
     */
    private inner class DragCallback : ItemTouchHelper.Callback() {

        override fun isLongPressDragEnabled(): Boolean = true
        override fun isItemViewSwipeEnabled(): Boolean = false

        override fun getMovementFlags(rv: RecyclerView, holder: RecyclerView.ViewHolder): Int {
            // どのタブでも動かせる。塊のタブでは塊の外へは出さない（dropRoot）
            // **LEFT/RIGHT を入れないと横のずれが取れない。**
            // ItemTouchHelper は許可されていない向きの移動量を 0 に丸めるので、
            // 上下だけを許可していると onChildDraw に来る dX が常に 0 になり、
            // 右にずらしても段が変わらない（v27がこれだった）。
            // 行はどれも同じ幅で左右の位置も同じなので、横向きの入れ替え相手は
            // 見つからず、誤って横に並び替わることはない。
            val drag = ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
            return makeMovementFlags(drag, 0)
        }

        override fun onSelectedChanged(holder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(holder, actionState)
            if (actionState != ItemTouchHelper.ACTION_STATE_DRAG || holder == null) return
            val row = adapter.rows().getOrNull(holder.bindingAdapterPosition) ?: return
            dragBaseLevel = row.level
            dragLevel = row.level
            hoverTab = null
            fingerKnown = false
            dropToTabs = groupView() == GroupView.TABS &&
                (tabs.visibility == View.VISIBLE || subTabs.visibility == View.VISIBLE)
            // 上のタブまで行を描けるように、掴んでいる間だけ切り抜きを止める
            if (dropToTabs) setDragClipping(false)
            // 掴んだ行はスクロールで結び直されることがあるので、アダプタ側にも覚えさせる
            adapter.draggingId = row.item.id
            showButtons(holder.itemView, false)
            swipe.isEnabled = false   // 下に引く操作がドラッグと喧嘩する
            showDragHint(row.level)
            // 「持ち上がった」ことを手で分かるようにする
            holder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            lift(holder.itemView, true)
        }

        override fun onChildDraw(
            c: android.graphics.Canvas, rv: RecyclerView, holder: RecyclerView.ViewHolder,
            dX: Float, dY: Float, actionState: Int, isActive: Boolean
        ) {
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && isActive) {
                // 指がタブの上に来たら「そのグループへ入れる」。その間は段の指定は見ない
                val hit = if (dropToTabs && fingerKnown) tabAt(fingerX, fingerY) else null
                if (hit?.key != hoverTab?.key) setHover(hit)
                if (hit == null) {
                    val pos = holder.bindingAdapterPosition
                    val above = adapter.rows().getOrNull(pos - 1)
                    val max = if (above == null) 0 else above.level + 1
                    val want = dragBaseLevel + Math.round(dX / stepPx())
                    val next = want.coerceIn(0, max)
                    if (next != dragLevel) {
                        dragLevel = next
                        showDragHint(next)
                    }
                }
            }
            super.onChildDraw(c, rv, holder, dX, dY, actionState, isActive)
        }

        override fun onMove(
            rv: RecyclerView, holder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
        ): Boolean {
            adapter.moveVisually(holder.bindingAdapterPosition, target.bindingAdapterPosition)
            return true
        }

        /** 横に払う操作は使わない（横のずれは段の指定に使っている） */
        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
            val dropIndex = holder.bindingAdapterPosition
            val movedId = adapter.draggingId
            val into = hoverTab
            into?.view?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(100)?.start()
            hoverTab = null
            if (dropToTabs) setDragClipping(true)
            dropToTabs = false
            lift(holder.itemView, false)
            holder.itemView.translationX = 0f
            showButtons(holder.itemView, true)
            super.clearView(rv, holder)
            adapter.draggingId = null
            swipe.isEnabled = true
            dragHintText.visibility = View.GONE
            // タブの上で離したら、その塊へ入れる。並びの入れ替えはしない
            if (into != null && movedId != null) moveIntoTab(movedId, into.key, into.label)
            else applyDrop(dropIndex, dragLevel)
        }

        /**
         * 画面の端まで持っていった時のスクロール速度。
         *
         * 既定の実装は**2秒かけて最高速まで上げる**ので、遠くへ運ぶほど待たされる。
         * ここでは時間による加速をほぼ無くし、**はみ出した量だけで速度を決める**。
         * 端に少し掛けたらゆっくり、深く押し込めば一気に流れる、という形にする。
         */
        override fun interpolateOutOfBoundsScroll(
            rv: RecyclerView, viewSize: Int, viewSizeOutOfBounds: Int,
            totalSize: Int, msSinceStartScroll: Long
        ): Int {
            // タブの上にいる間は流さない。流すと、入れ先を選んでいる間に一覧が勝手に上まで走る
            if (hoverTab != null) return 0
            val direction = if (viewSizeOutOfBounds > 0) 1 else -1
            val depth = (Math.abs(viewSizeOutOfBounds).toFloat() / viewSize).coerceIn(0f, 1f)
            val d = resources.displayMetrics.density
            // 押し込み具合で 6dp〜48dp / フレーム。指を止めても勝手には加速しない
            val perFrame = (6f + 42f * depth) * d
            // 動き出しだけごく短く鈍らせる（端に触れた瞬間に飛ばないように）
            val warmUp = (0.5f + msSinceStartScroll / 200f).coerceAtMost(1f)
            return (direction * perFrame * warmUp).toInt().let {
                if (it == 0) direction else it
            }
        }
    }

    /** 掴んでいる間はボタンを隠す。指が当たって削除されるのを防ぐ */
    private fun showButtons(view: View, show: Boolean) {
        val v = if (show) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.addChildButton)?.visibility = v
        view.findViewById<View>(R.id.deleteButton)?.visibility = v
    }

    /** 掴んだ板を浮かせる／戻す */
    private fun lift(view: View, up: Boolean) {
        val d = resources.displayMetrics.density
        view.animate().cancel()
        view.animate()
            .scaleX(if (up) 1.03f else 1f)
            .scaleY(if (up) 1.03f else 1f)
            .setDuration(120)
            .start()
        view.elevation = if (up) 12 * d else 0f
        view.alpha = if (up) 0.97f else 1f
    }

    private fun showDragHint(level: Int) {
        dragHintText.visibility = View.VISIBLE
        dragHintText.text = when (level) {
            0 -> "最上位に置きます"
            else -> "${level}段目（上のタスクの子）"
        }
    }

    /** 入れ先のタブが変わった。前のタブは元の大きさに戻し、新しいタブを大きくして手にも返す */
    private fun setHover(hit: TabHit?) {
        hoverTab?.view?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(100)?.start()
        hoverTab = hit
        if (hit == null) {
            showDragHint(dragLevel)
            return
        }
        hit.view.animate().scaleX(1.15f).scaleY(1.15f).setDuration(100).start()
        hit.view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        dragHintText.visibility = View.VISIBLE
        dragHintText.text = if (hit.key == GroupTabs.ALL) "離すと、最上位に出します"
            else "離すと「${hit.label}」の中に入ります"
    }

    /** 指の下にあるタブ。上段と下段の両方を見る */
    private fun tabAt(x: Float, y: Float): TabHit? {
        for (bar in listOf(tabs, subTabs)) {
            if (bar.visibility != View.VISIBLE || !contains(bar, x, y)) continue
            for (i in 0 until bar.tabCount) {
                val tab = bar.getTabAt(i) ?: continue
                val key = tab.tag as? String ?: continue
                if (contains(tab.view, x, y)) return TabHit(key, tab.text?.toString().orEmpty(), tab.view)
            }
        }
        return null
    }

    private fun contains(v: View, x: Float, y: Float): Boolean {
        val l = IntArray(2)
        v.getLocationOnScreen(l)
        return x >= l[0] && x < l[0] + v.width && y >= l[1] && y < l[1] + v.height
    }

    /**
     * 掴んだ行を一覧の外（上のタブ）まで描けるように、一覧から画面の一番外までの切り抜きを止める／戻す。
     * **掴んでいる間だけ。**止めたままにすると、スクロールで上に半分出た行がタブの上に描かれる
     */
    private fun setDragClipping(off: Boolean) {
        val top = view ?: return
        var v: View? = recycler
        while (v != null) {
            (v as? ViewGroup)?.let { it.clipChildren = !off; it.invalidate() }
            if (v === top) break
            v = v.parent as? View
        }
    }

    /**
     * 行をタブのグループの下へ動かす（最上位なら top へ）。末尾に付け、並びはそのまま保つ。
     * 自分の中の塊へは入れられない（[GroupTabs.canMoveInto]）。
     */
    private fun moveIntoTab(itemId: Long, key: String, label: String) {
        val ctx = requireContext().applicationContext
        val moved = loaded.firstOrNull { it.id == itemId } ?: return
        val target = GroupTabs.groupIdOf(key)   // 「すべて」は null（最上位）
        if (target == moved.parentId) { render(); return }
        if (!GroupTabs.canMoveInto(loaded, itemId, target)) {
            Toast.makeText(requireContext(), "そこには入れられません（自分の中へは動かせません）", Toast.LENGTH_SHORT).show()
            render()
            return
        }
        val siblings = loaded.filter { it.parentId == target && it.id != itemId }
            .sortedWith(TaskList.comparator(TaskSort.MANUAL, false))
            .map { it.id } + itemId
        Api.async(
            work = {
                val repo = Repo.current(ctx)
                repo.setHobbyParent(ctx, itemId, target)
                repo.reorderHobby(ctx, siblings)
                repo.getHobby(ctx)
            },
            onSuccess = { all ->
                if (!isAdded) return@async
                loaded = all
                rebuildTabs()
                render()
                val where = if (target == null) "最上位" else "「$label」"
                Toast.makeText(requireContext(), "「${moved.name}」を$where に入れました", Toast.LENGTH_SHORT).show()
            },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "動かせませんでした: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show()
                load()
            }
        )
    }

    /**
     * 落とした場所から親と並びを決めて保存する。
     *
     * 並び順が手動でも優先度でもない時は、そのままでは並びが保存されても
     * 次の描画で並び直されて**元に戻ったように見える**。指で動かした以上それは意図なので、
     * 手動の並びへ切り替えて動かした形を残す。優先度順のときだけは
     * 「落とした先の優先度に合わせる」という別の意味を持たせてある。
     */
    private fun applyDrop(dropIndex: Int, level: Int) {
        val rows = adapter.rows()
        val moved = rows.getOrNull(dropIndex)?.item ?: return
        val drop = TaskList.dropTarget(rows, loaded, dropIndex, level, dropRoot())
        if (drop == null) {
            Toast.makeText(requireContext(), "そこには置けません", Toast.LENGTH_SHORT).show()
            render(); return
        }
        val ctx = requireContext().applicationContext
        val parentChanged = drop.parentId != moved.parentId
        val sort = sortMode()
        val newPriority = if (sort == TaskSort.PRIORITY)
            TaskList.priorityAfterMove(rows, moved.id, dropIndex) else null

        // グループを下に回している時は、グループを単独タスクの上に置いても下に戻る。黙って戻ると壊れたように見える
        if (Prefs.taskGroupsLast(requireContext()) && currentTab() == GroupTabs.ALL) {
            val groups = loaded.mapNotNullTo(HashSet()) { it.parentId }
            if (drop.siblingIds.map { it in groups }.zipWithNext().any { (a, b) -> a && !b }) {
                Toast.makeText(requireContext(), "グループは下にまとまります（「並び順」で解除できます）",
                    Toast.LENGTH_SHORT).show()
            }
        }

        if (sort != TaskSort.MANUAL && sort != TaskSort.PRIORITY) {
            Prefs.setTaskSort(requireContext(), TaskSort.MANUAL.name)
            updateSortLabel()
            Toast.makeText(requireContext(), "手動の並びに切り替えました", Toast.LENGTH_SHORT).show()
        }

        Api.async(
            work = {
                val repo = Repo.current(ctx)
                if (parentChanged) repo.setHobbyParent(ctx, moved.id, drop.parentId)
                repo.reorderHobby(ctx, drop.siblingIds)
                if (newPriority != null && newPriority != moved.priority) {
                    repo.setHobbyPriority(ctx, moved.id, newPriority)
                }
                repo.getHobby(ctx)
            },
            onSuccess = { all ->
                if (!isAdded) return@async
                loaded = all
                render()
                if (newPriority != null && newPriority != moved.priority) {
                    val label = priorityName(newPriority)
                    Toast.makeText(requireContext(), "優先度を$label にしました", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "動かせませんでした: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show()
                load()
            }
        )
    }

    private fun toggle(item: HobbyItem, checked: Boolean) {
        val ctx = requireContext().applicationContext
        // 付けた手応えを指に返す
        if (checked) view?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        Api.async(
            work = { Repo.current(ctx).setHobbyCompleted(ctx, item.id, checked) },
            onSuccess = { awards ->
                dev.togar.dynasched.widget.SuggestWidgetProvider.updateAll(ctx)
                if (!isAdded) return@async
                showPoints(awards)
                load()
            },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "更新に失敗: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show()
                load()
            }
        )
    }

    /**
     * 入ったオヤスギを一瞬だけ浮かせて見せる。動きを切っていればトーストで済ませる。
     * まとまり（親）を片付けた時は手にも返す。
     */
    private fun showPoints(awards: List<Award>) {
        val text = Oyasugi.popText(awards)
        if (text.isEmpty()) return
        if (!Prefs.animations(requireContext())) {
            Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
            return
        }
        if (awards.any { it.isGroup }) view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        pointPop.animate().cancel()
        pointPop.text = text
        pointPop.alpha = 1f
        pointPop.translationY = 0f
        pointPop.visibility = View.VISIBLE
        pointPop.animate()
            .translationY(-40 * resources.displayMetrics.density)
            .alpha(0f)
            .setDuration(900)
            .withEndAction { pointPop.visibility = View.GONE }
            .start()
    }

    /** 今日の目標に届いたら1日1回だけ知らせる */
    private fun celebrateIfReached(stats: List<StatRow>) {
        val ctx = requireContext()
        val today = java.time.LocalDate.now()
        val p = Stats.today(stats, today, ProgressPanel.goalOf(ctx, stats, today))
        if (!p.reached) return
        if (Prefs.goalCelebratedOn(ctx) == today.toString()) return
        Prefs.setGoalCelebratedOn(ctx, today.toString())
        view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        Toast.makeText(ctx, "今日の目標 ${p.goal}件に届きました", Toast.LENGTH_SHORT).show()
    }

    // ---- 今日・明日やるもの ----

    /**
     * その日にやるタスクをまとめて選ぶ（[daysAhead] 0 が今日、1 が明日）。何時にやるかは決めない（配置に任せる）。
     * いま見えている範囲（グループのタブと絞り込み）の中から選ぶ。「#英語」で絞ってから今日の分を選ぶ、のように。
     */
    private fun pickDay(daysAhead: Int) {
        val ctx = requireContext()
        val date = java.time.LocalDate.now().plusDays(daysAhead.toLong()).toString()
        val dayName = if (daysAhead == 0) "今日" else "明日"
        val parents = loaded.mapNotNullTo(HashSet()) { it.parentId }
        val levels = dev.togar.dynasched.Priorities.all(ctx)
        val items = filter.apply(GroupTabs.apply(loaded, effectiveTab()), levels)
            .filter { it.id !in parents && !it.isCompleted }
            .sortedByDescending { it.priority }
        if (items.isEmpty()) {
            Toast.makeText(ctx, "選べるタスクがありません", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = items.map { t ->
            val parent = dev.togar.dynasched.data.NowTasks.parentPath(loaded, t.id)
            val other = if (t.wantDate.isNotEmpty() && t.wantDate != date)
                "（📌 ${dev.togar.dynasched.data.Want.dayLabel(t.wantDate)}）" else ""
            (if (parent.isNotEmpty()) "$parent › " else "") + t.name + other
        }
        val picked = items.filter { it.wantDate == date }.mapTo(HashSet()) { it.id }
        val before = picked.toSet()
        val narrowed = !filter.isEmpty || currentTab() != GroupTabs.ALL
        AlertDialog.Builder(ctx)
            .setTitle(if (narrowed) "${dayName}やるもの（いま見えている中から）" else "${dayName}やるもの")
            .setMultiChoiceItems(labels.toTypedArray(), BooleanArray(items.size) { items[it].id in picked }) { _, i, on ->
                if (on) picked.add(items[i].id) else picked.remove(items[i].id)
            }
            .setPositiveButton("次へ") { _, _ ->
                val removed = before - picked
                if (picked.isEmpty()) { saveDay(date, emptySet(), removed, 0); return@setPositiveButton }
                AlertDialog.Builder(ctx)
                    .setTitle("${picked.size}件をどのくらいやる？")
                    .setItems(arrayOf(
                        "絶対（枠に入らなければ、空いている時間に入れる）",
                        "なるべく（${dayName}の枠に先に入れる。入らなければ次の日へ）"
                    )) { _, i ->
                        saveDay(date, picked, removed,
                            if (i == 0) dev.togar.dynasched.data.Want.MUST else dev.togar.dynasched.data.Want.PREFER)
                    }
                    .setNegativeButton("やめる", null)
                    .show()
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    /** 選んだ物をその日に決め、外した物は決めないに戻す。すぐ組み直してカレンダーに出す */
    private fun saveDay(date: String, picked: Set<Long>, removed: Set<Long>, level: Int) {
        val app = requireContext().applicationContext
        Api.async({
            val repo = Repo.current(app)
            repo.setHobbyWant(app, removed, "", 0)
            repo.setHobbyWant(app, picked, date, level)
        }, {
            if (!isAdded) return@async
            load()
            runPlacement()
        }, { e ->
            if (isAdded) Toast.makeText(requireContext(), "決められませんでした: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show()
        })
    }

    // ---- 場所をまとめて変える ----

    /**
     * 場所ごとにタスクを選んで、別の場所へまとめて移す。
     * 1件ずつ編集を開いて直すのは、数十件あると現実的に続かない。
     * 場所を1つだけで絞っていれば、その場所から始める。
     */
    private fun bulkPlace() {
        val ctx = requireContext()
        val choices = dev.togar.dynasched.Places.taskChoices(dev.togar.dynasched.Places.all(ctx))
        filter.places.singleOrNull()?.let {
            pickTasksAt(it, choices)
            return
        }
        val counts = choices.map { (id, _) -> movableAt(id).size }
        AlertDialog.Builder(ctx)
            .setTitle("どの場所のタスクを移しますか")
            .setItems(choices.mapIndexed { i, c -> "${c.second}（${counts[i]}件）" }.toTypedArray()) { _, i ->
                pickTasksAt(choices[i].first, choices)
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    /** 移せるタスク。済んでいない葉だけ（親の場所は配置に使わない） */
    private fun movableAt(place: String): List<HobbyItem> {
        val parents = loaded.mapNotNullTo(HashSet()) { it.parentId }
        return loaded.filter {
            !it.isCompleted && it.id !in parents &&
                it.location.ifEmpty { dev.togar.dynasched.Places.ANYWHERE } == place
        }
    }

    private fun pickTasksAt(
        from: String, choices: List<Pair<String, String>>, preset: Set<Long> = emptySet()
    ) {
        val ctx = requireContext()
        val items = movableAt(from)
        val fromLabel = choices.firstOrNull { it.first == from }?.second ?: from
        if (items.isEmpty()) {
            Toast.makeText(ctx, "「$fromLabel」のタスクはありません", Toast.LENGTH_SHORT).show()
            return
        }
        // 同じ名前の子が別の親の下にあることがあるので、親の名前も添える
        val byId = loaded.associateBy { it.id }
        val labels = items.map { t ->
            val parent = t.parentId?.let { byId[it]?.name }
            if (parent != null) "$parent › ${t.name}" else t.name
        }
        val picked = preset.toMutableSet()
        AlertDialog.Builder(ctx)
            .setTitle("「$fromLabel」から移すもの")
            .setMultiChoiceItems(labels.toTypedArray(), BooleanArray(items.size) { items[it].id in picked }) { _, i, on ->
                if (on) picked.add(items[i].id) else picked.remove(items[i].id)
            }
            .setPositiveButton("移す先へ") { _, _ ->
                if (picked.isEmpty()) {
                    Toast.makeText(ctx, "何も選ばれていません", Toast.LENGTH_SHORT).show()
                } else pickDestination(from, picked, choices)
            }
            .setNeutralButton("全部選ぶ") { _, _ -> pickTasksAt(from, choices, items.mapTo(HashSet()) { it.id }) }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun pickDestination(from: String, ids: Set<Long>, choices: List<Pair<String, String>>) {
        val ctx = requireContext()
        val targets = choices.filter { it.first != from }
        AlertDialog.Builder(ctx)
            .setTitle("${ids.size}件をどこへ移しますか")
            .setItems(targets.map { it.second }.toTypedArray()) { _, i ->
                val (to, label) = targets[i]
                val app = ctx.applicationContext
                Api.async({ Repo.current(app).setHobbiesLocation(app, ids, to) }, {
                    if (!isAdded) return@async
                    Toast.makeText(requireContext(), "${ids.size}件を「$label」にしました", Toast.LENGTH_SHORT).show()
                    load()
                }, { e ->
                    if (isAdded) Toast.makeText(requireContext(), "移せませんでした: ${Api.friendlyMessage(e)}",
                        Toast.LENGTH_LONG).show()
                })
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    /** 大きいタスクを子タスクに分ける。子は親の場所・優先度・色・タグを引き継ぐ */
    private fun splitTask(item: HobbyItem) {
        val ctx = requireContext()
        SplitDialog.show(ctx, item) { parts ->
            val app = ctx.applicationContext
            Api.async({
                val repo = Repo.current(app)
                for (p in parts) {
                    repo.addHobby(app, p.name, item.id, p.minutes, item.priority, item.location, "",
                        item.color, item.tags)
                }
            }, {
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "${parts.size}件に分けました", Toast.LENGTH_SHORT).show()
                load()
            }, { e ->
                if (isAdded) Toast.makeText(requireContext(), "分けられませんでした: ${Api.friendlyMessage(e)}",
                    Toast.LENGTH_LONG).show()
            })
        }
    }

    /** 単発タスクをタップしたときの編集ダイアログ（名前・必要時間・場所・優先度・色・メモ） */
    private fun openEdit(item: HobbyItem) {
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun label(t: String) = android.widget.TextView(ctx).apply {
            text = t; textSize = 12f; setPadding(0, pad / 2, 0, 0)
        }

        val nameInput = android.widget.EditText(ctx).apply { setText(item.name); hint = "タスク名" }
        root.addView(nameInput)

        root.addView(label("必要時間"))
        val durationPicker = DurationPickerView(ctx, item.durationMinutes)
        root.addView(durationPicker)

        root.addView(label("場所"))
        val locChoices = dev.togar.dynasched.Places.taskChoices(dev.togar.dynasched.Places.all(ctx))
        val locValues = locChoices.map { it.first }
        val locSpinner = android.widget.Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                locChoices.map { it.second })
            setSelection(locValues.indexOf(item.location).let { if (it >= 0) it else 0 })
        }
        root.addView(locSpinner)

        root.addView(label("優先度"))
        val prioSlider = PriorityPicker(ctx, item.priority)
        root.addView(prioSlider)

        // やる日。葉だけ（グループは配下ごとに決める。親に付けても配置されるのは葉なので）
        val isLeaf = loaded.none { it.parentId == item.id }
        val want = WantPickerView(ctx, item.wantDate, item.wantLevel)
        val dueButton = DueDateButton(ctx, item.dueDate)
        if (isLeaf) {
            root.addView(label("やる日（何時かは配置に任せる）"))
            root.addView(want)
            root.addView(label("締切（それまでに終わらせる日）"))
            root.addView(dueButton)
        }

        root.addView(label("カレンダーの色"))
        val colorPalette = ColorPaletteView(ctx, dev.togar.dynasched.api.CalColor.indexOfId(item.color))
        root.addView(colorPalette)

        root.addView(label("タグ"))
        val tagInput = TagInputView(ctx, item.tags) { Tags.known(loaded) }
        root.addView(tagInput)

        root.addView(label("メモ"))
        val noteInput = android.widget.EditText(ctx).apply {
            setText(item.note)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        root.addView(noteInput)

        // 子を持つタスク＝グループ。ここで決めた設定を配下へ配れるようにする。
        // 1件ずつ開いて直すのは10件もあれば続かないし、
        // グループ内で場所や優先度がばらばらだと配置も意図しない形になる。
        val hasChildren = loaded.any { it.parentId == item.id }
        val spread = HashMap<String, android.widget.CheckBox>()
        if (hasChildren) {
            root.addView(label("配下のタスクにも適用する"))
            for ((key, text) in listOf(
                "priority" to "優先度", "location" to "場所",
                "color" to "色", "tags" to "タグ"
            )) {
                val cb = android.widget.CheckBox(ctx).apply { this.text = text; textSize = 13f }
                spread[key] = cb
                root.addView(cb)
            }
        }

        // 習慣から作られたタスクは、ここで直しても次の回には効かない。繰り返しの元へ行けるようにする
        if (item.routineId > 0) {
            root.addView(android.widget.Button(ctx).apply {
                text = "繰り返し（習慣）の方を直す"
                setOnClickListener { openRoutine(item.routineId) }
            })
        }

        val scroll = android.widget.ScrollView(ctx).apply { addView(root) }

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(if (hasChildren) "グループを編集" else "タスクを編集")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(ctx, "タスク名を入力してください", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                var total = durationPicker.totalMinutes
                if (total <= 0) total = 30
                val location = locValues[locSpinner.selectedItemPosition]
                val priority = prioSlider.value
                val color = dev.togar.dynasched.api.CalColor.idAt(colorPalette.selectedIndex)
                val note = noteInput.text.toString().trim()
                val appCtx = requireContext().applicationContext
                val tags = tagInput.value
                val spreadCount = spread.count { it.value.isChecked }
                val wantChanged = isLeaf && (want.date != item.wantDate || want.level != item.wantLevel)
                val wantDate = want.date
                val wantLevel = want.level
                val dueDate = dueButton.date
                val dueChanged = isLeaf && dueDate != item.dueDate
                // 配下へ配る値。チェックの状態は画面の側で読んでおく（ワーカーから画面に触らない）
                val spreadPriority = if (spread["priority"]?.isChecked == true) priority else null
                val spreadLocation = if (spread["location"]?.isChecked == true) location else null
                val spreadColor = if (spread["color"]?.isChecked == true) color else null
                val spreadTags = if (spread["tags"]?.isChecked == true) tags else null
                // 配下の塊（自分も含む）。前回の追加設定もこの値に揃える
                val groupIds = TaskList.subtreeIds(loaded, item.id).filter { id -> loaded.any { it.parentId == id } }
                Api.async(
                    work = {
                        val repo = Repo.current(appCtx)
                        repo.editHobby(
                            appCtx, item.id, name, total, priority, location, note, color, tags
                        )
                        if (wantChanged) repo.setHobbyWant(appCtx, listOf(item.id), wantDate, wantLevel)
                        if (dueChanged) repo.setHobbyDue(appCtx, item.id, dueDate)
                        if (spreadCount > 0) {
                            repo.applyToSubtree(
                                appCtx, item.id,
                                priority = spreadPriority, location = spreadLocation,
                                color = spreadColor, tags = spreadTags
                            )
                            dev.togar.dynasched.data.AddDefaults.overrideFor(
                                appCtx, groupIds, spreadLocation, spreadPriority, spreadColor, spreadTags
                            )
                        }
                    },
                    onSuccess = {
                        if (!isAdded) return@async
                        Toast.makeText(
                            requireContext(),
                            if (spreadCount > 0) "更新し、配下にも適用しました" else "更新しました",
                            Toast.LENGTH_SHORT
                        ).show()
                        load()
                        // やる日を変えたらカレンダーにすぐ出す（毎朝の組み直しを待つと今日の分に間に合わない）
                        if (wantChanged) runPlacement()
                    },
                    onError = { e ->
                        if (!isAdded) return@async
                        Toast.makeText(requireContext(), "更新に失敗: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show()
                    }
                )
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun openRoutine(id: Long) {
        val ctx = requireContext()
        val app = ctx.applicationContext
        Api.async({ Repo.current(app).routines(app).firstOrNull { it.id == id } }, { r ->
            if (!isAdded) return@async
            if (r == null) {
                Toast.makeText(ctx, "元の習慣はもう消されています", Toast.LENGTH_SHORT).show()
            } else RoutineEditor.edit(ctx, r) { if (isAdded) load() }
        }, { })
    }

    private fun openAddChild(parent: HobbyItem) {
        val intent = Intent(requireContext(), AddTaskActivity::class.java)
        intent.putExtra(AddTaskActivity.EXTRA_PARENT_ID, parent.id)
        intent.putExtra(AddTaskActivity.EXTRA_PARENT_NAME, parent.name)
        startActivity(intent)
    }

    private fun confirmDelete(item: HobbyItem) {
        val msg = if (item.hasChildren)
            "「${item.name}」を削除しますか？（配下の子タスクもすべて削除されます）"
        else
            "「${item.name}」を削除しますか？"
        AlertDialog.Builder(requireContext())
            .setMessage(msg)
            .setPositiveButton("削除") { _, _ -> doDelete(item) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun doDelete(item: HobbyItem) {
        val ctx = requireContext().applicationContext
        Api.async(
            work = { Repo.current(ctx).deleteHobby(ctx, item.id) },
            onSuccess = { if (isAdded) load() },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "削除に失敗: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show()
            }
        )
    }
}
