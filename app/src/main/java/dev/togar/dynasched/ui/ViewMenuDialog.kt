package dev.togar.dynasched.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.togar.dynasched.R

/**
 * [ViewMenu.rows] を出すだけのダイアログ。
 *
 * 標準の `setItems` をやめたのは、平らな文字列の並びでは節の切れ目を描けないため。
 * 押したら閉じる挙動は前と同じ（選んだ結果が後ろの一覧にすぐ出る）。
 */
object ViewMenuDialog {

    private const val SECTION = 0
    private const val ROW = 1

    fun show(ctx: Context, rows: List<ViewMenuRow>, onPick: (ViewMenuAction) -> Unit) {
        val list = LayoutInflater.from(ctx)
            .inflate(R.layout.dialog_view_menu, null) as RecyclerView
        list.layoutManager = LinearLayoutManager(ctx)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("並び順と表示")
            .setView(list)
            .setNegativeButton("閉じる", null)
            .create()

        list.adapter = Adapter(rows) { action ->
            dialog.dismiss()
            onPick(action)
        }
        dialog.show()
    }

    private class Adapter(
        val rows: List<ViewMenuRow>,
        val onPick: (ViewMenuAction) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) =
            if (rows[position] is ViewMenuRow.Section) SECTION else ROW

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val id = if (viewType == SECTION) R.layout.item_view_menu_section
            else R.layout.item_view_menu_row
            return object : RecyclerView.ViewHolder(inflater.inflate(id, parent, false)) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            when (val row = rows[position]) {
                is ViewMenuRow.Section -> {
                    v.findViewById<TextView>(R.id.sectionTitle).text = row.title
                    // 一番上の節には罫線を引かない。ダイアログの題と二重になるため
                    v.findViewById<View>(R.id.sectionRule).visibility =
                        if (position == 0) View.GONE else View.VISIBLE
                }
                is ViewMenuRow.Choice -> {
                    val mark = v.findViewById<TextView>(R.id.menuMark)
                    val label = v.findViewById<TextView>(R.id.menuLabel)
                    mark.visibility = View.VISIBLE
                    mark.text = if (row.selected) "◉" else "○"
                    val color = ContextCompat.getColor(
                        v.context, if (row.selected) R.color.primary else R.color.on_bg_dim
                    )
                    mark.setTextColor(color)
                    label.text = row.label
                    label.setTextColor(
                        ContextCompat.getColor(
                            v.context, if (row.selected) R.color.primary else R.color.on_bg
                        )
                    )
                    v.findViewById<View>(R.id.menuValue).visibility = View.GONE
                    v.findViewById<View>(R.id.menuChevron).visibility = View.GONE
                    v.setOnClickListener { onPick(row.action) }
                }
                is ViewMenuRow.Action -> {
                    // 丸印の場所は空けたまま。ラベルの左端が選択肢と揃う
                    v.findViewById<TextView>(R.id.menuMark).visibility = View.INVISIBLE
                    val label = v.findViewById<TextView>(R.id.menuLabel)
                    label.text = row.label
                    label.setTextColor(ContextCompat.getColor(v.context, R.color.on_bg))
                    val value = v.findViewById<TextView>(R.id.menuValue)
                    value.text = row.value ?: ""
                    value.visibility = if (row.value == null) View.GONE else View.VISIBLE
                    v.findViewById<View>(R.id.menuChevron).visibility =
                        if (row.opensDialog) View.VISIBLE else View.GONE
                    v.setOnClickListener { onPick(row.action) }
                }
            }
        }
    }
}
