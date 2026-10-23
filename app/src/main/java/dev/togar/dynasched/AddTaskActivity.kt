package dev.togar.dynasched

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dev.togar.dynasched.data.AddDefaults
import dev.togar.dynasched.data.Repo
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.ui.ColorPaletteView
import dev.togar.dynasched.ui.DurationPickerView
import dev.togar.dynasched.ui.LabeledSlider

/**
 * タスク追加画面（Googleカレンダー風）。名前・必要時間・場所・優先度を入力。
 * parent_id を intent で渡すと子タスクとして追加する。
 *
 * 「繰り返す」を選ぶと習慣として保存する（決まった日にタスクを1件ずつ作る）。
 * 習慣は最上位にしか作らないので、子タスクを足す時は出さない。
 */
class AddTaskActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PARENT_ID = "parent_id"
        const val EXTRA_PARENT_NAME = "parent_name"
        /** 最初に選んでおく場所（「場所ごと」のタブから足した時） */
        const val EXTRA_LOCATION = "location"
        /** 最初から「毎日」にしておく（習慣で絞り込んでいる時に足した） */
        const val EXTRA_REPEAT = "repeat"
    }

    // 場所: 表示ラベル → 値。場所は設定で増やせるので開くたびに作る
    private val locationChoices by lazy { Places.taskChoices(Places.all(this)) }
    private val locationValues by lazy { locationChoices.map { it.first } }
    private val locationLabels by lazy { locationChoices.map { it.second } }

    /** 設定の「文字の大きさ」を効かせる */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(dev.togar.dynasched.FontScale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_task)

        val parentId = if (intent.hasExtra(EXTRA_PARENT_ID))
            intent.getLongExtra(EXTRA_PARENT_ID, -1L) else null
        val parentName = intent.getStringExtra(EXTRA_PARENT_NAME)

        val title = findViewById<TextView>(R.id.screenTitle)
        val parentLabel = findViewById<TextView>(R.id.parentLabel)
        val nameInput = findViewById<EditText>(R.id.nameInput)
        val locationSpinner = findViewById<Spinner>(R.id.locationSpinner)
        val prioritySpinner = findViewById<Spinner>(R.id.prioritySpinner)
        val noteInput = findViewById<EditText>(R.id.noteInput)
        val saveButton = findViewById<Button>(R.id.saveButton)

        val durationPicker = DurationPickerView(this, 30)
        findViewById<android.widget.FrameLayout>(R.id.durationContainer).addView(durationPicker)
        val colorPalette = ColorPaletteView(this, 0)
        findViewById<android.widget.FrameLayout>(R.id.colorContainer).addView(colorPalette)

        if (parentId != null && parentId >= 0) {
            title.text = "子タスクを追加"
            parentLabel.visibility = TextView.VISIBLE
            parentLabel.text = "親: ${parentName ?: ""}"
        }

        locationSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, locationLabels
        )
        // 家でやるものが大半なので「家のみ」を初期値にする。
        // 「どこでも」始まりだと、外の枠にも置ける前提で配置されてしまう
        locationSpinner.setSelection(locationValues.indexOf("home"))
        intent.getStringExtra(EXTRA_LOCATION)?.let { loc ->
            locationValues.indexOf(loc).takeIf { it >= 0 }?.let { locationSpinner.setSelection(it) }
        }
        // 優先度はスワイプで選ぶ（スピナーを隠して同じ位置にスライダーを差し込む）
        prioritySpinner.visibility = android.view.View.GONE
        // 段は設定の「優先度」で決めたもの。真ん中あたりから始める
        val prioritySlider = dev.togar.dynasched.ui.PriorityPicker(this, 5)
        val prioParent = prioritySpinner.parent as android.view.ViewGroup
        prioParent.addView(prioritySlider, prioParent.indexOfChild(prioritySpinner))

        // タグ欄はメモの直前に差し込む（レイアウトを触らずに済ませる）
        val tagInput = dev.togar.dynasched.ui.TagInputView(this, "") { knownTags }
        val noteParent = noteInput.parent as android.view.ViewGroup
        noteParent.addView(
            TextView(this).apply { text = "タグ"; textSize = 12f },
            noteParent.indexOfChild(noteInput)
        )
        noteParent.addView(tagInput, noteParent.indexOfChild(noteInput))

        val isChild = parentId != null && parentId >= 0
        val repeat = dev.togar.dynasched.ui.RepeatPickerView(
            this, if (intent.getBooleanExtra(EXTRA_REPEAT, false)) "daily" else null
        )
        if (!isChild) {
            noteParent.addView(
                TextView(this).apply { text = "繰り返す"; textSize = 12f },
                noteParent.indexOfChild(noteInput)
            )
            noteParent.addView(repeat, noteParent.indexOfChild(noteInput))
        }

        // 初期値は、その親（グループ）に**前回足した時の設定**。覚えが無ければ親の設定を写す。
        // グループ内のタスクは場所も優先度も揃っているのが普通で、毎回入れ直させると形骸化する。
        // 親の設定だけを写していた頃は、親が「家」のまま配下に「実家」で足し続けると、毎回「家」で始まっていた
        val groupId = if (parentId != null && parentId >= 0) parentId else null
        loadDefaults(groupId) { remembered, parent ->
            val d = remembered
                ?: parent?.let { AddDefaults(it.location, it.priority, it.color, it.tags, it.durationMinutes) }
            if (d != null) {
                locationSpinner.setSelection(locationValues.indexOf(d.location).let { if (it >= 0) it else 0 })
                prioritySlider.value = d.priority
                colorPalette.select(dev.togar.dynasched.api.CalColor.indexOfId(d.color))
                tagInput.setValue(d.tags)
                durationPicker.setMinutes(d.durationMinutes)
            }
            // 場所を指定して開かれていたら（「場所」で絞っていた時など）、それを最後に勝たせる
            intent.getStringExtra(EXTRA_LOCATION)?.let { loc ->
                locationValues.indexOf(loc).takeIf { it >= 0 }?.let { locationSpinner.setSelection(it) }
            }
        }

        saveButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "タスク名を入力してください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            var total = durationPicker.totalMinutes
            if (total <= 0) total = 30
            val location = locationValues[locationSpinner.selectedItemPosition]
            val priority = prioritySlider.value
            val note = noteInput.text.toString().trim()
            val color = dev.togar.dynasched.api.CalColor.idAt(colorPalette.selectedIndex)

            val kind = if (isChild) null else repeat.kind
            repeat.problem()?.takeIf { kind != null }?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            saveButton.isEnabled = false
            val ctx = applicationContext
            val pid = if (isChild) parentId else null
            Api.async(
                work = {
                    val repo = Repo.current(ctx)
                    if (kind == null) {
                        repo.addHobby(ctx, name, pid, total, priority, location, note, color, tagInput.value)
                        // 次にこの親へ足す時の初期値になる
                        AddDefaults.remember(ctx, pid, AddDefaults(location, priority, color, tagInput.value, total))
                    } else {
                        repo.saveRoutine(ctx, dev.togar.dynasched.data.Routine(
                            name = name, kind = kind, weekdays = repeat.weekdays,
                            dayOfMonth = repeat.dayOfMonth, durationMinutes = maxOf(5, total),
                            priority = priority, location = location, color = color,
                            tags = tagInput.value
                        ))
                        // 今日が当たる日なら、すぐ一覧に出す
                        if (repo.generateRoutines(ctx) > 0) {
                            dev.togar.dynasched.widget.SuggestWidgetProvider.updateAll(ctx)
                        }
                    }
                },
                onSuccess = {
                    Toast.makeText(this, if (kind == null) "追加しました" else "習慣にしました", Toast.LENGTH_SHORT).show()
                    finish()
                },
                onError = { e ->
                    saveButton.isEnabled = true
                    Toast.makeText(this, "追加に失敗: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    /** 「選ぶ」に出す既存タグ。読み込む前に押されても落ちないよう空で始める */
    private var knownTags: List<String> = emptyList()

    /**
     * 既存タグの読み込みと、初期値（前回この親に足した設定・親の設定）。
     * どちらもDBを触るのでワーカーへ回す。読み込む前に触られても落ちない形にしてある。
     */
    private fun loadDefaults(
        parentId: Long?,
        onDefaults: (remembered: AddDefaults?, parent: dev.togar.dynasched.api.HobbyItem?) -> Unit
    ) {
        val ctx = applicationContext
        Api.async(
            work = {
                val all = Repo.current(ctx).getHobby(ctx)
                Triple(
                    dev.togar.dynasched.ui.Tags.known(all),
                    AddDefaults.remembered(ctx, parentId),
                    all.firstOrNull { it.id == parentId }
                )
            },
            onSuccess = { (tags, remembered, parent) ->
                knownTags = tags
                onDefaults(remembered, parent)
            },
            onError = { /* 候補が出ないだけ。手で入れられる */ }
        )
    }
}
