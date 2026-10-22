package dev.togar.dynasched.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import dev.togar.dynasched.Places
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.Priorities
import dev.togar.dynasched.R
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.data.Repo

/**
 * タスクの絞り込みの画面。検索と、習慣・場所・優先度・タグの条件。
 *
 * 前はチップを押すたびに小さな画面が出て「条件なし／やめる／絞り込む」の3つから選ばせていた。
 * どれが何をするのか分かりにくかったので、**選ぶのはこの画面の中で、押すのは「絞り込む」だけ**にした。
 * 何も選ばなければ、その条件は無し。戻る（←）で閉じれば何も変えない。
 *
 * 条件どうしは掛け合わせ、1つの条件の中は「または」（[TaskFilter]）。
 * グループはここではなく一覧のタブで選ぶ。
 */
class TaskFilterPage : Fragment() {

    companion object {
        /** 「絞り込む」を押した知らせ。中身は Prefs に書いてある */
        const val RESULT = "task_filter"
    }

    private var habit = TaskFilter.Habit.ALL
    private val places = HashSet<String>()
    private val priorities = HashSet<Int>()
    private val tags = HashSet<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_task_filter, container, false)
        val ctx = requireContext()
        val now = Prefs.taskFilter(ctx)
        habit = now.habit
        places.addAll(now.places)
        priorities.addAll(now.priorities)
        tags.addAll(now.tags)

        val body = root.findViewById<LinearLayout>(R.id.filterBody)
        val textInput = root.findViewById<EditText>(R.id.filterText)
        textInput.setText(now.text)

        val habitGroup = section(body, "習慣")
        fun drawHabit() {
            habitGroup.removeAllViews()
            for (h in TaskFilter.Habit.entries) {
                habitGroup.addView(chip(h.label, habit == h) { habit = h; drawHabit() })
            }
        }
        drawHabit()

        val placeGroup = section(body, "場所（いくつでも）")
        for ((id, label) in Places.taskChoices(Places.all(ctx))) {
            placeGroup.addView(toggle(label, places, id))
        }

        val prioGroup = section(body, "優先度（いくつでも）")
        for (l in Priorities.all(ctx)) prioGroup.addView(toggle(l.label, priorities, l.value))

        val tagGroup = section(body, "タグ（いくつでも）")
        val app = ctx.applicationContext
        Api.async({ Tags.known(Repo.current(app).getHobby(app)) }, { known ->
            if (!isAdded) return@async
            // 消えたタグで絞ったままだと何も出なくなるので、選べる物に今のものを足しておく
            val all = known + tags.filter { it !in known }
            if (all.isEmpty()) {
                tagGroup.addView(TextView(requireContext()).apply {
                    text = "まだタグがありません"; textSize = 12f
                    setTextColor(requireContext().getColor(R.color.on_bg_dim))
                })
            }
            for (t in all) tagGroup.addView(toggle("#$t", tags, t))
        }, { })

        root.findViewById<View>(R.id.filterBack).setOnClickListener { parentFragmentManager.popBackStack() }
        root.findViewById<View>(R.id.filterApply).setOnClickListener {
            Prefs.setTaskFilter(ctx, TaskFilter(
                text = textInput.text.toString().trim(), habit = habit,
                places = places.toSet(), priorities = priorities.toSet(), tags = tags.toSet()
            ))
            parentFragmentManager.setFragmentResult(RESULT, bundleOf())
            parentFragmentManager.popBackStack()
        }
        return root
    }

    private fun section(body: LinearLayout, title: String): ChipGroup {
        val ctx = requireContext()
        body.addView(TextView(ctx).apply {
            text = title
            textSize = 13f
            setTextColor(ctx.getColor(R.color.on_bg_dim))
            setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
        })
        val g = ChipGroup(ctx)
        body.addView(g)
        return g
    }

    /** 押すたびに入れたり外したりするチップ */
    private fun <T> toggle(label: String, set: MutableSet<T>, value: T): Chip {
        lateinit var c: Chip
        c = chip(label, value in set) {
            if (!set.add(value)) set.remove(value)
            paint(c, value in set)
        }
        return c
    }

    private fun chip(label: String, on: Boolean, onClick: () -> Unit) = Chip(requireContext()).apply {
        text = label
        isCheckable = false
        chipStrokeWidth = 0f
        setTextColor(requireContext().getColor(R.color.white))
        paint(this, on)
        setOnClickListener { onClick() }
    }

    private fun paint(c: Chip, on: Boolean) {
        c.chipBackgroundColor = ColorStateList.valueOf(
            requireContext().getColor(if (on) R.color.primary else R.color.surface_variant)
        )
    }
}
