package dev.togar.dynasched.ui

import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.Priorities
import dev.togar.dynasched.PriorityLevel
import dev.togar.dynasched.R
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.data.Repo
import dev.togar.dynasched.widget.SuggestWidgetProvider
import dev.togar.dynasched.widget.WidgetPrefs

/**
 * 優先度の段。足す・名前と目安を直す・消す。上ほど先にやる。
 *
 * 段の数字が足りなくなった時（[Priorities.plan]）は、タスク・習慣・絞り込み・
 * ウィジェットの数字もまとめて新しい段へ移す。片方だけ移すと、
 * 「優先度 1 で絞ったのに何も出ない」のような食い違いになる。
 */
class PrioritiesSettingsPage : SettingsPage("優先度", R.layout.settings_priorities) {

    private lateinit var list: LinearLayout

    override fun setup(root: View) {
        list = root.findViewById(R.id.levelList)
        root.findViewById<Button>(R.id.addLevelButton).setOnClickListener { add() }
        render()
    }

    private fun render() {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        list.removeAllViews()
        for ((i, l) in Priorities.all(ctx).withIndex()) {
            if (i > 0) list.addView(View(ctx).apply {
                setBackgroundColor(ctx.getColor(R.color.surface_variant))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
            list.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                addView(TextView(ctx).apply {
                    text = "${i + 1}. ${l.name}"
                    textSize = 16f
                    setTextColor(ctx.getColor(R.color.on_bg))
                })
                addView(TextView(ctx).apply {
                    text = l.memo.ifBlank { "目安なし" }
                    textSize = 12f
                    setTextColor(ctx.getColor(R.color.on_bg_dim))
                })
                setOnClickListener { edit(l) }
            })
        }
    }

    /** 名前と目安の入力欄 */
    private fun fields(level: PriorityLevel?): Triple<LinearLayout, EditText, EditText> {
        val ctx = requireContext()
        val pad = (20 * resources.displayMetrics.density).toInt()
        val name = EditText(ctx).apply {
            setText(level?.name ?: ""); hint = "名前（例: 1、すぐ、A）"; setSingleLine()
        }
        val memo = EditText(ctx).apply {
            setText(level?.memo ?: ""); hint = "目安（例: すぐやれ／暇ならやれ）"; setSingleLine()
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(name); addView(memo)
        }
        return Triple(box, name, memo)
    }

    private fun edit(level: PriorityLevel) {
        val ctx = requireContext()
        val (box, name, memo) = fields(level)
        val levels = Priorities.all(ctx)
        val b = AlertDialog.Builder(ctx)
            .setTitle("優先度の段")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val n = name.text.toString().trim().ifEmpty { level.name }
                apply(levels, levels.map {
                    if (it.value == level.value) it.copy(name = n, memo = memo.text.toString().trim()) else it
                })
            }
            .setNegativeButton("やめる", null)
        // 1段だけになると選ぶ意味が無くなるので、2段は残す
        if (levels.size > 2) b.setNeutralButton("消す") { _, _ -> confirmDelete(level) }
        b.show()
    }

    /** どこに足すかも選ぶ。上ほど先 */
    private fun add() {
        val ctx = requireContext()
        val levels = Priorities.all(ctx)
        if (levels.size >= Priorities.MAX_LEVELS) {
            Toast.makeText(ctx, "段は${Priorities.MAX_LEVELS}つまでです", Toast.LENGTH_SHORT).show()
            return
        }
        val (box, name, memo) = fields(null)
        val positions = listOf("一番上") +
            levels.zipWithNext().map { (a, b) -> "「${a.name}」と「${b.name}」の間" } + "一番下"
        val where = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, positions)
            setSelection(positions.size - 1)
        }
        box.addView(TextView(ctx).apply { text = "足す場所"; textSize = 12f })
        box.addView(where)
        AlertDialog.Builder(ctx)
            .setTitle("段を足す")
            .setView(box)
            .setPositiveButton("足す") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) {
                    Toast.makeText(ctx, "名前を入れてください", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val edited = levels.toMutableList()
                // 数字は 0 にしておくと Priorities.plan が隙間に入れる（入らなければ振り直す）
                edited.add(where.selectedItemPosition, PriorityLevel(0, n, memo.text.toString().trim()))
                apply(levels, edited)
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    /** 消す段のタスクをどこへ移すか選ぶ。既定は1つ下（一番下なら1つ上） */
    private fun confirmDelete(level: PriorityLevel) {
        val ctx = requireContext()
        val levels = Priorities.all(ctx)
        val others = levels.filter { it.value != level.value }
        val idx = levels.indexOf(level)
        val default = others.indexOf(levels.getOrNull(idx + 1) ?: levels[idx - 1]).coerceAtLeast(0)
        AlertDialog.Builder(ctx)
            .setTitle("「${level.name}」のタスクをどの段へ移しますか")
            .setSingleChoiceItems(others.map { it.label }.toTypedArray(), default) { d, i ->
                d.dismiss()
                apply(levels, others, mapOf(level.value to others[i].value))
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    /** 新しい段を保存し、数字が変わった物をまとめて移す */
    private fun apply(old: List<PriorityLevel>, edited: List<PriorityLevel>, movedTo: Map<Int, Int> = emptyMap()) {
        val ctx = requireContext().applicationContext
        val (levels, map) = Priorities.plan(old, edited, movedTo)
        Priorities.save(ctx, levels)
        if (map.any { (k, v) -> k != v }) {
            fun remap(p: Int) = Priorities.remap(old, map, p)
            Prefs.setTaskFilter(ctx, Prefs.taskFilter(ctx).let { f -> f.copy(priorities = f.priorities.map(::remap).toSet()) })
            Prefs.placeFilter(ctx)?.let { f -> Prefs.setPlaceFilter(ctx, f.copy(priorities = f.priorities.map(::remap).toSet())) }
            for (id in WidgetPrefs.placedIds(ctx)) {
                val cfg = WidgetPrefs.load(ctx, id)
                if (cfg.minPriority > 0) WidgetPrefs.save(ctx, id, cfg.copy(minPriority = remap(cfg.minPriority)))
            }
            Api.async({ Repo.current(ctx).remapPriorities(ctx, old, map) }, {
                SuggestWidgetProvider.updateAll(ctx)
            }, { e ->
                Toast.makeText(ctx, "タスクの優先度を移せませんでした: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show()
            })
        } else {
            SuggestWidgetProvider.updateAll(ctx)
        }
        if (isAdded) render()
    }
}
