package dev.togar.dynasched.ui

import android.app.DatePickerDialog
import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import dev.togar.dynasched.data.Want
import java.time.LocalDate

/**
 * 「やる日」の欄。日（決めない／今日／明日／明後日／日付を選ぶ）と、強さ（なるべく／絶対）。
 * 何時にやるかは決めない（配置に任せる）。
 */
class WantPickerView(ctx: Context, date: String, level: Int) : LinearLayout(ctx) {

    private val today = LocalDate.now()
    /** 日の候補。最後の「日付を選ぶ…」は値を持たない */
    private val dates = ArrayList<String>()
    private val labels = ArrayList<String>()
    private val daySpinner = Spinner(ctx)
    private val levelSpinner = Spinner(ctx)
    private val levels = listOf(Want.PREFER, Want.MUST)

    init {
        orientation = VERTICAL
        dates.addAll(listOf("", today.toString(), today.plusDays(1).toString(), today.plusDays(2).toString()))
        labels.addAll(listOf("決めない", "今日", "明日", "明後日"))
        if (date.isNotEmpty() && date !in dates) { dates.add(date); labels.add(Want.dayLabel(date, today)) }
        labels.add("日付を選ぶ…")
        daySpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, labels)
        daySpinner.setSelection(dates.indexOf(date).coerceAtLeast(0))
        levelSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
            listOf("なるべく（その日の枠に先に入れる）", "絶対（枠の外でも空いた時間に入れる）"))
        levelSpinner.setSelection(levels.indexOf(level).coerceAtLeast(0))
        addView(daySpinner)
        addView(levelSpinner)
        daySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == dates.size) pickDate() else refresh()
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        refresh()
    }

    private fun refresh() {
        levelSpinner.visibility = if (this.date.isEmpty()) GONE else VISIBLE
    }

    private fun pickDate() {
        val start = today.plusDays(1)
        DatePickerDialog(context, { _, y, m, d ->
            val picked = LocalDate.of(y, m + 1, d).toString()
            val at = dates.indexOf(picked).takeIf { it >= 0 } ?: run {
                dates.add(picked)
                labels.add(labels.size - 1, Want.dayLabel(picked, today))
                @Suppress("UNCHECKED_CAST")
                (daySpinner.adapter as ArrayAdapter<String>).notifyDataSetChanged()
                dates.size - 1
            }
            daySpinner.setSelection(at)
            refresh()
        }, start.year, start.monthValue - 1, start.dayOfMonth).apply {
            // 選ばずに閉じたら「決めない」へ戻す（「日付を選ぶ…」のままだと値が無い）
            setOnCancelListener { daySpinner.setSelection(0) }
        }.show()
    }

    /** 選んだ日。決めないなら空 */
    val date: String
        get() = dates.getOrNull(daySpinner.selectedItemPosition).orEmpty()

    /** 強さ。日を決めていなければ 0 */
    val level: Int
        get() = if (date.isEmpty()) Want.NONE else levels[levelSpinner.selectedItemPosition.coerceIn(0, 1)]
}
