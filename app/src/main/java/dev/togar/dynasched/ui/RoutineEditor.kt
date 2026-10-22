package dev.togar.dynasched.ui

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import dev.togar.dynasched.Places
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.data.Repo
import dev.togar.dynasched.data.Routine
import dev.togar.dynasched.data.Routines
import dev.togar.dynasched.widget.SuggestWidgetProvider
import java.time.LocalDate

/**
 * 習慣（ルーティン）の一覧と編集。毎日・毎週・毎月の決まった日に、タスクを1件作る。
 *
 * もとは「習慣」タブだった。タスクの一覧を習慣で絞り込めるようにしたのでタブは畳み、
 * 作るのはタスク追加画面の「繰り返す」、直すのはここ（タスク画面の「習慣」チップから開く）。
 *
 * 前に作った物が片付いていない間は作らない（[Routines.isDue]）。
 */
object RoutineEditor {

    /** 習慣の一覧。押すと直せる。[onChanged] は保存・削除のあと */
    fun showList(ctx: Context, onChanged: () -> Unit) {
        val app = ctx.applicationContext
        Api.async({ Repo.current(app).routines(app) }, { all ->
            if (all.isEmpty()) {
                AlertDialog.Builder(ctx)
                    .setTitle("習慣")
                    .setMessage("まだありません。\n「＋ タスクを追加」で「繰り返す」を選ぶと作れます。")
                    .setPositiveButton("閉じる", null)
                    .show()
                return@async
            }
            val today = LocalDate.now()
            val labels = all.map { r ->
                val next = Routines.nextDate(r, today)
                val whenText = when {
                    !r.isActive -> "止めている"
                    next == null -> "次の予定なし"
                    next == today -> "次は今日"
                    else -> "次は ${next.monthValue}/${next.dayOfMonth}(${Routines.WEEKDAY_NAMES[next.dayOfWeek.value - 1]})"
                }
                "${r.name}\n${r.summary()} ・ $whenText"
            }
            AlertDialog.Builder(ctx)
                .setTitle("習慣（${all.count { it.isActive }}件が動いている）")
                .setItems(labels.toTypedArray()) { _, i -> edit(ctx, all[i], onChanged) }
                .setNegativeButton("閉じる", null)
                .show()
        }, { e -> Toast.makeText(ctx, Api.friendlyMessage(e), Toast.LENGTH_SHORT).show() })
    }

    /** 習慣を直す。作るのはタスク追加画面なので、ここは既存のものだけ */
    fun edit(ctx: Context, existing: Routine, onChanged: () -> Unit) {
        val pad = (16 * ctx.resources.displayMetrics.density).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun label(s: String) = TextView(ctx).apply {
            text = s; textSize = 12f; setPadding(0, pad / 2, 0, 0)
        }

        val nameInput = EditText(ctx).apply { setText(existing.name); hint = "例：筋トレ" }
        box.addView(nameInput)

        box.addView(label("繰り返し"))
        val repeat = RepeatPickerView(ctx, existing.kind, existing.weekdays, existing.dayOfMonth, allowNone = false)
        box.addView(repeat)

        box.addView(label("必要時間"))
        val duration = DurationPickerView(ctx, existing.durationMinutes)
        box.addView(duration)

        box.addView(label("場所"))
        val placeChoices = Places.taskChoices(Places.all(ctx))
        val placeSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, placeChoices.map { it.second })
            setSelection(placeChoices.indexOfFirst { it.first == existing.location }.coerceAtLeast(0))
        }
        box.addView(placeSpinner)

        box.addView(label("優先度"))
        val prio = PriorityPicker(ctx, existing.priority)
        box.addView(prio)

        box.addView(label("タグ"))
        val tagsInput = EditText(ctx).apply { setText(Tags.display(existing.tags)); hint = "例：健康 朝" }
        box.addView(tagsInput)

        val activeCheck = CheckBox(ctx).apply {
            text = "動かす（外すとタスクを作らなくなる）"
            textSize = 13f
            isChecked = existing.isActive
        }
        box.addView(activeCheck)

        AlertDialog.Builder(ctx)
            .setTitle("習慣を直す")
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(ctx, "名前を入力してください", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                repeat.problem()?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
                save(ctx, existing.copy(
                    name = name,
                    kind = repeat.kind ?: "daily",
                    weekdays = repeat.weekdays,
                    dayOfMonth = repeat.dayOfMonth,
                    durationMinutes = maxOf(5, duration.totalMinutes),
                    priority = prio.value,
                    location = placeChoices[placeSpinner.selectedItemPosition].first,
                    tags = tagsInput.text.toString(),
                    isActive = activeCheck.isChecked
                ), onChanged)
            }
            .setNegativeButton("やめる", null)
            .setNeutralButton("消す") { _, _ -> confirmDelete(ctx, existing, onChanged) }
            .show()
    }

    /** 保存して、今日のぶんがまだなら作る */
    fun save(ctx: Context, routine: Routine, onChanged: () -> Unit) {
        val app = ctx.applicationContext
        Api.async({
            Repo.current(app).saveRoutine(app, routine)
            Repo.current(app).generateRoutines(app)
        }, { made ->
            if (made > 0) SuggestWidgetProvider.updateAll(app)
            onChanged()
        }, { e ->
            Toast.makeText(ctx, Api.friendlyMessage(e), Toast.LENGTH_LONG).show()
        })
    }

    private fun confirmDelete(ctx: Context, routine: Routine, onChanged: () -> Unit) {
        AlertDialog.Builder(ctx)
            .setMessage("「${routine.name}」の繰り返しをやめますか？（すでに作られたタスクは残ります）")
            .setPositiveButton("やめる") { _, _ ->
                val app = ctx.applicationContext
                Api.async({ Repo.current(app).deleteRoutine(app, routine.id) }, { onChanged() }, {})
            }
            .setNegativeButton("残す", null)
            .show()
    }
}
