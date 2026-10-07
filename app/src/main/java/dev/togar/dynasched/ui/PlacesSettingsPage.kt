package dev.togar.dynasched.ui

import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import dev.togar.dynasched.Place
import dev.togar.dynasched.Places
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.R
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.data.Repo
import dev.togar.dynasched.widget.SuggestWidgetProvider

/**
 * 場所の一覧。足す・名前を変える・消す。
 *
 * **消した場所のタスクと教材は「どこでも」に戻す。**消した場所を指したままだと、
 * その枠がもう無いので二度と配置されず、一覧からは理由が分からない。
 */
class PlacesSettingsPage : SettingsPage("場所", R.layout.settings_places) {

    private lateinit var list: LinearLayout

    override fun setup(root: View) {
        list = root.findViewById(R.id.placeList)
        root.findViewById<Button>(R.id.addPlaceButton).setOnClickListener { edit(null) }
        render()
    }

    private fun render() {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        list.removeAllViews()
        for ((i, p) in Places.all(ctx).withIndex()) {
            if (i > 0) list.addView(View(ctx).apply {
                setBackgroundColor(ctx.getColor(R.color.surface_variant))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
            list.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                isClickable = true
                isFocusable = true
                val attrs = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = attrs.getDrawable(0)
                attrs.recycle()
                addView(TextView(ctx).apply {
                    text = p.name
                    textSize = 16f
                    setTextColor(ctx.getColor(R.color.on_bg))
                })
                addView(TextView(ctx).apply {
                    text = "カレンダーの印: " + p.tags().joinToString(" / ") { "「$it」" } +
                        if (p.builtin) "（消せません）" else ""
                    textSize = 12f
                    setTextColor(ctx.getColor(R.color.on_bg_dim))
                })
                setOnClickListener { edit(p) }
            })
        }
    }

    /** 足す（place が null）か、名前を変える。足した場所は消すこともできる */
    private fun edit(place: Place?) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            setText(place?.name ?: "")
            hint = "例: 学校、図書館、バイト先"
            setSingleLine()
            setSelection(text.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(ctx).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val b = AlertDialog.Builder(ctx)
            .setTitle(if (place == null) "場所を足す" else "場所の名前")
            .setView(box)
            .setPositiveButton("保存", null)   // 押しても閉じないよう、後で差し替える
            .setNegativeButton("やめる", null)
        if (place != null && !place.builtin) b.setNeutralButton("消す") { _, _ -> confirmDelete(place) }
        val dialog = b.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                val places = Places.all(ctx)
                val err = Places.validateName(places, name, place?.id)
                if (err != null) {
                    input.error = err
                    return@setOnClickListener
                }
                dialog.dismiss()
                save(place, name, places)
            }
        }
        dialog.show()
    }

    private fun save(place: Place?, name: String, places: List<Place>) {
        val ctx = requireContext().applicationContext
        if (place == null) {
            Places.save(ctx, places + Place(Places.newId(places), name))
            Toast.makeText(ctx, "予定の題名の末尾に「$name」を付けると、ここの枠になります",
                Toast.LENGTH_LONG).show()
        } else {
            if (place.name == name) return
            Places.save(ctx, places.map { if (it.id == place.id) it.copy(name = name) else it })
            // Notionの選択肢は「〇〇のみ」の名前で持っているので、押し返して揃える
            Api.async({ Repo.current(ctx).moveFromPlace(ctx, place.id, place.id) }, {}, {})
            val msg = if (place.builtin)
                "名前を変えました。末尾が「${place.tags().drop(1).joinToString("」「")}」の予定もそのまま読みます"
            else "名前を変えました。カレンダーの予定の末尾も「$name」に直してください"
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
        changed()
    }

    private fun confirmDelete(place: Place) {
        val ctx = requireContext().applicationContext
        Api.async({ Repo.current(ctx).countUsingPlace(ctx, place.id) }, { (tasks, materials) ->
            if (!isAdded) return@async
            val counts = listOfNotNull(
                if (tasks > 0) "タスク${tasks}件" else null,
                if (materials > 0) "教材${materials}件" else null
            )
            val used = if (counts.isEmpty()) ""
                else "「${place.onlyLabel}」の${counts.joinToString("・")}は「どこでも」になります。\n\n"
            AlertDialog.Builder(requireContext())
                .setTitle("「${place.name}」を消しますか")
                .setMessage(used + "末尾が「${place.name}」のカレンダーの予定は、" +
                    "枠ではなく普通の予定（埋まっている時間）として読まれるようになります。")
                .setPositiveButton("消す") { _, _ ->
                    Places.save(ctx, Places.all(ctx).filterNot { it.id == place.id })
                    Api.async({
                        Repo.current(ctx).moveFromPlace(ctx, place.id, Places.ANYWHERE)
                    }, {}, {})
                    changed()
                }
                .setNegativeButton("やめる", null)
                .show()
        }, { e ->
            if (isAdded) Toast.makeText(ctx, "失敗: " + Api.friendlyMessage(e), Toast.LENGTH_LONG).show()
        })
    }

    /** 場所の名前はウィジェットの見出しにも出る */
    private fun changed() {
        if (isAdded) render()
        val ctx = context?.applicationContext ?: return
        SuggestWidgetProvider.updateAll(ctx)
    }
}
