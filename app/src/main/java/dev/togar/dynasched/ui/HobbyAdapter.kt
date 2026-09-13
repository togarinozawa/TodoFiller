package dev.togar.dynasched.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.togar.dynasched.R
import dev.togar.dynasched.api.CalColor
import dev.togar.dynasched.api.HobbyItem

/**
 * 単発タスクの階層リスト。
 *
 * 表示は [TaskList] が組んだ行をそのまま出すだけにしてある。
 * 並び順・折りたたみ・完了のまとめ方をここで判断すると、
 * 画面を動かさないと確かめられないものが増える。
 *
 * **長押しでそのまま掴んで動かせる**。モードに入る手順は挟まない。
 * 掴んだ行はボタンを隠して、1枚の板を持ち上げているように見せる。
 */
class HobbyAdapter(
    private val onToggle: (HobbyItem, Boolean) -> Unit,
    private val onAddChild: (HobbyItem) -> Unit,
    private val onDelete: (HobbyItem) -> Unit,
    private val onEdit: (HobbyItem) -> Unit,
    private val onCollapse: (HobbyItem) -> Unit
) : RecyclerView.Adapter<HobbyAdapter.VH>() {

    private val rows = ArrayList<TaskRow>()

    /** つかんでいる行のID。その行だけボタンを隠して、掴んだ塊に見せる */
    var draggingId: Long? = null

    fun submit(list: List<TaskRow>) {
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
    }

    fun rows(): List<TaskRow> = rows

    /** ドラッグ中の見た目だけ先に入れ替える（保存は離した時） */
    fun moveVisually(from: Int, to: Int) {
        if (from !in rows.indices || to !in rows.indices) return
        rows.add(to, rows.removeAt(from))
        notifyItemMoved(from, to)
    }

    /**
     * 優先度の帯色。**数字を読ませる前に、高い低いを色で伝える**のが目的。
     * 既定が 3/5/8 の3段なので、その境目で切ってある。
     */
    private fun priorityColor(p: Int): Int = when {
        p >= 8 -> 0xFFE24A90.toInt()   // 高い
        p >= 5 -> 0xFF4A90E2.toInt()   // 普通
        else -> 0xFF5F5F6E.toInt()     // 低い
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val root: LinearLayout = view.findViewById(R.id.itemRoot)
        val expand: TextView = view.findViewById(R.id.expandToggle)
        val check: CheckBox = view.findViewById(R.id.checkBox)
        val title: TextView = view.findViewById(R.id.taskTitle)
        val sub: TextView = view.findViewById(R.id.taskSub)
        val priority: TextView = view.findViewById(R.id.priorityChip)
        val textContainer: View = title.parent as View
        val addChild: Button = view.findViewById(R.id.addChildButton)
        val delete: Button = view.findViewById(R.id.deleteButton)
        val basePaddingStart: Int = view.paddingStart
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_hobby, parent, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        val item = row.item
        val density = holder.itemView.resources.displayMetrics.density

        // インデント（子タスクは右にずらす。深い階層でも溢れないよう1段24dp）
        val extra = (row.level * 24 * density).toInt()
        holder.root.setPadding(
            holder.basePaddingStart + extra,
            holder.root.paddingTop, holder.root.paddingEnd, holder.root.paddingBottom
        )

        // 親は ▸/▾ で畳める。畳むと配下は行ごと消えて、親1行に要約が出る
        if (row.hasChildren) {
            holder.expand.visibility = View.VISIBLE
            holder.expand.text = if (row.collapsed) "▸" else "▾"
            holder.expand.setOnClickListener { onCollapse(item) }
        } else {
            holder.expand.visibility = View.GONE
            holder.expand.setOnClickListener(null)
        }

        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = item.isCompleted
        holder.title.text = item.name
        // 完了は薄くする。「下にまとめる」を切っている時はこれだけが手がかりになる
        holder.title.alpha = if (item.isCompleted) 0.5f else 1.0f

        // タイトル左にカレンダー色のドット（葉タスクのみ）
        if (!row.hasChildren) {
            val d = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                val s = (10 * density).toInt()
                setSize(s, s)
                setColor(CalColor.colorFor(item.color))
            }
            d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight)
            holder.title.setCompoundDrawablesRelative(d, null, null, null)
            holder.title.compoundDrawablePadding = (8 * density).toInt()
        } else {
            holder.title.setCompoundDrawablesRelative(null, null, null, null)
        }

        // 優先度。葉タスクだけ。親は配下がばらばらなので、まとめて出しても嘘になる
        if (row.hasChildren) {
            holder.priority.visibility = View.GONE
        } else {
            holder.priority.visibility = View.VISIBLE
            holder.priority.text = item.priority.toString()
            holder.priority.alpha = if (item.isCompleted) 0.4f else 1.0f
            val bg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 6 * density
                setColor(priorityColor(item.priority))
            }
            holder.priority.background = bg
        }

        holder.sub.visibility = View.VISIBLE
        holder.sub.text = when {
            // 畳んだ親は1行に要約する（件数・済み・合計時間）
            row.hasChildren && row.collapsed -> row.summary()
            row.hasChildren -> ""
            else -> leafSub(item)
        }
        if (holder.sub.text.isEmpty()) holder.sub.visibility = View.GONE

        // 子を持つタスクはチェック不可：葉タスクのみ完了できる
        if (row.hasChildren) {
            holder.check.visibility = View.INVISIBLE
        } else {
            holder.check.visibility = View.VISIBLE
            holder.check.setOnCheckedChangeListener { _, isChecked -> onToggle(item, isChecked) }
        }

        if (item.id == draggingId) {
            // つかんでいる間はボタンを隠す。指が当たって削除されるのを防ぐのと、
            // 1枚の板を持ち上げているように見せるため
            holder.addChild.visibility = View.GONE
            holder.delete.visibility = View.GONE
            holder.expand.visibility = View.GONE
            holder.check.isEnabled = false
            holder.textContainer.setOnClickListener(null)
            holder.textContainer.isClickable = false
        } else {
            holder.check.isEnabled = true
            holder.addChild.visibility = View.VISIBLE
            holder.delete.visibility = View.VISIBLE
            holder.addChild.setOnClickListener { onAddChild(item) }
            holder.delete.setOnClickListener { onDelete(item) }
            // 行のテキストをタップで編集。**親も編集できる**
            // （畳むのは行頭の▾。親を編集できないと、グループ名を直すことも
            //   配下へまとめて設定を配ることもできなかった）
            holder.textContainer.setOnClickListener { onEdit(item) }
        }
        // 長押しはItemTouchHelperがそのまま「つかむ」に使う。
        // ここでリスナを付けると長押しを食ってしまうので付けない。
    }

    private fun leafSub(item: HobbyItem): String {
        val h = item.durationMinutes / 60
        val m = item.durationMinutes % 60
        val dur = if (h > 0) "${h}時間${if (m > 0) "${m}分" else ""}" else "${m}分"
        val prio = when {
            item.priority >= 8 -> "高"
            item.priority <= 3 -> "低"
            else -> "中"
        }
        val base = StringBuilder("$dur ・ ${item.locationLabel()} ・ 優先$prio")
        val tags = Tags.display(item.tags)
        if (tags.isNotEmpty()) base.append("  $tags")
        if (item.note.isNotBlank()) {
            val memo = item.note.replace("\n", " ").let {
                if (it.length > 40) it.substring(0, 40) + "…" else it
            }
            base.append("\n📝 $memo")
        }
        return base.toString()
    }
}
