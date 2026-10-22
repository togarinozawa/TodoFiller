package dev.togar.dynasched.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.SeekBar
import android.widget.TextView
import dev.togar.dynasched.api.CalColor

/**
 * カレンダー色の見本グリッド。丸い色見本をタップで選択し、選択中はリングで強調。
 * 値は CalColor.items の index で保持（idAt/hexFor で colorId/HEX に変換して使う）。
 */
class ColorPaletteView(context: Context, initialIndex: Int = 0) : GridLayout(context) {

    var selectedIndex: Int = initialIndex.coerceIn(0, CalColor.items.size - 1)
        private set

    private val swatches = mutableListOf<TextView>()
    private val density = context.resources.displayMetrics.density

    init {
        columnCount = 6
        val size = (44 * density).toInt()
        val margin = (5 * density).toInt()
        CalColor.items.forEachIndexed { i, item ->
            val v = TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 9f
                layoutParams = LayoutParams().apply {
                    width = size; height = size
                    setMargins(margin, margin, margin, margin)
                }
                setOnClickListener { select(i) }
                contentDescription = item.name
            }
            swatches.add(v)
            addView(v)
        }
        refresh()
    }

    /** 外から選択を差し替える（親タスクからの引き継ぎなど） */
    fun select(i: Int) {
        selectedIndex = i.coerceIn(0, CalColor.items.size - 1)
        refresh()
    }

    private fun refresh() {
        swatches.forEachIndexed { i, v ->
            val item = CalColor.items[i]
            val fill = try { Color.parseColor(item.hex) } catch (e: Exception) { Color.GRAY }
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fill)
                if (i == selectedIndex) {
                    setStroke((3 * density).toInt(), Color.WHITE)
                }
            }
            v.text = if (i == selectedIndex) "✓" else ""
            v.setTextColor(Color.WHITE)
        }
    }
}

/**
 * 必要時間ピッカー。時（0〜30）と分（0/15/30/45）の2ホイール＋合計表示。
 * totalMinutes で分単位の合計を取得（0 のときは呼び出し側で既定値に置き換える想定）。
 */
class DurationPickerView(context: Context, initialMinutes: Int = 30) : LinearLayout(context) {

    private val minuteSteps = arrayOf(0, 15, 30, 45)
    private val hourPicker = NumberPicker(context)
    private val minutePicker = NumberPicker(context)
    private val totalLabel = TextView(context)

    val totalMinutes: Int
        get() = hourPicker.value * 60 + minuteSteps[minutePicker.value]

    /** 外から値を差し替える（親タスクからの引き継ぎなど）。15分刻みに丸める */
    fun setMinutes(minutes: Int) {
        val snapped = ((minutes.coerceIn(0, 30 * 60 + 45) + 7) / 15) * 15
        hourPicker.value = (snapped / 60).coerceAtMost(30)
        minutePicker.value = minuteSteps.indexOf(snapped % 60).let { if (it >= 0) it else 0 }
        refreshTotal()
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        val init = initialMinutes.coerceIn(0, 30 * 60 + 45)
        // 15分刻みに丸める（四捨五入）
        val snapped = ((init + 7) / 15) * 15

        hourPicker.minValue = 0
        hourPicker.maxValue = 30
        hourPicker.value = (snapped / 60).coerceAtMost(30)
        hourPicker.wrapSelectorWheel = false

        minutePicker.minValue = 0
        minutePicker.maxValue = minuteSteps.size - 1
        minutePicker.displayedValues = minuteSteps.map { it.toString() }.toTypedArray()
        minutePicker.value = minuteSteps.indexOf(snapped % 60).let { if (it >= 0) it else 0 }
        minutePicker.wrapSelectorWheel = false

        val density = context.resources.displayMetrics.density
        fun unit(t: String) = TextView(context).apply {
            text = t; setPadding((4 * density).toInt(), 0, (12 * density).toInt(), 0)
        }

        addView(hourPicker)
        addView(unit("時間"))
        addView(minutePicker)
        addView(unit("分"))
        addView(totalLabel)

        val listener = NumberPicker.OnValueChangeListener { _, _, _ -> refreshTotal() }
        hourPicker.setOnValueChangedListener(listener)
        minutePicker.setOnValueChangedListener(listener)
        refreshTotal()
    }

    private fun refreshTotal() {
        totalLabel.text = "計 ${totalMinutes} 分"
    }
}

/**
 * スワイプ（ドラッグ）で値を選ぶスライダー。候補値のリストから1つ選ぶ。
 * 例: 難易度1〜5、進捗0〜100(5刻み)、優先度 低/中/高。
 */
class LabeledSlider(
    context: Context,
    private val values: List<Int>,
    initialValue: Int,
    private val labelOf: (Int) -> String = { it.toString() },
    /** 動かした時。目安の一言を下に出し直すのに使う */
    private val onChange: (Int) -> Unit = {}
) : LinearLayout(context) {

    private val seek = SeekBar(context)
    private val valueLabel = TextView(context)

    var value: Int
        get() = values[seek.progress.coerceIn(0, values.size - 1)]
        /** 外から値を差し替える（親タスクからの引き継ぎなど） */
        set(v) {
            val idx = values.indexOf(v)
            seek.progress = if (idx >= 0) idx else values.size / 2
            valueLabel.text = labelOf(value)
            onChange(value)
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        seek.max = values.size - 1
        val idx = values.indexOf(initialValue)
        seek.progress = if (idx >= 0) idx else values.size / 2
        seek.layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        val density = context.resources.displayMetrics.density
        valueLabel.minWidth = (64 * density).toInt()
        valueLabel.gravity = Gravity.END
        valueLabel.text = labelOf(value)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                valueLabel.text = labelOf(value)
                onChange(value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        addView(seek)
        addView(valueLabel)
    }
}

/**
 * タスクの優先度を選ぶ。段は設定で決めたもの（[dev.togar.dynasched.Priorities]）。
 * 右ほど上の段。選んでいる段の目安（「すぐやれ」など）を下に出す。
 */
class PriorityPicker(context: Context, initial: Int) : LinearLayout(context) {

    private val levels = dev.togar.dynasched.Priorities.all(context)
    private val memo = TextView(context).apply { textSize = 12f }
    private val slider: LabeledSlider

    var value: Int
        get() = slider.value
        set(v) { slider.value = dev.togar.dynasched.Priorities.levelOf(levels, v).value }

    init {
        orientation = VERTICAL
        val values = levels.map { it.value }.sorted()
        slider = LabeledSlider(
            context, values, dev.togar.dynasched.Priorities.levelOf(levels, initial).value,
            { v -> dev.togar.dynasched.Priorities.name(levels, v) }
        ) { v -> showMemo(v) }
        addView(slider)
        addView(memo)
        showMemo(slider.value)
    }

    private fun showMemo(v: Int) {
        val m = dev.togar.dynasched.Priorities.levelOf(levels, v).memo
        memo.text = m
        memo.visibility = if (m.isBlank()) GONE else VISIBLE
    }
}
