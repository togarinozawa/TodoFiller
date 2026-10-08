package dev.togar.dynasched.ui

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import dev.togar.dynasched.Places
import dev.togar.dynasched.R
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.data.Repo
import dev.togar.dynasched.data.Routine
import dev.togar.dynasched.data.Routines
import dev.togar.dynasched.widget.SuggestWidgetProvider
import java.time.LocalDate

/**
 * 習慣（ルーティン）。毎日・毎週・毎月の決まった日に、単発タスクを1件作る。
 *
 * 開くたびに今日のぶんを作る（起動時にも作っている）。
 * 前に作った物が片付いていない間は作らない（[Routines.isDue]）。
 */
class RoutineFragment : Fragment() {

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var summary: TextView
    private var routines: List<Routine> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_routine, container, false)
        list = root.findViewById(R.id.routineList)
        empty = root.findViewById(R.id.emptyText)
        summary = root.findViewById(R.id.summaryText)
        root.findViewById<Button>(R.id.addRoutineButton).setOnClickListener { edit(null) }
        return root
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val ctx = requireContext().applicationContext
        Api.async({
            val repo = Repo.current(ctx)
            repo.generateRoutines(ctx) to repo.routines(ctx)
        }, { (made, all) ->
            if (!isAdded) return@async
            routines = all
            if (made > 0) {
                Toast.makeText(requireContext(), "今日のぶんを${made}件作りました", Toast.LENGTH_SHORT).show()
                SuggestWidgetProvider.updateAll(ctx)
            }
            render()
        }, { e ->
            if (isAdded) Toast.makeText(requireContext(), Api.friendlyMessage(e), Toast.LENGTH_SHORT).show()
        })
    }

    private fun render() {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        list.removeAllViews()
        empty.visibility = if (routines.isEmpty()) View.VISIBLE else View.GONE
        val active = routines.count { it.isActive }
        summary.text = if (routines.isEmpty()) ""
            else "動いているルーティン ${active}件 ・ 止めている ${routines.size - active}件"
        val today = LocalDate.now()
        for (r in routines) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val h = (14 * d).toInt()
                val v = (10 * d).toInt()
                setPadding(h, v, h, v)
                setBackgroundColor(ContextCompat.getColor(ctx, R.color.surface))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, (6 * d).toInt()) }
                setOnClickListener { edit(r) }
            }
            row.addView(TextView(ctx).apply {
                text = r.name
                textSize = 15f
                setTextColor(ContextCompat.getColor(ctx, R.color.on_bg))
                alpha = if (r.isActive) 1f else 0.45f
            })
            val next = Routines.nextDate(r, today)
            val when_ = when {
                !r.isActive -> "止めている"
                next == null -> "次の予定なし（曜日を選んでください）"
                next == today -> "次は今日"
                else -> "次は ${next.monthValue}/${next.dayOfMonth}(${Routines.WEEKDAY_NAMES[next.dayOfWeek.value - 1]})"
            }
            row.addView(TextView(ctx).apply {
                text = "${r.summary()} ・ $when_"
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.on_bg_dim))
            })
            list.addView(row)
        }
    }

    private fun label(ctx: Context, pad: Int, s: String) = TextView(ctx).apply {
        text = s
        textSize = 12f
        setPadding(0, pad / 2, 0, 0)
        setTextColor(ContextCompat.getColor(ctx, R.color.on_bg_dim))
    }

    private fun edit(existing: Routine?) {
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val nameInput = EditText(ctx).apply {
            setText(existing?.name ?: "")
            hint = "例：筋トレ"
        }
        box.addView(nameInput)

        box.addView(label(ctx, pad, "繰り返し"))
        val kinds = listOf("daily", "weekly", "monthly")
        val kindSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, listOf("毎日", "毎週", "毎月"))
            setSelection(kinds.indexOf(existing?.kind ?: "daily").coerceAtLeast(0))
        }
        box.addView(kindSpinner)

        box.addView(label(ctx, pad, "曜日（毎週のとき）"))
        val dayChecks = ArrayList<CheckBox>()
        val dayRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (wd in 1..7) {
            val c = CheckBox(ctx).apply {
                text = Routines.WEEKDAY_NAMES[wd - 1]
                textSize = 11f
                isChecked = existing?.weekdays?.contains(wd) == true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            dayChecks.add(c)
            dayRow.addView(c)
        }
        box.addView(dayRow)

        box.addView(label(ctx, pad, "日（毎月のとき）"))
        val dayOfMonthInput = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((existing?.dayOfMonth ?: 1).toString())
        }
        box.addView(dayOfMonthInput)

        box.addView(label(ctx, pad, "必要時間"))
        val duration = DurationPickerView(ctx, existing?.durationMinutes ?: 30)
        box.addView(duration)

        box.addView(label(ctx, pad, "場所"))
        val placeChoices = Places.taskChoices(Places.all(ctx))
        val placeSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, placeChoices.map { it.second })
            setSelection(placeChoices.indexOfFirst { it.first == (existing?.location ?: Places.ANYWHERE) }
                .coerceAtLeast(0))
        }
        box.addView(placeSpinner)

        box.addView(label(ctx, pad, "優先度"))
        val prioValues = listOf(3, 5, 8)
        val prioNames = arrayOf("低", "中", "高")
        val prio = LabeledSlider(ctx, prioValues, existing?.priority ?: 5) { v ->
            prioNames[prioValues.indexOf(v).coerceIn(0, 2)]
        }
        box.addView(prio)

        box.addView(label(ctx, pad, "タグ"))
        val tagsInput = EditText(ctx).apply {
            setText(existing?.tags ?: "")
            hint = "例：健康 朝"
        }
        box.addView(tagsInput)

        val activeCheck = CheckBox(ctx).apply {
            text = "動かす（外すとタスクを作らなくなる）"
            textSize = 13f
            isChecked = existing?.isActive ?: true
        }
        box.addView(activeCheck)

        val b = AlertDialog.Builder(ctx)
            .setTitle(if (existing == null) "ルーティンを作る" else "ルーティンを直す")
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(ctx, "名前を入力してください", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                save(Routine(
                    id = existing?.id ?: 0,
                    name = name,
                    kind = kinds[kindSpinner.selectedItemPosition],
                    weekdays = dayChecks.withIndex().filter { it.value.isChecked }.map { it.index + 1 }.toSet(),
                    dayOfMonth = (dayOfMonthInput.text.toString().toIntOrNull() ?: 1).coerceIn(1, 31),
                    durationMinutes = maxOf(5, duration.totalMinutes),
                    priority = prio.value,
                    location = placeChoices[placeSpinner.selectedItemPosition].first,
                    color = existing?.color ?: "",
                    tags = tagsInput.text.toString(),
                    lastMade = existing?.lastMade ?: "",
                    isActive = activeCheck.isChecked
                ))
            }
            .setNegativeButton("やめる", null)
        if (existing != null) b.setNeutralButton("消す") { _, _ -> confirmDelete(existing) }
        b.show()
    }

    private fun save(routine: Routine) {
        val ctx = requireContext().applicationContext
        Api.async({
            Repo.current(ctx).saveRoutine(ctx, routine)
            Repo.current(ctx).generateRoutines(ctx)
        }, { if (isAdded) load() }, { e ->
            if (isAdded) Toast.makeText(requireContext(), Api.friendlyMessage(e), Toast.LENGTH_LONG).show()
        })
    }

    private fun confirmDelete(routine: Routine) {
        AlertDialog.Builder(requireContext())
            .setMessage("「${routine.name}」を消しますか？（すでに作られたタスクは残ります）")
            .setPositiveButton("消す") { _, _ ->
                val ctx = requireContext().applicationContext
                Api.async({ Repo.current(ctx).deleteRoutine(ctx, routine.id) }, { if (isAdded) load() }, {})
            }
            .setNegativeButton("やめる", null)
            .show()
    }
}
