package dev.togar.dynasched

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dev.togar.dynasched.data.Repo
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.ui.ColorPaletteView
import dev.togar.dynasched.ui.DurationPickerView
import dev.togar.dynasched.ui.LabeledSlider

/**
 * タスク追加画面（Googleカレンダー風）。名前・必要時間・場所・優先度を入力。
 * parent_id を intent で渡すと子タスクとして追加する。
 */
class AddTaskActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PARENT_ID = "parent_id"
        const val EXTRA_PARENT_NAME = "parent_name"
        /** 最初に選んでおく場所（「場所ごと」のタブから足した時） */
        const val EXTRA_LOCATION = "location"
    }

    // 場所: 表示ラベル → 値。場所は設定で増やせるので開くたびに作る
    private val locationChoices by lazy { Places.taskChoices(Places.all(this)) }
    private val locationValues by lazy { locationChoices.map { it.first } }
    private val locationLabels by lazy { locationChoices.map { it.second } }
    // 優先度: 表示ラベル → 値
    private val priorityValues = arrayOf(3, 5, 8)
    private val priorityLabels = arrayOf("低", "中", "高")

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
        val prioritySlider = LabeledSlider(this, priorityValues.toList(), 5) { v ->
            priorityLabels[priorityValues.toList().indexOf(v).coerceIn(0, 2)]
        }
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

        // 子タスクを足す時は、**親と同じ設定で始める**。
        // グループ内のタスクは場所も優先度も揃っているのが普通で、
        // 毎回入れ直させると結局そのまま既定値で入って設定が形骸化する。
        loadDefaults(if (parentId != null && parentId >= 0) parentId else null) { parent ->
            locationSpinner.setSelection(
                locationValues.indexOf(parent.location).let { if (it >= 0) it else 0 }
            )
            prioritySlider.value = parent.priority
            colorPalette.select(dev.togar.dynasched.api.CalColor.indexOfId(parent.color))
            tagInput.setValue(parent.tags)
            durationPicker.setMinutes(parent.durationMinutes)
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

            saveButton.isEnabled = false
            val ctx = applicationContext
            val pid = if (parentId != null && parentId >= 0) parentId else null
            Api.async(
                work = {
                    Repo.current(ctx)
                        .addHobby(ctx, name, pid, total, priority, location, note, color, tagInput.value)
                },
                onSuccess = {
                    Toast.makeText(this, "追加しました", Toast.LENGTH_SHORT).show()
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
     * 既存タグの読み込みと、親からの引き継ぎ。
     * どちらもDBを触るのでワーカーへ回す。読み込む前に触られても落ちない形にしてある。
     */
    private fun loadDefaults(
        parentId: Long?,
        onParent: (dev.togar.dynasched.api.HobbyItem) -> Unit
    ) {
        val ctx = applicationContext
        Api.async(
            work = {
                val all = Repo.current(ctx).getHobby(ctx)
                dev.togar.dynasched.ui.Tags.known(all) to all.firstOrNull { it.id == parentId }
            },
            onSuccess = { (tags, parent) ->
                knownTags = tags
                if (parent != null) onParent(parent)
            },
            onError = { /* 候補が出ないだけ。手で入れられる */ }
        )
    }
}
