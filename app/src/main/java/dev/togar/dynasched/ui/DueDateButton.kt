package dev.togar.dynasched.ui

import android.app.DatePickerDialog
import android.content.Context
import com.google.android.material.button.MaterialButton
import java.time.LocalDate

/**
 * タスクの締切を選ぶボタン。押すと日付を選ぶ画面が出て、「締切なし」で外せる。
 *
 * やる日（その日にやる）とは別物。締切は「それまでに終わらせる」日で、いまやるの並びと、
 * ドパチルの締切の集中（/deadlines）に使う。
 */
class DueDateButton(ctx: Context, initial: String) :
    MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle) {

    /** yyyy-MM-dd。無ければ空 */
    var date: String = initial
        private set

    init {
        refresh()
        setOnClickListener { pick() }
    }

    private fun refresh() {
        text = if (date.isEmpty()) "締切なし（押して決める）" else "締切 ${label(date)}"
    }

    private fun pick() {
        val start = try { LocalDate.parse(date) } catch (e: Exception) { LocalDate.now().plusDays(1) }
        DatePickerDialog(context, { _, y, m, d ->
            date = LocalDate.of(y, m + 1, d).toString()
            refresh()
        }, start.year, start.monthValue - 1, start.dayOfMonth).apply {
            setButton(DatePickerDialog.BUTTON_NEUTRAL, "締切なし") { _, _ -> date = ""; refresh() }
        }.show()
    }

    companion object {
        /** 「10/30(金)」。今日・明日・過ぎた日はそう言う */
        fun label(date: String, today: LocalDate = LocalDate.now()): String {
            val d = try { LocalDate.parse(date) } catch (e: Exception) { return "" }
            val wd = "月火水木金土日"[d.dayOfWeek.value - 1]
            return when {
                d == today -> "今日まで"
                d == today.plusDays(1) -> "明日まで"
                d.isBefore(today) -> "${d.monthValue}/${d.dayOfMonth}($wd)（過ぎています）"
                else -> "${d.monthValue}/${d.dayOfMonth}($wd)まで"
            }
        }
    }
}
