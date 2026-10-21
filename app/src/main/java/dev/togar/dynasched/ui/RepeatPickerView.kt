package dev.togar.dynasched.ui

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import dev.togar.dynasched.data.Routines

/**
 * 「繰り返す」の欄。しない／毎日／毎週（曜日）／毎月（日）。
 *
 * 習慣タブを畳んで、タスクを作る画面で繰り返しも決めるようにした。
 * 選んでいない欄（毎日なのに曜日など）は隠す。出したままだと、
 * 何を入れれば効くのかが見て分からない。
 */
class RepeatPickerView(
    ctx: Context,
    kind: String? = null,
    weekdays: Set<Int> = emptySet(),
    dayOfMonth: Int = 1,
    /** 「繰り返さない」を選べるか。習慣を直す時は出さない */
    allowNone: Boolean = true
) : LinearLayout(ctx) {

    private val kinds = (if (allowNone) listOf(NONE) else emptyList()) + listOf("daily", "weekly", "monthly")
    private val labels = (if (allowNone) listOf("繰り返さない") else emptyList()) + listOf("毎日", "毎週", "毎月")

    private val spinner = Spinner(ctx)
    private val dayRow = LinearLayout(ctx).apply { orientation = HORIZONTAL }
    private val dayChecks = ArrayList<CheckBox>()
    private val monthRow = LinearLayout(ctx).apply { orientation = HORIZONTAL }
    private val monthInput = EditText(ctx).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(dayOfMonth.coerceIn(1, 31).toString())
        minEms = 2
    }

    init {
        orientation = VERTICAL
        spinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.setSelection(kinds.indexOf(kind ?: kinds.first()).coerceAtLeast(0))
        addView(spinner)

        for (wd in 1..7) {
            val c = CheckBox(ctx).apply {
                text = Routines.WEEKDAY_NAMES[wd - 1]
                textSize = 11f
                isChecked = wd in weekdays
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }
            dayChecks.add(c)
            dayRow.addView(c)
        }
        addView(dayRow)

        monthRow.addView(TextView(ctx).apply { text = "毎月 " })
        monthRow.addView(monthInput)
        monthRow.addView(TextView(ctx).apply { text = " 日" })
        addView(monthRow)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refresh()
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        refresh()
    }

    private fun refresh() {
        dayRow.visibility = if (this.kind == "weekly") VISIBLE else GONE
        monthRow.visibility = if (this.kind == "monthly") VISIBLE else GONE
    }

    /** "daily" / "weekly" / "monthly"。繰り返さないなら null */
    val kind: String?
        get() = kinds.getOrNull(spinner.selectedItemPosition)?.takeUnless { it == NONE }

    val weekdays: Set<Int>
        get() = dayChecks.withIndex().filter { it.value.isChecked }.map { it.index + 1 }.toSet()

    val dayOfMonth: Int
        get() = (monthInput.text.toString().toIntOrNull() ?: 1).coerceIn(1, 31)

    /** 毎週なのに曜日が無い、のように入れ忘れていれば、その一言 */
    fun problem(): String? =
        if (kind == "weekly" && weekdays.isEmpty()) "曜日を選んでください" else null

    private companion object {
        const val NONE = "none"
    }
}
