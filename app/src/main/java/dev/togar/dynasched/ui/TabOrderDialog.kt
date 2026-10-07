package dev.togar.dynasched.ui

import android.app.Activity
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.togar.dynasched.R

/**
 * タブの並びを変えるダイアログ。上にあるほど左に並ぶ。
 *
 * タブの帯そのものを横にドラッグさせる案もあったが、帯は横に流れるので
 * 端のタブを遠くへ運ぶのがつらい。縦の一覧にして、右の「≡」をつかんで動かす。
 */
object TabOrderDialog {

    fun show(activity: Activity, title: String, tabs: List<TaskTab>, onSave: (List<String>) -> Unit) {
        val items = tabs.toMutableList()
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val recycler = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            setPadding(0, dp(8), 0, dp(8))
        }
        lateinit var helper: ItemTouchHelper

        class VH(val row: LinearLayout, val label: TextView, val handle: TextView) : RecyclerView.ViewHolder(row)

        val adapter = object : RecyclerView.Adapter<VH>() {
            override fun getItemCount() = items.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
                val label = TextView(activity).apply {
                    textSize = 16f
                    setTextColor(activity.getColor(R.color.on_bg))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val handle = TextView(activity).apply {
                    text = "≡"
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setTextColor(activity.getColor(R.color.on_bg_dim))
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                    contentDescription = "つかんで動かす"
                }
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(24), 0, dp(8), 0)
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    addView(label)
                    addView(handle)
                }
                return VH(row, label, handle)
            }

            @android.annotation.SuppressLint("ClickableViewAccessibility")
            override fun onBindViewHolder(holder: VH, position: Int) {
                holder.label.text = items[position].label
                // 「≡」に触れたらすぐ掴む。行の長押しでも掴める
                holder.handle.setOnTouchListener { _, e ->
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) helper.startDrag(holder)
                    false
                }
            }
        }
        recycler.adapter = adapter

        helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder
            ): Boolean {
                val a = from.bindingAdapterPosition
                val b = to.bindingAdapterPosition
                items.add(b, items.removeAt(a))
                adapter.notifyItemMoved(a, b)
                return true
            }

            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(holder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(holder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && holder != null) {
                    holder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    holder.itemView.setBackgroundColor(activity.getColor(R.color.surface_variant))
                }
            }

            override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(rv, holder)
                holder.itemView.background = null
            }
        })
        helper.attachToRecyclerView(recycler)

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = "上にあるほど左に並びます。右の ≡ をつかんで動かしてください。"
                textSize = 12f
                setTextColor(activity.getColor(R.color.on_bg_dim))
                setPadding(dp(24), dp(8), dp(24), 0)
            })
            addView(recycler)
        }

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("決定") { _, _ -> onSave(items.map { it.key }) }
            .setNegativeButton("やめる", null)
            .show()
    }
}
