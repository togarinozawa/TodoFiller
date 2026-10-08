package dev.togar.dynasched.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.R
import dev.togar.dynasched.data.PointsSummary
import java.time.LocalDate

/**
 * 今日の進み具合（view_progress.xml）。カレンダー画面の一行を押すと出る。
 *
 * 大きな数・目標までの棒・続いた日数・今日片付けた物・オヤスギ・28日ぶんの升目。
 * 升目は「休んだ日も見える」ことが大事で、色の濃さで片付けた量を見せる。
 */
class ProgressPanel(private val root: View) {

    private val done = root.findViewById<TextView>(R.id.progressDone)
    private val label = root.findViewById<TextView>(R.id.progressLabel)
    private val streak = root.findViewById<TextView>(R.id.progressStreak)
    private val sub = root.findViewById<TextView>(R.id.progressSub)
    private val names = root.findViewById<TextView>(R.id.progressNames)
    private val points = root.findViewById<TextView>(R.id.progressPoints)
    private val goalButton = root.findViewById<TextView>(R.id.goalButton)
    private val bar = root.findViewById<ProgressBar>(R.id.progressBar)
    private val chain = root.findViewById<LinearLayout>(R.id.chainGrid)
    private var rows: List<StatRow> = emptyList()

    /** 目標を変えた時に呼ぶ（画面側の一行も直すため） */
    var onGoalChanged: (() -> Unit)? = null

    fun render(ctx: Context, statRows: List<StatRow>, pt: PointsSummary) {
        rows = statRows
        val today = LocalDate.now()
        val p = Stats.today(rows, today, goalOf(ctx, rows, today))
        val accent = ContextCompat.getColor(ctx, R.color.accent_goal)
        val primary = ContextCompat.getColor(ctx, R.color.primary)

        done.text = p.big.toString()
        label.text = p.bigLabel
        done.setTextColor(if (p.reached) accent else primary)
        bar.progress = (p.done * 100 / p.goal).coerceIn(0, 100)
        bar.progressTintList = ColorStateList.valueOf(if (p.reached) accent else primary)

        val days = Stats.streak(rows, today)
        streak.text = when {
            p.reached && days >= 2 -> "今日の分は済み・${days}日つづけて"
            p.reached -> "今日の分は済み"
            days >= 2 -> "${days}日つづけて"
            else -> ""
        }
        sub.text = "増やした ${p.added} ・ 残り ${p.undone}"
        goalButton.text = if (Prefs.dailyGoal(ctx) > 0) "目標 ${p.goal}件" else "目標 ${p.goal}件（自動）"
        goalButton.setOnClickListener { showGoalDialog(ctx) }

        val doneNames = Stats.doneToday(rows, today)
        if (doneNames.isEmpty()) {
            names.visibility = View.GONE
        } else {
            val more = p.done - doneNames.size
            names.text = (doneNames.joinToString("") { "・$it\n" } +
                if (more > 0) "・ほか${more}件" else "").trimEnd()
            names.visibility = View.VISIBLE
        }

        if (pt.total <= 0) {
            points.visibility = View.GONE
        } else {
            points.text = if (pt.today > 0) "オヤスギ ${pt.total}（今日 +${pt.today}）" else "オヤスギ ${pt.total}"
            points.visibility = View.VISIBLE
        }

        renderChain(ctx, Stats.daily(rows, today, 28), today)
        root.visibility = View.VISIBLE
    }

    /** 画面上部に出す一行。「3 件 片付けた ・ 5日つづけて ・ オヤスギ 12」 */
    fun oneLine(ctx: Context, statRows: List<StatRow>, pt: PointsSummary): String =
        Companion.oneLine(ctx, statRows, pt)

    private fun showGoalDialog(ctx: Context) {
        val auto = "自動（いまは${Stats.suggestGoal(rows, LocalDate.now())}件）"
        val items = listOf(auto) + (1..10).map { "${it}件" }
        AlertDialog.Builder(ctx)
            .setTitle("今日の目標")
            .setItems(items.toTypedArray()) { _, i ->
                // 0番目は「自動」。保存値0が自動の意味
                Prefs.setDailyGoal(ctx, i)
                onGoalChanged?.invoke()
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    /** 28日ぶんの升目。7日で1行。片付けた日は色、3件以上は濃い色 */
    private fun renderChain(ctx: Context, days: List<Stats.DayCount>, today: LocalDate) {
        val d = ctx.resources.displayMetrics.density
        val size = (13 * d).toInt()
        val gap = (3 * d).toInt()
        chain.removeAllViews()
        for (week in days.chunked(7)) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (day in week) {
                val v = View(ctx)
                v.layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(0, 0, gap, gap) }
                val color = when {
                    day.date == today && day.done == 0 -> R.color.on_bg_dim
                    day.done == 0 -> R.color.surface_variant
                    day.done >= 3 -> R.color.accent_goal
                    else -> R.color.primary
                }
                v.setBackgroundColor(ContextCompat.getColor(ctx, color))
                v.alpha = if (day.done != 0 || day.date == today) 1f else 0.5f
                row.addView(v)
            }
            chain.addView(row)
        }
    }

    companion object {
        /** 目標。決めていなければ実績から（[Stats.suggestGoal]） */
        fun goalOf(ctx: Context, rows: List<StatRow>, today: LocalDate): Int =
            Prefs.dailyGoal(ctx).takeIf { it > 0 } ?: Stats.suggestGoal(rows, today)

        fun oneLine(ctx: Context, statRows: List<StatRow>, pt: PointsSummary): String {
            val today = LocalDate.now()
            val p = Stats.today(statRows, today, goalOf(ctx, statRows, today))
            val days = Stats.streak(statRows, today)
            val extra = ArrayList<String>()
            if (days >= 2) extra.add("${days}日つづけて")
            if (pt.total > 0) extra.add("オヤスギ ${pt.total}")
            val head = "${p.big} ${p.bigLabel}"
            return if (extra.isEmpty()) head else head + " ・ " + extra.joinToString(" ・ ")
        }
    }
}
