package dev.togar.dynasched.ui

import android.app.Activity
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import dev.togar.dynasched.data.Repo
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.api.ScheduledEvent
import dev.togar.dynasched.api.SuggestItem
import dev.togar.dynasched.notify.AlarmScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「暇なとき」に、いまの状況（どこにいるか・何分あるか）を入れると
 * やることを提示するダイアログ。GET /suggest をそのまま使う。
 *
 * 既定値は手を抜けるように寄せてある:
 * - 場所はウィジェットと共有（Prefs.widgetLoc）
 * - 時間は次の予定が始まるまでの分数
 */
object FreeTimeDialog {

    /** 候補は絞らず全部見せる。選ぶのは自分でやりたいので。 */
    private const val ALL = 100

    fun show(activity: Activity) {
        val ctx = activity.applicationContext
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        root.addView(TextView(activity).apply {
            text = "いまどこにいますか"
            textSize = 14f
        })

        // 場所は設定で増やせる。横に並ぶのは3つまでで、それより多いと縦に積む
        val places = dev.togar.dynasched.Places.all(ctx)
        val current = Prefs.widgetLoc(ctx)
        val locGroup = RadioGroup(activity).apply {
            orientation = if (places.size <= 3) RadioGroup.HORIZONTAL else RadioGroup.VERTICAL
            for ((i, p) in places.withIndex()) {
                addView(RadioButton(activity).apply { id = i + 1; text = p.name })
            }
            check(places.indexOfFirst { it.id == current }.coerceAtLeast(0) + 1)
        }
        root.addView(locGroup)

        root.addView(TextView(activity).apply {
            text = "どれくらい時間がありますか"
            textSize = 14f
            setPadding(0, dp(12), 0, 0)
        })

        // 次の予定までの時間を初期値にする（そのまま押せば済むように）。
        // ただし**就寝時刻を越える提案はしない**。越えて出すと、寝る時間を削る前提の
        // 予定を毎回すすめることになる。
        val untilBed = minutesUntilBedtime(ctx)
        val initial = (Prefs.cachedEvents(ctx)
            ?.let { ScheduledEvent.freeMinutesUntilNext(ScheduledEvent.fromJsonArray(it)) }
            ?: 30).let { if (untilBed in 1 until it) untilBed else it }
        val duration = DurationPickerView(activity, initial)
        root.addView(duration)

        if (untilBed in 1..90) {
            root.addView(TextView(activity).apply {
                text = "就寝まであと${untilBed}分です"
                textSize = 11f
                setPadding(0, dp(4), 0, 0)
            })
        }

        // タグで候補を絞れるようにする。「いま買い物系だけ見たい」を1タップで
        val tagButton = android.widget.Button(activity).apply {
            text = tagButtonLabel(emptySet())
            textSize = 12f
        }
        var chosen: Set<String> = emptySet()
        tagButton.setOnClickListener {
            pickTags(activity, chosen) { picked ->
                chosen = picked
                tagButton.text = tagButtonLabel(picked)
            }
        }
        root.addView(tagButton)

        AlertDialog.Builder(activity)
            .setTitle("暇なとき")
            .setView(root)
            .setNegativeButton("閉じる", null)
            .setPositiveButton("提案して") { _, _ ->
                val loc = places.getOrNull(locGroup.checkedRadioButtonId - 1)?.id
                    ?: dev.togar.dynasched.Places.HOME
                val raw = duration.totalMinutes.let { if (it <= 0) 30 else it }
                val cap = minutesUntilBedtime(ctx)
                val min = if (cap in 1 until raw) cap else raw
                Prefs.setWidgetLoc(ctx, loc)   // ウィジェットと設定を揃えておく
                fetch(activity, loc, min, chosen)
            }
            .show()
    }

    /** 就寝時刻まであと何分か。すでに過ぎていれば 0 */
    private fun minutesUntilBedtime(ctx: android.content.Context): Int {
        val now = java.util.Calendar.getInstance()
        val nowMin = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        return (Prefs.bedtimeMinutes(ctx) - nowMin).coerceAtLeast(0)
    }

    private fun tagButtonLabel(tags: Set<String>): String =
        if (tags.isEmpty()) "タグで絞る（いまは全部）" else "#${tags.joinToString(" #")}"

    private fun pickTags(activity: Activity, current: Set<String>, onPick: (Set<String>) -> Unit) {
        val ctx = activity.applicationContext
        Api.async(
            work = { Tags.known(Repo.current(ctx).getHobby(ctx)) },
            onSuccess = { known ->
                if (activity.isFinishing) return@async
                if (known.isEmpty()) {
                    Toast.makeText(activity, "まだタグがありません", Toast.LENGTH_SHORT).show()
                    return@async
                }
                val selected = current.toMutableSet()
                val checked = BooleanArray(known.size) { selected.contains(known[it]) }
                AlertDialog.Builder(activity)
                    .setTitle("タグで絞る")
                    .setMultiChoiceItems(known.toTypedArray(), checked) { _, which, isChecked ->
                        if (isChecked) selected.add(known[which]) else selected.remove(known[which])
                    }
                    .setPositiveButton("決定") { _, _ -> onPick(selected) }
                    .setNeutralButton("全部見る") { _, _ -> onPick(emptySet()) }
                    .setNegativeButton("やめる", null)
                    .show()
            },
            onError = { /* 絞れないだけ */ }
        )
    }

    private fun fetch(activity: Activity, loc: String, min: Int, tags: Set<String>) {
        val ctx = activity.applicationContext
        Toast.makeText(activity, "候補を探しています…", Toast.LENGTH_SHORT).show()
        Api.async(
            work = { Repo.current(ctx).getSuggestions(ctx, loc, min, ALL, tags) },
            onSuccess = { items ->
                if (activity.isFinishing) return@async
                showResult(activity, loc, min, items)
            },
            onError = { e ->
                if (activity.isFinishing) return@async
                Toast.makeText(activity, Api.friendlyMessage(e), Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun showResult(activity: Activity, loc: String, min: Int, items: List<SuggestItem>) {
        val locLabel = dev.togar.dynasched.Places.name(dev.togar.dynasched.Places.all(activity), loc)
        if (items.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle("$locLabel・${min}分")
                .setMessage("いまできる候補はありません。ゆっくり休みましょう。")
                .setPositiveButton("閉じる", null)
                .show()
            return
        }
        val labels = items.map {
            val mark = if (it.kind == "material") "🎯 " else "・"
            "$mark${it.title}（${it.minutes}分）"
        }.toTypedArray()

        AlertDialog.Builder(activity)
            .setTitle("$locLabel・${min}分ならこれ")
            .setItems(labels) { _, which -> decide(activity, items[which], min) }
            .setPositiveButton("ランダム") { _, _ -> decide(activity, items.random(), min) }
            .setNegativeButton("閉じる", null)
            .setNeutralButton("条件を変える") { _, _ -> show(activity) }
            .show()
    }

    /**
     * やることを決めたら、終わっているはずの時刻に確認通知を予約する。
     * 決めた時点では何も記録しない。「やった」と言うのは終わってからでいい。
     */
    private fun decide(activity: Activity, item: SuggestItem, availableMin: Int) {
        val ctx = activity.applicationContext
        val minutes = minOf(item.minutes, availableMin).coerceAtLeast(5)
        val at = System.currentTimeMillis() + minutes * 60_000L
        AlarmScheduler.scheduleFreeTimeCheck(ctx, item.title, item.kind, item.id, at, minutes)
        val label = SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date(at))
        Toast.makeText(
            activity, "「${item.title}」を${minutes}分。$label に確認します", Toast.LENGTH_LONG
        ).show()
    }
}
