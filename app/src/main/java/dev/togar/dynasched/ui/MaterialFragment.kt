package dev.togar.dynasched.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import dev.togar.dynasched.R
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.api.CalColor
import dev.togar.dynasched.data.MaterialInput
import dev.togar.dynasched.data.Repo
import dev.togar.dynasched.api.MaterialItem
import dev.togar.dynasched.api.PlanItem
import dev.togar.dynasched.api.PlanResult
import java.util.Calendar
import java.util.Locale

/**
 * 教材画面。goals の置き換え。
 *
 * 聞くのは**数えられるもの**だけ（総問数・応用の番号・目標周回・試験日）。
 * 総時間・進捗%・難易度・理解度の入力欄は無い。どれも正直に答えられない質問で、
 * 適当に答えた数字から出た予定は自分が一番信用できないため。
 */
class MaterialFragment : Fragment() {

    private lateinit var adapter: MaterialAdapter
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var empty: TextView
    private lateinit var planSummary: TextView

    private var materials: List<MaterialItem> = emptyList()

    private val studyTypeValues = arrayOf(MaterialItem.TYPE_EXERCISE, MaterialItem.TYPE_MEMORIZE)
    private val studyTypeLabels = arrayOf("演習（昼に置かれやすい）", "暗記（夜に置かれやすい）")
    private val prioValues = arrayOf(3, 5, 8)
    private val prioLabels = arrayOf("低", "中", "高")
    private val sessionValues = arrayOf(25, 40, 50, 60, 90)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_material, container, false)

        empty = root.findViewById(R.id.emptyText)
        swipe = root.findViewById(R.id.swipeRefresh)
        planSummary = root.findViewById(R.id.planSummary)
        val recycler = root.findViewById<RecyclerView>(R.id.recycler)
        val addButton = root.findViewById<Button>(R.id.addMaterialButton)

        adapter = MaterialAdapter(
            onEdit = { m -> showMaterialDialog(m, null) },
            onRecord = { m -> showRecordDialog(m) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        addButton.setOnClickListener { showMaterialDialog(null, null) }
        swipe.setOnRefreshListener { load() }
        return root
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    /** 一覧と計画をまとめて取る。計画が取れなくても一覧は出す。 */
    private fun load() {
        swipe.isRefreshing = true
        val ctx = requireContext().applicationContext
        Api.async(
            work = {
                val list = Repo.current(ctx).getMaterials(ctx)
                val plan = try { Repo.current(ctx).getPlan(ctx) } catch (e: Exception) { null }
                Pair(list, plan)
            },
            onSuccess = { (list, plan) ->
                if (!isAdded) return@async
                swipe.isRefreshing = false
                materials = list
                adapter.submit(list, plan?.items ?: emptyList())
                empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                showPlanSummary(plan)
            },
            onError = { e ->
                if (!isAdded) return@async
                swipe.isRefreshing = false
                empty.visibility = View.VISIBLE
                empty.text = "読み込みエラー: ${Api.friendlyMessage(e)}"
            }
        )
    }

    /**
     * 全体の総括。カレンダー同期がどこまで届いているかも書く。
     * 推定が混ざっていることを隠すと、数字を信用する根拠が無くなる。
     */
    private fun showPlanSummary(plan: PlanResult?) {
        if (plan == null || plan.items.isEmpty()) {
            planSummary.visibility = View.GONE
            return
        }
        val ng = plan.items.count { !it.ok }
        val head = if (ng == 0) "全部間に合います" else "$ng 件が間に合いません"
        val sb = StringBuilder(head)
        if (plan.knownDays > 0 && plan.calendarHorizon.isNotEmpty()) {
            sb.append("\nカレンダーは ${plan.calendarHorizon} まで同期済み。")
            sb.append("それ以降は1日 ${plan.estimatedPerDay} 分として概算しています")
        }
        if (plan.hobbyMinutes > 0) {
            sb.append("\n単発タスクに ${PlanItem.hm(plan.hobbyMinutes)} を先に確保しています")
        }
        planSummary.text = sb.toString()
        planSummary.visibility = View.VISIBLE
    }

    /** 実績の記録。聞くのは「何問やった？」だけ。 */
    private fun showRecordDialog(m: MaterialItem) {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        layout.addView(TextView(ctx).apply {
            text = "${m.progressLabel()}（残り ${m.remainingProblems}問）"
            textSize = 12f
        })
        val problemsInput = EditText(ctx).apply {
            hint = "何問やった？"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(problemsInput)
        layout.addView(TextView(ctx).apply {
            text = "かかった時間（分）"
            textSize = 12f
            setPadding(0, pad / 2, 0, 0)
        })
        val minutesInput = EditText(ctx).apply {
            setText(m.sessionMinutes.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(minutesInput)

        AlertDialog.Builder(ctx)
            .setTitle("${m.name} の記録")
            .setView(ScrollView(ctx).apply { addView(layout) })
            .setPositiveButton("記録") { _, _ ->
                val p = problemsInput.text.toString().trim().toIntOrNull()
                if (p == null || p <= 0) {
                    Toast.makeText(ctx, "問数を入れてください", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val min = minutesInput.text.toString().trim().toIntOrNull() ?: m.sessionMinutes
                val app = ctx.applicationContext
                Api.async(
                    work = { Repo.current(app).recordAttempt(app, m.id, p, min) },
                    onSuccess = { if (isAdded) { Toast.makeText(ctx, "${p}問を記録しました", Toast.LENGTH_SHORT).show(); load() } },
                    onError = { e -> if (isAdded) Toast.makeText(ctx, "記録に失敗: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show() }
                )
            }
            .setNeutralButton("直前を取り消す") { _, _ ->
                val app = ctx.applicationContext
                Api.async(
                    work = { Repo.current(app).undoAttempt(app, m.id) },
                    onSuccess = { if (isAdded) { Toast.makeText(ctx, "取り消しました", Toast.LENGTH_SHORT).show(); load() } },
                    onError = { e -> if (isAdded) Toast.makeText(ctx, "取り消しに失敗: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show() }
                )
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    /**
     * 教材の追加/編集。
     * @param existing 非nullなら編集
     * @param presetDate 新規追加時の初期試験日
     */
    private fun showMaterialDialog(existing: MaterialItem?, presetDate: Calendar?) {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val cal = Calendar.getInstance()
        if (existing != null) existing.deadlineDate()?.let { cal.time = it }
        else if (presetDate != null) cal.time = presetDate.time
        else { cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59) }

        // 中間期限（1周目の締切）。既定は試験日の5日前。
        val firstCal = Calendar.getInstance().apply {
            time = cal.time
            if (existing != null && existing.firstRoundDeadline.isNotEmpty()) {
                runCatching {
                    MaterialItem.from(org.json.JSONObject().put("deadline", existing.firstRoundDeadline))
                        .deadlineDate()?.let { time = it }
                }
            } else {
                add(Calendar.DAY_OF_MONTH, -5)
            }
        }
        var useFirstRound = existing?.firstRoundDeadline?.isNotEmpty() ?: false

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun label(t: String) = TextView(ctx).apply {
            text = t; textSize = 12f; setPadding(0, pad / 2, 0, 0)
        }

        val subjectInput = EditText(ctx).apply {
            hint = "教科（例：物理）"
            setText(existing?.subject ?: "")
        }
        layout.addView(subjectInput)

        val nameInput = EditText(ctx).apply {
            hint = "教材名（例：ワーク）"
            setText(existing?.name ?: "")
        }
        layout.addView(nameInput)

        layout.addView(label("総問数（試験範囲の問題数）"))
        val totalInput = EditText(ctx).apply {
            setText(existing?.totalProblems?.takeIf { it > 0 }?.toString() ?: "")
            hint = "120"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(totalInput)

        layout.addView(label("応用の番号（足りない時ここから自動で外す）"))
        val advancedInput = EditText(ctx).apply {
            setText(existing?.advancedRanges ?: "")
            hint = "19-22, 45-48, 71-75"
        }
        layout.addView(advancedInput)

        layout.addView(label("目標周回（浅く何回も）"))
        val roundsSlider = LabeledSlider(ctx, (1..5).toList(), existing?.targetRounds ?: 3) { "${it}周" }
        layout.addView(roundsSlider)

        layout.addView(label("試験日"))
        val dateBtn = Button(ctx)
        fun refreshDateBtn() {
            dateBtn.text = String.format(
                Locale.JAPAN, "%04d/%02d/%02d",
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
            )
        }
        refreshDateBtn()
        dateBtn.setOnClickListener {
            DatePickerDialog(ctx, { _, y, mo, d ->
                cal.set(Calendar.YEAR, y); cal.set(Calendar.MONTH, mo); cal.set(Calendar.DAY_OF_MONTH, d)
                refreshDateBtn()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        layout.addView(dateBtn)

        // 中間期限。過去問を間に合わせるための「本命の締切」。
        val firstSwitch = android.widget.Switch(ctx).apply {
            text = "1周目の締切を別に決める"
            textSize = 13f
            isChecked = useFirstRound
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        layout.addView(firstSwitch)
        val firstBtn = Button(ctx)
        fun refreshFirstBtn() {
            firstBtn.text = String.format(
                Locale.JAPAN, "1周目まで %04d/%02d/%02d",
                firstCal.get(Calendar.YEAR), firstCal.get(Calendar.MONTH) + 1, firstCal.get(Calendar.DAY_OF_MONTH)
            )
        }
        refreshFirstBtn()
        firstBtn.visibility = if (useFirstRound) View.VISIBLE else View.GONE
        firstBtn.setOnClickListener {
            DatePickerDialog(ctx, { _, y, mo, d ->
                firstCal.set(Calendar.YEAR, y); firstCal.set(Calendar.MONTH, mo); firstCal.set(Calendar.DAY_OF_MONTH, d)
                refreshFirstBtn()
            }, firstCal.get(Calendar.YEAR), firstCal.get(Calendar.MONTH), firstCal.get(Calendar.DAY_OF_MONTH)).show()
        }
        layout.addView(firstBtn)
        firstSwitch.setOnCheckedChangeListener { _, checked ->
            useFirstRound = checked
            firstBtn.visibility = if (checked) View.VISIBLE else View.GONE
        }

        // 前提条件（例: 過去問はワークが1周終わるまで出さない）
        layout.addView(label("前提（これが1周終わるまで出さない）"))
        val prereqOptions = ArrayList<Pair<Long?, String>>()
        prereqOptions.add(Pair(null, "なし"))
        for (o in materials) if (o.id != existing?.id) prereqOptions.add(Pair(o.id, o.name))
        val prereqSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, prereqOptions.map { it.second })
            val idx = prereqOptions.indexOfFirst { it.first == existing?.prereqMaterialId }
            setSelection(if (idx >= 0) idx else 0)
        }
        layout.addView(prereqSpinner)

        layout.addView(label("種別"))
        val typeSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, studyTypeLabels)
            val idx = studyTypeValues.indexOf(existing?.studyType ?: MaterialItem.TYPE_EXERCISE)
            setSelection(if (idx >= 0) idx else 0)
        }
        layout.addView(typeSpinner)

        // 場所は単発タスクと同じく1件ずつ決める。以前の「必要なもの」は、
        // 何も要らなければどこでも・机などが要れば家、として初期値にだけ使う
        layout.addView(label("場所"))
        val placeChoices = dev.togar.dynasched.Places.taskChoices(dev.togar.dynasched.Places.all(ctx))
        val placeSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                placeChoices.map { it.second })
            // 新しい教材は家（演習は机が要るものが大半）
            val cur = existing?.location?.ifEmpty { null } ?: dev.togar.dynasched.Places.HOME
            val idx = placeChoices.indexOfFirst { it.first == cur }
            setSelection(if (idx >= 0) idx else 0)
        }
        layout.addView(placeSpinner)

        val examSwitch = android.widget.Switch(ctx).apply {
            text = "定期テスト（テスト期間中はこれ優先）"
            textSize = 13f
            isChecked = existing?.isExam ?: false
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        layout.addView(examSwitch)

        layout.addView(label("1コマの長さ"))
        val sessionSlider = LabeledSlider(ctx, sessionValues.toList(), existing?.sessionMinutes ?: 50) { "${it}分" }
        layout.addView(sessionSlider)

        layout.addView(label("優先度"))
        val prioSlider = LabeledSlider(ctx, prioValues.toList(), existing?.priority ?: 5) { v ->
            prioLabels[prioValues.indexOf(v).coerceIn(0, prioLabels.size - 1)]
        }
        layout.addView(prioSlider)

        layout.addView(label("カレンダーの色"))
        val colorPalette = ColorPaletteView(ctx, colorIndexForHex(existing?.color))
        layout.addView(colorPalette)

        layout.addView(label("メモ"))
        val memoInput = EditText(ctx).apply {
            setText(existing?.memo ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        layout.addView(memoInput)

        val builder = AlertDialog.Builder(ctx)
            .setTitle(if (existing == null) "教材を追加" else "教材を編集")
            .setView(ScrollView(ctx).apply { addView(layout) })
            // 中身は下の setOnShowListener で処理する。理由はそこに書いてある
            .setPositiveButton(if (existing == null) "追加" else "保存", null)
            .setNegativeButton("キャンセル", null)
        if (existing != null) builder.setNeutralButton("削除") { _, _ -> confirmDelete(existing) }

        val dialog = builder.create()
        // AlertDialog のボタンは**押した時点で必ず閉じる**。
        // 入力漏れで閉じられると、ここまで入れた内容が全部消えて一からやり直しになる。
        // show() の後にリスナーを差し替えると、閉じるかどうかを自分で決められる。
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(ctx, "教材名を入力してください", Toast.LENGTH_SHORT).show()
                    nameInput.requestFocus()
                    return@setOnClickListener      // 閉じない
                }
                val total = totalInput.text.toString().trim().toIntOrNull() ?: 0
                if (total <= 0) {
                    Toast.makeText(ctx, "総問数を入力してください", Toast.LENGTH_SHORT).show()
                    totalInput.requestFocus()
                    return@setOnClickListener      // 閉じない
                }
                save(
                    existing?.id,
                    subjectInput.text.toString().trim(),
                    name, total,
                    advancedInput.text.toString().trim(),
                    roundsSlider.value,
                    fmt(cal), if (useFirstRound) fmt(firstCal) else "",
                    prereqOptions[prereqSpinner.selectedItemPosition].first,
                    studyTypeValues[typeSpinner.selectedItemPosition],
                    existing?.needs ?: MaterialItem.NEEDS_NONE,
                    placeChoices[placeSpinner.selectedItemPosition].first,
                    sessionSlider.value, prioSlider.value,
                    CalColor.hexFor(CalColor.idAt(colorPalette.selectedIndex)),
                    memoInput.text.toString().trim(),
                    examSwitch.isChecked
                )
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun fmt(c: Calendar) = String.format(
        Locale.US, "%04d-%02d-%02dT%02d:%02d:00",
        c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
        c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)
    )

    private fun save(
        id: Long?, subject: String, name: String, total: Int, advanced: String, rounds: Int,
        deadline: String, firstDeadline: String, prereq: Long?, studyType: String, needs: String,
        location: String,
        session: Int, priority: Int, color: String, memo: String, isExam: Boolean
    ) {
        val ctx = requireContext().applicationContext
        val input = MaterialInput(
            subject = subject, name = name, totalProblems = total, advancedRanges = advanced,
            targetRounds = rounds, deadline = deadline, firstRoundDeadline = firstDeadline,
            prereqMaterialId = prereq, studyType = studyType, needs = needs,
            sessionMinutes = session, priority = priority, color = color, memo = memo, isExam = isExam,
            location = location
        )
        Api.async(
            work = {
                if (id == null) Repo.current(ctx).addMaterial(ctx, input)
                else Repo.current(ctx).editMaterial(ctx, id, input)
            },
            onSuccess = {
                if (!isAdded) return@async
                Toast.makeText(requireContext(), if (id == null) "追加しました" else "更新しました", Toast.LENGTH_SHORT).show()
                load()
            },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "保存に失敗: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show()
            }
        )
    }

    /** 色は HEX 保存。CalColor の近似HEXと一致するものを選ぶ（無ければ既定=0）。 */
    private fun colorIndexForHex(hex: String?): Int {
        if (hex.isNullOrEmpty()) return 0
        val i = CalColor.items.indexOfFirst { it.hex.equals(hex, ignoreCase = true) }
        return if (i >= 0) i else 0
    }

    private fun confirmDelete(m: MaterialItem) {
        AlertDialog.Builder(requireContext())
            .setMessage("「${m.name}」を削除しますか？記録した実績も消えます。")
            .setPositiveButton("削除") { _, _ ->
                val ctx = requireContext().applicationContext
                Api.async(
                    work = { Repo.current(ctx).deleteMaterial(ctx, m.id) },
                    onSuccess = { if (isAdded) load() },
                    onError = { e ->
                        if (!isAdded) return@async
                        Toast.makeText(requireContext(), "削除に失敗: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show()
                    }
                )
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }
}
