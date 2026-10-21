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
    private lateinit var searchInput: android.widget.EditText
    private lateinit var chips: com.google.android.material.chip.ChipGroup
    private lateinit var placeFilterText: TextView
    private lateinit var nowCard: View
    private lateinit var nowLabel: TextView
    private lateinit var nowTitle: TextView
    private lateinit var statsText: TextView
    private lateinit var pointPop: TextView
    private lateinit var touchHelper: ItemTouchHelper

    /** 絞り込み。変えるたびに覚える（[Prefs.taskFilter]） */
    private var filter = TaskFilter()

    /** 「いまやること」の候補と、いま出している番号。「ほかの」で次へ送る */
    private var nowCandidates: List<dev.togar.dynasched.api.SuggestItem> = emptyList()
    private var nowIndex = 0

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
        searchInput = root.findViewById(R.id.searchInput)
        chips = root.findViewById(R.id.filterChips)
        placeFilterText = root.findViewById(R.id.placeFilterText)
        nowCard = root.findViewById(R.id.nowCard)
        nowLabel = root.findViewById(R.id.nowLabel)
        nowTitle = root.findViewById(R.id.nowTitle)
        statsText = root.findViewById(R.id.statsText)
        pointPop = root.findViewById(R.id.pointPop)
        val recycler = root.findViewById<RecyclerView>(R.id.recycler)
        val addButton = root.findViewById<Button>(R.id.addTaskButton)

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
            filter.groupId?.let { id -> loaded.firstOrNull { it.id == id } }?.let { g ->
                intent.putExtra(AddTaskActivity.EXTRA_PARENT_ID, g.id)
                intent.putExtra(AddTaskActivity.EXTRA_PARENT_NAME, g.name)
            }
            // 場所を1つだけ選んでいれば、最初からその場所にしておく
            filter.places.singleOrNull()?.let { intent.putExtra(AddTaskActivity.EXTRA_LOCATION, it) }
            if (filter.habit == TaskFilter.Habit.ONLY) intent.putExtra(AddTaskActivity.EXTRA_REPEAT, true)
            startActivity(intent)
        }
        sortButton.setOnClickListener { showViewMenu() }

        filter = Prefs.taskFilter(requireContext())
        searchInput.setText(filter.text)
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(e: android.text.Editable?) {
                val t = e?.toString() ?: ""
                if (t != filter.text) setFilter(filter.copy(text = t), rebuildChips = false)
            }
        })
        root.findViewById<Button>(R.id.nowDo).setOnClickListener { doNow() }
        root.findViewById<Button>(R.id.nowNext).setOnClickListener {
            if (nowCandidates.isNotEmpty()) { nowIndex = (nowIndex + 1) % nowCandidates.size; renderNow() }
        }
        nowLabel.setOnClickListener { pickNowPlace() }
        placeFilterText.setOnClickListener { confirmPlaceAll() }

        swipe.setOnRefreshListener { syncThenLoad() }
        updateSortLabel()
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

    private fun updateSortLabel() {
        // 完了を隠している間はそれを見せる。気付かないまま「終わったものが消えた」と
        // 思わないように（実際には隠れているだけ）。絞り込みはチップの方に見えている
        val hidden = if (doneMode() == DoneMode.HIDDEN) " 済×" else ""
        sortButton.text = shortLabel(sortMode()) + hidden
    }

    private fun shortLabel(s: TaskSort) = when (s) {
        TaskSort.MANUAL -> "手動"
        TaskSort.NEWEST -> "新しい順"
        TaskSort.OLDEST -> "古い順"
        TaskSort.PRIORITY -> "優先度順"
        TaskSort.LONGEST -> "長い順"
        TaskSort.SHORTEST -> "短い順"
    }

    /** 並び順と「完了の見せ方」、タグの絞り込みをまとめて選ぶ */
    private fun showViewMenu() {
        val ctx = requireContext()
        val rows = ViewMenu.rows(
            sort = sortMode(),
            done = doneMode(),
            statsLabel = if (Prefs.taskShowStats(ctx)) statsSpan(ctx).label else "出さない",
            groupsLast = Prefs.taskGroupsLast(ctx),
            showNow = Prefs.taskShowNow(ctx),
            filtering = !filter.isEmpty,
            placeFiltered = Prefs.placeFilter(ctx) != null
        )
        ViewMenuDialog.show(ctx, rows) { action ->
            when (action) {
                is ViewMenuAction.Sort -> Prefs.setTaskSort(ctx, action.sort.name)
                is ViewMenuAction.Done -> Prefs.setTaskDoneMode(ctx, action.mode.name)
                ViewMenuAction.ShowNow -> {
                    Prefs.setTaskShowNow(ctx, !Prefs.taskShowNow(ctx))
                    refreshNow()
                }
                ViewMenuAction.PlaceFiltered -> { confirmPlaceFiltered(); return@show }
                ViewMenuAction.PlaceAll -> { confirmPlaceAll(); return@show }
                ViewMenuAction.GroupsLast ->
                    Prefs.setTaskGroupsLast(ctx, !Prefs.taskGroupsLast(ctx))
                ViewMenuAction.StatsSpan -> { showStatsSpan(); return@show }
                ViewMenuAction.BulkPlace -> { bulkPlace(); return@show }
                ViewMenuAction.CollapseAll -> Prefs.setCollapsed(ctx, allParentIds())
                ViewMenuAction.ExpandAll -> Prefs.setCollapsed(ctx, emptySet())
            }
            updateSortLabel()
            render()
        }
    }

    /** 数える期間を選ぶ。出さないことも選べる */
    private fun showStatsSpan() {
        val ctx = requireContext()
        val spans = Stats.Span.entries
        val labels = spans.map { it.label }.toTypedArray() + "出さない"
        AlertDialog.Builder(ctx)
            .setTitle("済んだ数・増えた数")
            .setItems(labels) { _, i ->
                if (i < spans.size) {
                    Prefs.setTaskStatsSpan(ctx, spans[i].name)
                    Prefs.setTaskShowStats(ctx, true)
                } else {
                    Prefs.setTaskShowStats(ctx, false)
                }
                updateSortLabel(); render()
            }
            .setNegativeButton("閉じる", null)
            .show()
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

    /** 絞り込みを変えて覚える。検索欄から来た時はチップを作り直さない（打つたびに点滅する） */
    private fun setFilter(next: TaskFilter, rebuildChips: Boolean = true) {
        filter = next
        Prefs.setTaskFilter(requireContext(), next)
        if (rebuildChips) rebuildChips()
        render()
        refreshNow()
    }

    private fun placeLabel(id: String): String {
        val ctx = requireContext()
        return dev.togar.dynasched.Places.taskChoices(dev.togar.dynasched.Places.all(ctx))
            .firstOrNull { it.first == id }?.second ?: id
    }

    private fun filterSummary(f: TaskFilter): String =
        f.summary(::placeLabel) { id -> loaded.firstOrNull { it.id == id }?.name }.joinToString(" × ")

    /**
     * 条件チップ。どれも押すと選ぶ画面が開く。効いているものは色を付けて中身を見せる。
     * **効いている条件が見えていないと「タスクが消えた」と思う**ので、短くても必ず出す。
     */
    private fun rebuildChips() {
        if (!isAdded) return
        val f = filter
        chips.removeAllViews()
        chips.addView(chip(if (f.habit == TaskFilter.Habit.ALL) "習慣" else f.habit.label,
            f.habit != TaskFilter.Habit.ALL) { pickHabit() })
        chips.addView(chip(if (f.places.isEmpty()) "場所" else f.places.joinToString("・") { placeLabel(it) },
            f.places.isNotEmpty()) { pickPlaces() })
        chips.addView(chip(
            if (f.priorities.isEmpty()) "優先度"
            else "優先度 " + f.priorities.sortedDescending().joinToString("・") { TaskFilter.priorityLabel(it) },
            f.priorities.isNotEmpty()) { pickPriorities() })
        chips.addView(chip(if (f.tags.isEmpty()) "タグ" else f.tags.sorted().joinToString(" ") { "#$it" },
            f.tags.isNotEmpty()) { pickTags() })
        val groupName = f.groupId?.let { id -> loaded.firstOrNull { it.id == id }?.name }
        chips.addView(chip(groupName ?: "グループ", groupName != null) { pickGroup() })
        if (!f.isEmpty) {
            chips.addView(chip("✕ 条件を消す", false) {
                filter = TaskFilter()
                searchInput.setText("")
                setFilter(TaskFilter())
            })
        }
    }

    private fun chip(label: String, active: Boolean, onClick: () -> Unit) =
        com.google.android.material.chip.Chip(requireContext()).apply {
            val ctx = requireContext()
            text = label
            isCheckable = false
            chipStrokeWidth = 0f
            chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(ctx, if (active) R.color.primary else R.color.surface_variant)
            )
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.white))
            setOnClickListener { onClick() }
        }

    private fun pickHabit() {
        val ctx = requireContext()
        val modes = TaskFilter.Habit.entries
        val labels = modes.map { if (it == filter.habit) "● ${it.label}" else "　${it.label}" } + "　習慣の繰り返しを直す…"
        AlertDialog.Builder(ctx)
            .setTitle("習慣")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i < modes.size) setFilter(filter.copy(habit = modes[i]))
                else RoutineEditor.showList(ctx) { if (isAdded) load() }
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    /** 複数から選ぶ。何も選ばなければ条件なし */
    private fun <T> pickMany(
        title: String, values: List<T>, labels: List<String>, selected: Set<T>, onPick: (Set<T>) -> Unit
    ) {
        val picked = selected.toMutableSet()
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMultiChoiceItems(labels.toTypedArray(), BooleanArray(values.size) { values[it] in picked }) { _, i, on ->
                if (on) picked.add(values[i]) else picked.remove(values[i])
            }
            .setPositiveButton("絞り込む") { _, _ -> onPick(picked) }
            .setNeutralButton("条件なし") { _, _ -> onPick(emptySet()) }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun pickPlaces() {
        val choices = dev.togar.dynasched.Places.taskChoices(dev.togar.dynasched.Places.all(requireContext()))
        pickMany("場所", choices.map { it.first }, choices.map { it.second }, filter.places) {
            setFilter(filter.copy(places = it))
        }
    }

    private fun pickPriorities() {
        val values = TaskFilter.PRIORITIES
        pickMany("優先度", values, values.map { TaskFilter.priorityLabel(it) }, filter.priorities) {
            setFilter(filter.copy(priorities = it))
        }
    }

    private fun pickTags() {
        val known = Tags.known(loaded)
        if (known.isEmpty()) {
            Toast.makeText(requireContext(), "まだタグがありません。タスクを編集して付けてください", Toast.LENGTH_LONG).show()
            return
        }
        // 消えたタグで絞ったままだと何も出なくなるので、選べる物に今のものを足しておく
        val values = (known + filter.tags.filter { it !in known })
        pickMany("タグ", values, values.map { "#$it" }, filter.tags) {
            setFilter(filter.copy(tags = it))
        }
    }

    /** 子を持つタスク（グループ）の中だけを見る。入れ子は「親 › 子」と書いて区別する */
    private fun pickGroup() {
        val ctx = requireContext()
        val parents = loaded.mapNotNullTo(HashSet()) { it.parentId }
        val byId = loaded.associateBy { it.id }
        fun path(i: HobbyItem): String {
            val names = ArrayList<String>()
            var cur: HobbyItem? = i
            val seen = HashSet<Long>()
            while (cur != null && seen.add(cur.id)) { names.add(cur.name); cur = cur.parentId?.let { byId[it] } }
            return names.asReversed().joinToString(" › ")
        }
        val groups = loaded.filter { it.id in parents }.map { it.id to path(it) }.sortedBy { it.second }
        if (groups.isEmpty()) {
            Toast.makeText(ctx, "グループがありません（子タスクを持つタスクがグループになります）", Toast.LENGTH_LONG).show()
            return
        }
        val labels = listOf(if (filter.groupId == null) "● すべて" else "　すべて") +
            groups.map { (id, name) -> if (id == filter.groupId) "● $name" else "　$name" }
        AlertDialog.Builder(ctx)
            .setTitle("グループ")
            .setItems(labels.toTypedArray()) { _, i ->
                setFilter(filter.copy(groupId = if (i == 0) null else groups[i - 1].first))
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    // ---- いまやること ----

    private var nowLoc = ""
    private var nowMinutes = 30

    /**
     * いまの場所・時間でできる候補を探して、1つだけ出す。
     * 絞り込んでいればその中から（「寮のみ × #英語」で絞れば、その中で今やるもの）
     */
    private fun refreshNow() {
        if (!isAdded) return
        val ctx = requireContext()
        if (!Prefs.taskShowNow(ctx)) {
            nowCard.visibility = View.GONE
            return
        }
        val app = ctx.applicationContext
        val f = filter
        Api.async({ dev.togar.dynasched.data.NowTasks.pick(app, f) }, { now ->
            if (!isAdded) return@async
            val next = now.items
            // 同じ候補が先頭のままなら、「ほかの」で送った位置を保つ
            val keep = nowCandidates.getOrNull(nowIndex)?.let { cur -> next.indexOfFirst { it.id == cur.id && it.kind == cur.kind } } ?: -1
            nowCandidates = next
            nowIndex = if (keep >= 0) keep else 0
            nowLoc = now.place
            nowMinutes = now.minutes
            renderNow()
        }, { if (isAdded) nowCard.visibility = View.GONE })
    }

    private fun renderNow() {
        val ctx = requireContext()
        val place = dev.togar.dynasched.Places.name(dev.togar.dynasched.Places.all(ctx), nowLoc)
        nowCard.visibility = View.VISIBLE
        nowLabel.text = "いまやること ・ $place・${nowMinutes}分 ▾"
        val item = nowCandidates.getOrNull(nowIndex)
        val doButton = nowCard.findViewById<Button>(R.id.nowDo)
        val nextButton = nowCard.findViewById<Button>(R.id.nowNext)
        if (item == null) {
            nowTitle.text = if (filter.isEmpty) "いまできるものはありません。休みましょう"
                else "この条件で、いまできるものはありません"
            doButton.visibility = View.GONE
            nextButton.visibility = View.GONE
            return
        }
        nowTitle.text = "${if (item.kind == "material") "🎯 " else ""}${item.title}（${item.minutes}分）"
        doButton.visibility = View.VISIBLE
        nextButton.visibility = if (nowCandidates.size > 1) View.VISIBLE else View.GONE
        nextButton.text = "ほかの ${nowIndex + 1}/${nowCandidates.size}"
    }

    /** 決めたら、終わっているはずの時刻に確認を出す（「暇なとき」と同じ） */
    private fun doNow() {
        val item = nowCandidates.getOrNull(nowIndex) ?: return
        FreeTimeDialog.decide(requireActivity(), item, nowMinutes)
    }

    /** いまいる場所を変える。ウィジェット・「暇なとき」と同じ設定を使う */
    private fun pickNowPlace() {
        val ctx = requireContext()
        val places = dev.togar.dynasched.Places.all(ctx)
        val cur = places.indexOfFirst { it.id == Prefs.widgetLoc(ctx) }
        AlertDialog.Builder(ctx)
            .setTitle("いまどこにいますか")
            .setSingleChoiceItems(places.map { it.name }.toTypedArray(), cur) { d, i ->
                d.dismiss()
                Prefs.setWidgetLoc(ctx, places[i].id)
                refreshNow()
            }
            .setNegativeButton("やめる", null)
            .show()
    }

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
                rebuildChips()
                render()
                refreshNow()
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

    /** 片付いた数・増えた数。数えるのは [Stats]、ここは出すだけ */
    private fun renderStats(ctx: android.content.Context) {
        if (!Prefs.taskShowStats(ctx)) {
            statsText.visibility = View.GONE
            return
        }
        val span = statsSpan(ctx)
        val from = Stats.from(java.time.LocalDate.now(), span)
        val s = Stats.count(statRowsCache, from)
        statsText.text = "${span.label}: ${s.line()}"
        statsText.visibility = View.VISIBLE
    }

    private fun statsSpan(ctx: android.content.Context): Stats.Span =
        Stats.Span.entries.firstOrNull { it.name == Prefs.taskStatsSpan(ctx) } ?: Stats.Span.WEEK

    /** 読み直さずに並べ直す。並び順・折りたたみ・絞り込み・タブを変えたときはこちらだけ */
    private fun render() {
        if (!isAdded) return
        val ctx = requireContext()
        val visible = filter.apply(loaded)
        renderStats(ctx)
        renderPlaceFilter()
        val mode = doneMode()
        // グループを下に回すのは塊で絞っていない時だけ。塊の中は中身そのものが一つのグループなので
        val rows = TaskList.build(visible, sortMode(), Prefs.collapsed(ctx), mode,
            groupsLast = Prefs.taskGroupsLast(ctx) && filter.groupId == null)
        adapter.places = dev.togar.dynasched.Places.all(ctx)
        adapter.submit(rows)
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            // 全部終わっている時に「タスクはありません」だと消えたように見える
            mode == DoneMode.HIDDEN && visible.isNotEmpty() -> "残っているタスクはありません"
            !filter.isEmpty -> "この条件に当てはまるタスクはありません"
            else -> "タスクはありません"
        }
    }

    // ---- つかんで動かす ----

    /**
     * 一番浅く置いた時の親。塊で絞っていればその塊、それ以外は最上位。
     */
    private fun dropRoot(): Long? = filter.groupId?.takeIf { id -> loaded.any { it.id == id } }

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
            lift(holder.itemView, false)
            holder.itemView.translationX = 0f
            showButtons(holder.itemView, true)
            super.clearView(rv, holder)
            adapter.draggingId = null
            swipe.isEnabled = true
            dragHintText.visibility = View.GONE
            applyDrop(dropIndex, dragLevel)
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
        if (Prefs.taskGroupsLast(requireContext()) && filter.groupId == null) {
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
                    val label = when {
                        newPriority >= 8 -> "高"; newPriority <= 3 -> "低"; else -> "中"
                    }
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
        val prioValues = listOf(3, 5, 8)
        val prioNames = arrayOf("低", "中", "高")
        val prioSlider = LabeledSlider(ctx, prioValues, item.priority) { v ->
            prioNames[prioValues.indexOf(v).coerceIn(0, 2)]
        }
        root.addView(prioSlider)

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
                Api.async(
                    work = {
                        val repo = Repo.current(ctx)
                        repo.editHobby(
                            appCtx, item.id, name, total, priority, location, note, color, tags
                        )
                        if (spreadCount > 0) {
                            repo.applyToSubtree(
                                appCtx, item.id,
                                priority = if (spread["priority"]?.isChecked == true) priority else null,
                                location = if (spread["location"]?.isChecked == true) location else null,
                                color = if (spread["color"]?.isChecked == true) color else null,
                                tags = if (spread["tags"]?.isChecked == true) tags else null
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
