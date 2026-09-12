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
    private lateinit var tabs: com.google.android.material.tabs.TabLayout
    private lateinit var touchHelper: ItemTouchHelper

    /** タブを差し替えている最中か。差し替え中の選択通知で二重描画しないため */
    private var rebuildingTabs = false

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
        tabs = root.findViewById(R.id.taskTabs)
        val recycler = root.findViewById<RecyclerView>(R.id.recycler)
        val addButton = root.findViewById<Button>(R.id.addTaskButton)

        adapter = HobbyAdapter(
            onToggle = { item, checked -> toggle(item, checked) },
            onAddChild = { item -> openAddChild(item) },
            onDelete = { item -> confirmDelete(item) },
            onEdit = { item -> openEdit(item) },
            onCollapse = { item -> toggleCollapse(item) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        touchHelper = ItemTouchHelper(DragCallback())
        touchHelper.attachToRecyclerView(recycler)

        addButton.setOnClickListener {
            startActivity(Intent(requireContext(), AddTaskActivity::class.java))
        }
        sortButton.setOnClickListener { showViewMenu() }

        tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                if (rebuildingTabs) return
                Prefs.setTaskTab(requireContext(), tab.tag as? String ?: TaskTabs.ALL)
                render()
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
        })

        // 引き下ろしはNotionから取り直す合図にもする。
        // 開いている間にNotion側で書いたものを、閉じずに拾えるように
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
        val filter = Prefs.tagFilter(requireContext())
        // 完了を隠している間はそれを見せる。気付かないまま「終わったものが消えた」と
        // 思わないように（実際には隠れているだけ）
        val hidden = if (doneMode() == DoneMode.HIDDEN) " 済×" else ""
        sortButton.text = if (filter.isEmpty()) shortLabel(sortMode()) + hidden
        // 絞り込み中はそれを最初に見せる。気付かないまま「タスクが消えた」と思うのを防ぐ
        else "#${filter.first()}${if (filter.size > 1) "+${filter.size - 1}" else ""}$hidden"
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
            tabSource = TabSource.from(Prefs.taskTabSource(ctx)),
            tagFilterCount = Prefs.tagFilter(ctx).size
        )
        ViewMenuDialog.show(ctx, rows) { action ->
            when (action) {
                is ViewMenuAction.Sort -> Prefs.setTaskSort(ctx, action.sort.name)
                is ViewMenuAction.Done -> Prefs.setTaskDoneMode(ctx, action.mode.name)
                ViewMenuAction.TagFilter -> { showTagFilter(); return@show }
                ViewMenuAction.TabSource -> { showTabSource(); return@show }
                ViewMenuAction.CollapseAll -> Prefs.setCollapsed(ctx, allParentIds())
                ViewMenuAction.ExpandAll -> Prefs.setCollapsed(ctx, emptySet())
            }
            updateSortLabel()
            render()
        }
    }

    /** タグで一覧を絞る。当てはまる枝だけを残し、木の形は保つ */
    private fun showTagFilter() {
        val ctx = requireContext()
        val known = Tags.known(loaded)
        if (known.isEmpty()) {
            Toast.makeText(ctx, "まだタグがありません。タスクを編集して付けてください", Toast.LENGTH_LONG).show()
            return
        }
        val selected = Prefs.tagFilter(ctx).toMutableSet()
        val checked = BooleanArray(known.size) { selected.contains(known[it]) }
        AlertDialog.Builder(ctx)
            .setTitle("タグで絞り込む")
            .setMultiChoiceItems(known.toTypedArray(), checked) { _, which, isChecked ->
                if (isChecked) selected.add(known[which]) else selected.remove(known[which])
            }
            .setPositiveButton("絞り込む") { _, _ ->
                Prefs.setTagFilter(ctx, selected)
                updateSortLabel(); render()
            }
            .setNeutralButton("絞り込みをやめる") { _, _ ->
                Prefs.setTagFilter(ctx, emptySet())
                updateSortLabel(); render()
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    /** タブに何を並べるかを選ぶ */
    private fun showTabSource() {
        val ctx = requireContext()
        val sources = TabSource.entries
        val cur = TabSource.from(Prefs.taskTabSource(ctx))
        val labels = sources.map { if (it == cur) "● ${it.label}" else "　${it.label}" }
        AlertDialog.Builder(ctx)
            .setTitle("横に並べるタブ")
            .setItems(labels.toTypedArray()) { _, i ->
                Prefs.setTaskTabSource(ctx, sources[i].name)
                Prefs.setTaskTab(ctx, TaskTabs.ALL)   // 中身が変わるので先頭へ戻す
                rebuildTabs(); render()
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

    // ---- 読み込みと描画 ----

    private fun load() {
        swipe.isRefreshing = true
        val ctx = requireContext().applicationContext
        Api.async(
            work = { Repo.current(ctx).getHobby(ctx) },
            onSuccess = { all ->
                if (!isAdded) return@async
                swipe.isRefreshing = false
                loaded = all
                rebuildTabs()
                render()
            },
            onError = { e ->
                if (!isAdded) return@async
                swipe.isRefreshing = false
                empty.visibility = View.VISIBLE
                empty.text = "読み込みエラー: ${Api.friendlyMessage(e)}"
            }
        )
    }

    /** いま選んでいるタブ。無くなっていたら「すべて」に戻す */
    private fun currentTab(): String {
        val ctx = requireContext()
        val key = Prefs.taskTab(ctx)
        val list = TaskTabs.tabs(loaded, TabSource.from(Prefs.taskTabSource(ctx)))
        return if (TaskTabs.exists(list, key)) key else TaskTabs.ALL
    }

    /**
     * タブを作り直す。中身（グループ／タグ）は増減するので、読み込みのたびに組み直す。
     * 差し替え中の選択通知は無視する（作り直しただけで描画が走ると点滅する）。
     */
    private fun rebuildTabs() {
        val ctx = requireContext()
        val source = TabSource.from(Prefs.taskTabSource(ctx))
        val list = TaskTabs.tabs(loaded, source)
        if (list.isEmpty()) {
            tabs.visibility = View.GONE
            tabs.removeAllTabs()
            return
        }
        val selected = currentTab()
        rebuildingTabs = true
        tabs.removeAllTabs()
        for (t in list) {
            val tab = tabs.newTab().setText(t.label)
            tab.tag = t.key
            tabs.addTab(tab, t.key == selected)
        }
        rebuildingTabs = false
        tabs.visibility = View.VISIBLE
    }

    /** 読み直さずに並べ直す。並び順・折りたたみ・絞り込み・タブを変えたときはこちらだけ */
    private fun render() {
        if (!isAdded) return
        val ctx = requireContext()
        val filter = Prefs.tagFilter(ctx)
        val tabKey = currentTab()
        val inTab = TaskTabs.apply(loaded, tabKey)
        val visible = Tags.filterTree(inTab, filter)
        val mode = doneMode()
        val rows = TaskList.build(visible, sortMode(), Prefs.collapsed(ctx), mode)
        adapter.submit(rows)
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            filter.isNotEmpty() -> "「#${filter.joinToString(" #")}」に当てはまるタスクはありません"
            // 全部終わっている時に「タスクはありません」だと消えたように見える
            mode == DoneMode.HIDDEN && visible.isNotEmpty() -> "残っているタスクはありません"
            tabKey != TaskTabs.ALL -> "この中にタスクはありません"
            else -> "タスクはありません"
        }
    }

    // ---- つかんで動かす ----

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
            // タブで塊を選んでいる間は全体が見えていない。そこで階層をいじると、
            // いま見えていない場所へタスクが飛んで「消えた」ように見える。
            // 並び替えも階層変更も「すべて」タブに限る。
            if (currentTab() != TaskTabs.ALL) return 0
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
        val drop = TaskList.dropTarget(rows, loaded, dropIndex, level)
        if (drop == null) {
            Toast.makeText(requireContext(), "そこには置けません", Toast.LENGTH_SHORT).show()
            render(); return
        }
        val ctx = requireContext().applicationContext
        val parentChanged = drop.parentId != moved.parentId
        val sort = sortMode()
        val newPriority = if (sort == TaskSort.PRIORITY)
            TaskList.priorityAfterMove(rows, moved.id, dropIndex) else null

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
        Api.async(
            work = { Repo.current(ctx).setHobbyCompleted(ctx, item.id, checked) },
            onSuccess = { if (isAdded) load() },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "更新に失敗: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show()
                load()
            }
        )
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
        val locValues = arrayOf("anywhere", "home", "out")
        val locSpinner = android.widget.Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("どこでも", "家のみ", "外のみ"))
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
