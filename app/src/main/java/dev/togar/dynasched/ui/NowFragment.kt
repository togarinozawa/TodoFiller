package dev.togar.dynasched.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import dev.togar.dynasched.Places
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.Priorities
import dev.togar.dynasched.R
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.api.SuggestItem
import dev.togar.dynasched.data.NowTasks
import dev.togar.dynasched.data.Repo
import dev.togar.dynasched.data.Want
import dev.togar.dynasched.widget.SuggestWidgetProvider

/**
 * いまやること。いまの場所と空き時間でできるものを1つ言い切り、下に次の候補を並べる。
 *
 * もとはタスク画面の上の欄だった。一覧を見る時と「何をやるか言ってほしい」時は
 * 別の気分なので、タブを分けた。
 *
 * 選び方は [NowTasks]。ドパチルの警告画面が読みに来るものと同じ。
 * タスク画面の絞り込みは見ない（配置の絞り込みだけ見る）。
 */
class NowFragment : Fragment() {

    private lateinit var placeButton: Button
    private lateinit var timeButton: Button
    private lateinit var placeFilterText: TextView
    private lateinit var card: View
    private lateinit var parentText: TextView
    private lateinit var titleText: TextView
    private lateinit var metaText: TextView
    private lateinit var buttons: View
    private lateinit var nextButton: Button
    private lateinit var othersTitle: TextView
    private lateinit var others: LinearLayout

    private var now: NowTasks.Now? = null
    private var index = 0
    /** 手で決めた時間。この画面にいる間だけ（次の予定までの時間は刻々と変わるので覚えない） */
    private var minutesOverride: Int? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_now, container, false)
        placeButton = root.findViewById(R.id.nowPlace)
        timeButton = root.findViewById(R.id.nowTime)
        placeFilterText = root.findViewById(R.id.nowPlaceFilter)
        card = root.findViewById(R.id.nowCard)
        parentText = root.findViewById(R.id.nowParent)
        titleText = root.findViewById(R.id.nowTitle)
        metaText = root.findViewById(R.id.nowMeta)
        buttons = root.findViewById(R.id.nowButtons)
        nextButton = root.findViewById(R.id.nowNext)
        othersTitle = root.findViewById(R.id.nowOthersTitle)
        others = root.findViewById(R.id.nowOthers)

        placeButton.setOnClickListener { pickPlace() }
        timeButton.setOnClickListener { pickMinutes() }
        titleText.setOnClickListener { showDetail() }
        parentText.setOnClickListener { showDetail() }
        root.findViewById<Button>(R.id.nowDetail).setOnClickListener { showDetail() }
        root.findViewById<Button>(R.id.nowDo).setOnClickListener { doIt() }
        nextButton.setOnClickListener {
            val n = now?.items?.size ?: 0
            if (n > 1) { index = (index + 1) % n; render() }
        }
        return root
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val app = requireContext().applicationContext
        val override = minutesOverride
        Api.async({ NowTasks.pick(app, Prefs.placeFilter(app), override) }, { next ->
            if (!isAdded) return@async
            // 同じ候補がまだあれば、「ほかの」で送った位置を保つ
            val cur = now?.items?.getOrNull(index)
            val keep = cur?.let { c -> next.items.indexOfFirst { it.id == c.id && it.kind == c.kind } } ?: -1
            now = next
            index = if (keep >= 0) keep else 0
            render()
        }, { e ->
            if (isAdded) Toast.makeText(requireContext(), Api.friendlyMessage(e), Toast.LENGTH_SHORT).show()
        })
    }

    private fun render() {
        val ctx = requireContext()
        val n = now ?: return
        placeButton.text = "📍 ${Places.name(Places.all(ctx), n.place)} ▾"
        timeButton.text = "⏱ ${n.minutes}分 ▾"
        val pf = Prefs.placeFilter(ctx)
        placeFilterText.visibility = if (pf == null) View.GONE else View.VISIBLE
        placeFilterText.text = "タスクは「配置の絞り込み」に当たるものだけ出しています"

        val item = n.items.getOrNull(index)
        if (item == null) {
            parentText.visibility = View.GONE
            titleText.text = "いまできるものはありません。休みましょう"
            metaText.text = "場所か時間を変えると、出てくるかもしれません"
            buttons.visibility = View.GONE
            othersTitle.visibility = View.GONE
            others.removeAllViews()
            return
        }
        val parent = if (item.kind == "hobby") n.parents[item.id].orEmpty() else ""
        parentText.text = "$parent ›"
        parentText.visibility = if (parent.isEmpty()) View.GONE else View.VISIBLE
        titleText.text = (if (item.kind == "material") "🎯 " else "") + item.title
        metaText.text = meta(item)
        buttons.visibility = View.VISIBLE
        nextButton.visibility = if (n.items.size > 1) View.VISIBLE else View.GONE
        nextButton.text = "ほかの ${index + 1}/${n.items.size}"

        // 次の候補。押すとそれを上に出す
        others.removeAllViews()
        val rest = n.items.withIndex().filter { it.index != index }.take(5)
        othersTitle.visibility = if (rest.isEmpty()) View.GONE else View.VISIBLE
        val d = resources.displayMetrics.density
        for ((i, s) in rest) {
            val p = if (s.kind == "hobby") n.parents[s.id]?.let { "$it › " }.orEmpty() else ""
            others.addView(TextView(ctx).apply {
                text = "${if (s.kind == "material") "🎯 " else ""}$p${s.title}（${s.minutes}分）"
                textSize = 14f
                setTextColor(ctx.getColor(R.color.on_bg))
                setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
                setOnClickListener { index = i; render() }
            })
        }
    }

    /** 時間・優先度（目安つき）・やる日 */
    private fun meta(item: SuggestItem): String {
        val n = now ?: return ""
        val parts = arrayListOf("${item.minutes}分", NowTasks.reason(item, Priorities.all(requireContext())))
        n.tasks[item.id]?.takeIf { item.kind == "hobby" }?.let { t ->
            Want.badge(t.wantDate, t.wantLevel).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        }
        return parts.joinToString(" ・ ")
    }

    /** 親・メモ・タグなどをまとめて見せる。ここから片付けることもできる */
    private fun showDetail() {
        val ctx = requireContext()
        val n = now ?: return
        val item = n.items.getOrNull(index) ?: return
        val task = n.tasks[item.id]?.takeIf { item.kind == "hobby" }
        if (task == null) {
            AlertDialog.Builder(ctx)
                .setTitle(item.title)
                .setMessage("予定（教材）・${item.minutes}分\n\n詳しくは予定タブで見られます。")
                .setPositiveButton("やる") { _, _ -> doIt() }
                .setNegativeButton("閉じる", null)
                .show()
            return
        }
        val places = Places.all(ctx)
        val msg = buildString {
            n.parents[task.id]?.let { append("親: ").append(it).append("\n") }
            append("時間: ").append(task.durationMinutes).append("分\n")
            append("場所: ").append(task.locationLabel(places)).append("\n")
            append("優先度: ").append(Priorities.levelOf(Priorities.all(ctx), task.priority).label).append("\n")
            Tags.display(task.tags).takeIf { it.isNotEmpty() }?.let { append("タグ: ").append(it).append("\n") }
            Want.badge(task.wantDate, task.wantLevel).takeIf { it.isNotEmpty() }?.let { append(it).append("\n") }
            if (task.note.isNotBlank()) append("\n📝 ").append(task.note.trim())
        }.trimEnd()
        AlertDialog.Builder(ctx)
            .setTitle(task.name)
            .setMessage(msg)
            .setPositiveButton("やる") { _, _ -> doIt() }
            .setNeutralButton("もう片付けた") { _, _ -> complete(task.id, task.name) }
            .setNegativeButton("閉じる", null)
            .show()
    }

    /** 決めたら、終わっているはずの時刻に確認を出す（「暇なとき」と同じ） */
    private fun doIt() {
        val item = now?.items?.getOrNull(index) ?: return
        FreeTimeDialog.decide(requireActivity(), item, now?.minutes ?: item.minutes)
    }

    private fun complete(id: Long, name: String) {
        val app = requireContext().applicationContext
        Api.async({ Repo.current(app).setHobbyCompleted(app, id, true) }, {
            SuggestWidgetProvider.updateAll(app)
            if (!isAdded) return@async
            Toast.makeText(requireContext(), "「$name」を片付けました", Toast.LENGTH_SHORT).show()
            refresh()
        }, { e ->
            if (isAdded) Toast.makeText(requireContext(), Api.friendlyMessage(e), Toast.LENGTH_LONG).show()
        })
    }

    /** いまいる場所を変える。今日いっぱいは曜日のいつもの場所より優先する */
    private fun pickPlace() {
        val ctx = requireContext()
        val places = Places.all(ctx)
        val cur = places.indexOfFirst { it.id == Prefs.widgetLoc(ctx) }
        AlertDialog.Builder(ctx)
            .setTitle("いまどこにいますか")
            .setSingleChoiceItems(places.map { it.name }.toTypedArray(), cur) { d, i ->
                d.dismiss()
                Prefs.setWidgetLoc(ctx, places[i].id)
                refresh()
            }
            .show()
    }

    private fun pickMinutes() {
        val ctx = requireContext()
        val auto = NowTasks.availableMinutes(ctx)
        val values = listOf<Int?>(null, 15, 30, 45, 60, 90, 120)
        val labels = values.map { if (it == null) "次の予定まで（${auto}分）" else "${it}分" }
        AlertDialog.Builder(ctx)
            .setTitle("どれくらい時間がありますか")
            .setSingleChoiceItems(labels.toTypedArray(), values.indexOf(minutesOverride).coerceAtLeast(0)) { d, i ->
                d.dismiss()
                minutesOverride = values[i]
                refresh()
            }
            .show()
    }
}
