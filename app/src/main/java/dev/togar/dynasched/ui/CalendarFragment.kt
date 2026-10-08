package dev.togar.dynasched.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import dev.togar.dynasched.Places
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.R
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.api.HobbyItem
import dev.togar.dynasched.api.ScheduledEvent
import dev.togar.dynasched.calendar.CalendarRepo
import dev.togar.dynasched.calendar.Slot
import dev.togar.dynasched.data.PointsSummary
import dev.togar.dynasched.data.Repo
import dev.togar.dynasched.widget.SuggestWidgetProvider
import java.time.LocalDate

/**
 * カレンダー画面（下のタブの一番左）。**縦が時間、横が日付**の時間割に、
 * 枠（作業できる時間）・カレンダーの予定・置いたタスクを重ねて見せる。
 *
 * - 空いている所を長押しして引く → 枠を作る。押すと場所や「毎週」を決められる
 * - 置いたタスクを長押しして引く → 動かす（手で動かした物は組み直しで戻さない）
 * - 下のタスク置き場から長押しで運ぶ → その時刻に置く
 * - 「自動で置く」→ 枠の中に、まだ置いていないタスクと教材を詰める
 *
 * 横向きは3日、縦に積む向きは7日ぶんを出す。
 */
class CalendarFragment : Fragment() {

    private lateinit var grid: TimeGridView
    private lateinit var rangeText: TextView
    private lateinit var progressLine: TextView
    private lateinit var modeButton: TextView
    private lateinit var tray: LinearLayout
    private lateinit var trayEmpty: TextView

    /** 表示の先頭の日 */
    private var anchor: LocalDate = LocalDate.now()
    private var slots: List<Slot> = emptyList()
    private var events: List<ScheduledEvent> = emptyList()
    private var busy: List<dev.togar.dynasched.calendar.BusyBlock> = emptyList()
    /** 置けるタスク（未完了の葉） */
    private var leaves: List<HobbyItem> = emptyList()

    private val span: Int get() = if (Prefs.calendarHorizontal(requireContext())) 3 else 7

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_calendar, container, false)
        grid = root.findViewById(R.id.timeGrid)
        rangeText = root.findViewById(R.id.rangeText)
        progressLine = root.findViewById(R.id.progressLine)
        modeButton = root.findViewById(R.id.modeButton)
        tray = root.findViewById(R.id.taskTray)
        trayEmpty = root.findViewById(R.id.trayEmptyText)

        // 起きている時間帯だけ出す。早起き・夜更かしの設定でも端が切れないよう幅を持たせる
        val ctx = requireContext()
        grid.startHour = (Prefs.wakeMinutes(ctx) / 60).coerceIn(0, 12)
        grid.endHour = ((Prefs.bedtimeMinutes(ctx) + 59) / 60).coerceIn(13, 24)

        root.findViewById<View>(R.id.prevButton).setOnClickListener { shift(-span) }
        root.findViewById<View>(R.id.nextButton).setOnClickListener { shift(span) }
        root.findViewById<View>(R.id.todayButton).setOnClickListener {
            anchor = LocalDate.now()
            load()
        }
        modeButton.setOnClickListener {
            Prefs.setCalendarHorizontal(ctx, !Prefs.calendarHorizontal(ctx))
            load()
        }
        progressLine.setOnClickListener { showProgress() }
        root.findViewById<View>(R.id.autoPlaceButton).setOnClickListener { autoPlace() }
        root.findViewById<View>(R.id.freeTimeButton).setOnClickListener {
            FreeTimeDialog.show(requireActivity())
        }

        grid.onCreateSlot = { day, start, end -> createSlot(day, start, end) }
        grid.onChangeBlock = { block, day, start, end -> changeBlock(block, day, start, end) }
        grid.onTapBlock = { tapBlock(it) }
        grid.onTapEmpty = { day, min -> placeSomething(day, min) }
        grid.onDropTask = { taskId, day, min -> dropTask(taskId, day, min) }
        return root
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun shift(days: Int) {
        anchor = anchor.plusDays(days.toLong())
        load()
    }

    private fun dates(): List<LocalDate> = (0 until span).map { anchor.plusDays(it.toLong()) }

    // ---- 読み込み ----

    private class Loaded(
        val slots: List<Slot>,
        val events: List<ScheduledEvent>,
        val busy: List<dev.togar.dynasched.calendar.BusyBlock>,
        val stats: List<StatRow>,
        val points: PointsSummary,
        val tasks: List<HobbyItem>
    )

    private fun load() {
        val ctx = requireContext().applicationContext
        val horizontal = Prefs.calendarHorizontal(requireContext())
        val from = anchor
        val days = span
        modeButton.text = if (horizontal) "縦に" else "横に"
        Api.async(
            work = {
                val repo = Repo.current(ctx)
                // カレンダーが読めなくても、アプリの枠と置いた物は出す
                val snap = runCatching { CalendarRepo.read(ctx, days, Prefs.calendarId(ctx)) }.getOrNull()
                Loaded(
                    slots = repo.slots(ctx),
                    events = repo.getSchedule(ctx, from.toString()),
                    busy = snap?.busy.orEmpty(),
                    stats = repo.statRows(ctx),
                    points = repo.points(ctx),
                    tasks = repo.getHobby(ctx)
                )
            },
            onSuccess = { data ->
                if (!isAdded) return@async
                slots = data.slots
                events = data.events
                busy = data.busy
                val parents = data.tasks.mapNotNullTo(HashSet()) { it.parentId }
                leaves = data.tasks.filter { !it.isCompleted && it.id !in parents }
                renderTray()
                // 今日の分は通知とウィジェットが見るので控えておく
                val today = LocalDate.now().toString()
                Prefs.saveScheduleCache(ctx, today,
                    ScheduledEvent.toJsonArray(events.filter { it.isOnDate(today) }))
                progressLine.text = ProgressPanel.oneLine(requireContext(), data.stats, data.points)
                render(horizontal)
            },
            onError = { e ->
                if (isAdded) Toast.makeText(requireContext(), Api.friendlyMessage(e), Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun render(horizontal: Boolean) {
        val list = dates()
        val places = Places.all(requireContext())
        grid.horizontal = horizontal
        grid.days = list
        rangeText.text = "${label(list.first())} 〜 ${label(list.last())}"

        val blocks = ArrayList<TimeGridView.Block>()
        for ((i, date) in list.withIndex()) {
            val ds = date.toString()
            val wd = date.dayOfWeek.value
            for (s in slots) {
                if (!((s.isWeekly && s.weekday == wd) || s.date == ds) || s.minutes() <= 0) continue
                blocks.add(TimeGridView.Block(
                    id = s.id,
                    kind = if (s.isBlock) TimeGridView.Kind.BUSY else TimeGridView.Kind.SLOT,
                    dayIndex = i, startMin = s.startMin, endMin = s.endMin,
                    label = if (s.isBlock) "休み"
                        else Places.name(places, s.location) + if (s.isWeekly) "（毎週）" else "",
                    location = s.location
                ))
            }
        }
        // カレンダーの予定。id は負の数にして、アプリの枠と見分ける
        for ((n, b) in busy.withIndex()) {
            val day = list.indexOfFirst { b.start.startsWith(it.toString()) }
            if (day < 0) continue
            blocks.add(TimeGridView.Block(
                id = -1L - n, kind = TimeGridView.Kind.BUSY, dayIndex = day,
                startMin = minuteOf(b.start), endMin = endMinuteOf(b.start, b.end),
                label = b.title.ifBlank { "予定" }
            ))
        }
        for (e in events) {
            val day = list.indexOfFirst { e.startDatetime.startsWith(it.toString()) }
            if (day < 0) continue
            blocks.add(TimeGridView.Block(
                id = e.id, kind = TimeGridView.Kind.TASK, dayIndex = day,
                startMin = minuteOf(e.startDatetime), endMin = endMinuteOf(e.startDatetime, e.endDatetime),
                label = e.title, isStudy = e.eventType == "study", isManual = e.isManual
            ))
        }
        grid.blocks = blocks
    }

    private fun label(date: LocalDate): String =
        "${date.monthValue}/${date.dayOfMonth}(${WEEKDAYS[date.dayOfWeek.value - 1]})"

    /** `yyyy-MM-ddTHH:mm:ss` の時刻部分を分に */
    private fun minuteOf(at: String): Int {
        val t = at.substringAfter("T", "")
        if (t.length < 5) return 0
        return (t.substring(0, 2).toIntOrNull() ?: 0) * 60 + (t.substring(3, 5).toIntOrNull() ?: 0)
    }

    /** 終わりの分。日をまたぐ物はその日の終わり（24時）で切る */
    private fun endMinuteOf(start: String, end: String): Int =
        if (end.take(10) > start.take(10)) 1440 else minuteOf(end)

    private fun at(date: LocalDate, min: Int): String =
        if (min >= 1440) "${date.plusDays(1)}T00:00:00" else "${date}T${Slot.hhmm(min)}:00"

    // ---- 枠 ----

    private fun createSlot(dayIndex: Int, start: Int, end: Int) {
        val date = dates().getOrNull(dayIndex) ?: return
        val ctx = requireContext().applicationContext
        Api.async({
            Repo.current(ctx).addSlot(ctx, Slot(date = date.toString(), startMin = start, endMin = end,
                location = Places.HOME))
        }, {
            if (!isAdded) return@async
            Toast.makeText(requireContext(), "枠を作りました（押すと場所や毎週の設定）", Toast.LENGTH_SHORT).show()
            load()
        }, {})
    }

    private fun changeBlock(block: TimeGridView.Block, dayIndex: Int, start: Int, end: Int) {
        val date = dates().getOrNull(dayIndex) ?: return
        val ctx = requireContext().applicationContext
        if (block.kind == TimeGridView.Kind.TASK) {
            Api.async({ Repo.current(ctx).moveEvent(ctx, block.id, at(date, start), at(date, end)) },
                { if (isAdded) load() }, {})
            return
        }
        val slot = slots.firstOrNull { it.id == block.id } ?: return
        // 毎週の枠は曜日ごと動く。その日だけの枠は日付ごと動く
        val moved = if (slot.isWeekly) slot.copy(weekday = date.dayOfWeek.value, startMin = start, endMin = end)
            else slot.copy(date = date.toString(), startMin = start, endMin = end)
        Api.async({ Repo.current(ctx).updateSlot(ctx, moved) }, { if (isAdded) load() }, {})
    }

    private fun tapBlock(block: TimeGridView.Block) {
        when {
            block.kind == TimeGridView.Kind.TASK -> taskMenu(block)
            block.id < 0 -> calendarEventMenu(block)
            else -> slotMenu(block)
        }
    }

    private fun taskMenu(block: TimeGridView.Block) {
        val items = arrayOf(
            "片付けた",
            "この予定を外す",
            if (block.isManual) "自動配置に任せる（固定をやめる）" else "この場所に固定する"
        )
        AlertDialog.Builder(requireContext())
            .setTitle(block.label + if (block.isManual) "（固定）" else "")
            .setItems(items) { _, which ->
                val ctx = requireContext().applicationContext
                Api.async({
                    val repo = Repo.current(ctx)
                    when (which) {
                        0 -> repo.completeTask(ctx, block.id)
                        1 -> repo.unplaceEvent(ctx, block.id)
                        else -> repo.setEventManual(ctx, block.id, !block.isManual)
                    }
                }, {
                    if (!isAdded) return@async
                    if (which == 0) {
                        Toast.makeText(requireContext(), "片付けました", Toast.LENGTH_SHORT).show()
                        SuggestWidgetProvider.updateAll(ctx)
                    }
                    load()
                }, { e ->
                    if (isAdded) Toast.makeText(requireContext(), Api.friendlyMessage(e), Toast.LENGTH_SHORT).show()
                })
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    private fun slotMenu(block: TimeGridView.Block) {
        val slot = slots.firstOrNull { it.id == block.id } ?: return
        val places = Places.all(requireContext())
        val items = arrayOf(
            "場所を変える（いまは${Places.name(places, slot.location)}）",
            if (slot.isWeekly) "この曜日の毎週をやめる" else "毎週にする",
            "消す"
        )
        AlertDialog.Builder(requireContext())
            .setTitle(slot.label(places))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickLocation(slot)
                    1 -> toggleWeekly(slot, block.dayIndex)
                    2 -> saveSlot(null, slot.id)
                }
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    /** 枠の場所。設定で足した場所も選べる */
    private fun pickLocation(slot: Slot) {
        val places = Places.all(requireContext())
        AlertDialog.Builder(requireContext())
            .setTitle("この枠でできること")
            .setItems(places.map { it.name }.toTypedArray()) { _, i ->
                saveSlot(slot.copy(location = places[i].id), null)
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    /** その日だけ ⇔ 毎週（その曜日） */
    private fun toggleWeekly(slot: Slot, dayIndex: Int) {
        val date = dates().getOrNull(dayIndex) ?: return
        saveSlot(
            if (slot.isWeekly) slot.copy(weekday = 0, date = date.toString())
            else slot.copy(weekday = date.dayOfWeek.value, date = ""),
            null
        )
    }

    private fun saveSlot(slot: Slot?, deleteId: Long?) {
        val ctx = requireContext().applicationContext
        Api.async({
            val repo = Repo.current(ctx)
            slot?.let { repo.updateSlot(ctx, it) }
            deleteId?.let { repo.deleteSlot(ctx, it) }
        }, { if (isAdded) load() }, {})
    }

    /** カレンダーの予定。ここでは直せないので、その時間を休みにする道だけ出す */
    private fun calendarEventMenu(block: TimeGridView.Block) {
        val date = dates().getOrNull(block.dayIndex) ?: return
        val now = if (Prefs.avoidBusy(requireContext())) "いまは、この時間に自動で置かない設定です。"
            else "いまは、この時間にも自動で置く設定です。"
        AlertDialog.Builder(requireContext())
            .setTitle(block.label)
            .setMessage("Googleカレンダー側の予定です。ここでは直せません。$now")
            .setPositiveButton("この時間を休みにする") { _, _ ->
                val ctx = requireContext().applicationContext
                Api.async({
                    Repo.current(ctx).addSlot(ctx, Slot(date = date.toString(), startMin = block.startMin,
                        endMin = block.endMin, isBlock = true))
                }, { if (isAdded) load() }, {})
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    // ---- タスクを置く ----

    /** 空いている所を軽く押した時。置くタスクを選ばせる */
    private fun placeSomething(dayIndex: Int, min: Int) {
        val date = dates().getOrNull(dayIndex) ?: return
        if (leaves.isEmpty()) {
            Toast.makeText(requireContext(), "置けるタスクがありません", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("${label(date)} ${Slot.hhmm(min)} に置く")
            .setItems(leaves.map { "${it.name}（${it.durationMinutes}分）" }.toTypedArray()) { _, i ->
                place(leaves[i], date, min)
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun dropTask(taskId: Long, dayIndex: Int, min: Int) {
        val date = dates().getOrNull(dayIndex) ?: return
        val item = leaves.firstOrNull { it.id == taskId } ?: return
        place(item, date, min)
    }

    private fun place(item: HobbyItem, date: LocalDate, min: Int) {
        val ctx = requireContext().applicationContext
        Api.async({
            Repo.current(ctx).placeTask(ctx, item.id, at(date, min), at(date, min + maxOf(15, item.durationMinutes)))
        }, { if (isAdded) load() }, { e ->
            if (isAdded) Toast.makeText(requireContext(), Api.friendlyMessage(e), Toast.LENGTH_SHORT).show()
        })
    }

    /** 下のタスク置き場。長押しで掴んで時間割へ運ぶ */
    private fun renderTray() {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        tray.removeAllViews()
        trayEmpty.visibility = if (leaves.isEmpty()) View.VISIBLE else View.GONE
        for (item in leaves) {
            val chip = TextView(ctx).apply {
                text = "${item.name}（${item.durationMinutes}分）"
                textSize = 12f
                val h = (10 * d).toInt()
                val v = (6 * d).toInt()
                setPadding(h, v, h, v)
                setTextColor(ContextCompat.getColor(ctx, R.color.on_bg))
                background = GradientDrawable().apply {
                    cornerRadius = 14 * d
                    setColor(ContextCompat.getColor(ctx, R.color.surface_variant))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = v }
                setOnLongClickListener { v2 ->
                    v2.startDragAndDrop(null, View.DragShadowBuilder(v2),
                        TimeGridView.DragPayload(item.id, item.durationMinutes, item.name), 0)
                }
                setOnClickListener {
                    Toast.makeText(ctx, "長押しして掴み、カレンダーへ運んでください", Toast.LENGTH_SHORT).show()
                }
            }
            tray.addView(chip)
        }
    }

    // ---- 自動で置く・進み具合 ----

    private fun autoPlace() {
        val ctx = requireContext().applicationContext
        Toast.makeText(requireContext(), "置いています…", Toast.LENGTH_SHORT).show()
        Api.async({ Repo.current(ctx).runScheduler(ctx, Prefs.fillDays(ctx)) }, { report ->
            if (!isAdded) return@async
            Toast.makeText(requireContext(), report.describe(), Toast.LENGTH_LONG).show()
            load()
        }, { e ->
            if (isAdded) Toast.makeText(requireContext(), Api.friendlyMessage(e), Toast.LENGTH_LONG).show()
        })
    }

    private fun showProgress() {
        val ctx = requireContext()
        val view = layoutInflater.inflate(R.layout.view_progress, null)
        val panel = ProgressPanel(view)
        val app = ctx.applicationContext
        fun refresh() {
            Api.async({
                Repo.current(app).statRows(app) to Repo.current(app).points(app)
            }, { (rows, pt) ->
                if (!isAdded) return@async
                panel.render(ctx, rows, pt)
                progressLine.text = ProgressPanel.oneLine(ctx, rows, pt)
            }, {})
        }
        panel.onGoalChanged = { refresh() }
        refresh()
        AlertDialog.Builder(ctx).setView(view).setPositiveButton("閉じる", null).show()
    }

    companion object {
        private val WEEKDAYS = arrayOf("月", "火", "水", "木", "金", "土", "日")
    }
}
