package dev.togar.dynasched.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dev.togar.dynasched.Places
import dev.togar.dynasched.R
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.data.Repo
import dev.togar.dynasched.ui.Tags

/**
 * ウィジェット1枚ぶんの設定。置いた時（APPWIDGET_CONFIGURE）と、ウィジェットの⚙から開く。
 * 何枚でも置けて、枚ごとに場所・空き時間・タグ・見た目を変えられる。
 */
class WidgetConfigActivity : AppCompatActivity() {

    companion object {
        fun intent(ctx: Context, widgetId: Int): Intent =
            Intent(ctx, WidgetConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                // 枚ごとに別の画面として扱わせる（同じ Intent とみなされると前の枚の設定が開く）
                .setData(Uri.parse("skimas://widget-config/$widgetId"))
    }

    private var widgetId = 0
    private var tags: MutableSet<String> = HashSet()
    private var knownTags: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 戻るで閉じたら「置くのをやめた」ことになる（ホーム画面に置かれない）
        setResult(RESULT_CANCELED)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0) ?: 0
        if (widgetId == 0) {
            finish()
            return
        }
        title = "ウィジェットの設定"
        val cfg = WidgetPrefs.load(this, widgetId)
        tags = cfg.tags.toMutableSet()
        loadTags()

        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(ContextCompat.getColor(context, R.color.bg))
        }
        box.addView(TextView(this).apply {
            text = "この1枚だけの設定です。何枚でも置けて、枚ごとに別の条件にできます。"
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.on_bg))
        })

        fun label(s: String) = TextView(this).apply {
            text = s
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.on_bg_dim))
            setPadding(0, pad / 2, 0, 0)
        }
        fun spinner(items: List<String>, selected: Int) = Spinner(this).apply {
            adapter = ArrayAdapter(this@WidgetConfigActivity, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(selected.coerceIn(0, items.size - 1))
        }

        box.addView(label("今日の達成の見せ方"))
        val styles = ProgressStyle.entries
        val styleSpinner = spinner(styles.map { it.label }, styles.indexOf(cfg.style))
        box.addView(styleSpinner)

        // 場所は設定で増やせる。最後に「どこでも」
        box.addView(label("場所"))
        val places = Places.all(this)
        val locValues = places.map { it.id } + Places.ANYWHERE
        val locSpinner = spinner(places.map { it.name } + Places.ANYWHERE_LABEL,
            locValues.indexOf(cfg.loc).coerceAtLeast(0))
        box.addView(locSpinner)

        box.addView(label("空き時間"))
        val minValues = listOf(0, 15, 30, 45, 60, 90, 120)
        val minSpinner = spinner(listOf("次の予定まで（自動）", "15分", "30分", "45分", "60分", "90分", "120分"),
            minValues.indexOf(cfg.minutes).coerceAtLeast(0))
        box.addView(minSpinner)

        box.addView(label("下限（これより短い作業は出さない）"))
        val lowValues = listOf(0, 15, 30, 45, 60)
        val lowSpinner = spinner(listOf("下限なし", "15分以上", "30分以上", "45分以上", "60分以上"),
            lowValues.indexOf(cfg.minMinutes).coerceAtLeast(0))
        box.addView(lowSpinner)

        box.addView(label("出すもの"))
        val kinds = listOf("both", "hobby", "material")
        val kindSpinner = spinner(listOf("タスクと教材", "タスクだけ", "教材だけ"), kinds.indexOf(cfg.kind).coerceAtLeast(0))
        box.addView(kindSpinner)

        box.addView(label("優先度"))
        val prioValues = listOf(0, 5, 8)
        val prioSpinner = spinner(listOf("すべて", "中以上だけ", "高だけ"), prioValues.indexOf(cfg.minPriority).coerceAtLeast(0))
        box.addView(prioSpinner)

        box.addView(label("候補の行数"))
        val lineValues = listOf(0, 1, 2, 3)
        val lineSpinner = spinner(listOf("出さない（達成だけ）", "1行", "2行", "3行"),
            lineValues.indexOf(cfg.lines.coerceIn(0, 3)).coerceAtLeast(0))
        box.addView(lineSpinner)

        box.addView(label("タグで絞る"))
        val tagButton = Button(this).apply { text = tagLabel() }
        tagButton.setOnClickListener { pickTags(tagButton) }
        box.addView(tagButton)
        box.addView(TextView(this).apply {
            text = "タグを指定すると、教材は候補から外れます（教材にタグが無いため）。"
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.on_bg_dim))
        })

        box.addView(label("見出し（空なら自動）"))
        val titleInput = EditText(this).apply {
            setText(cfg.title)
            hint = "例：勉強用"
        }
        box.addView(titleInput)

        box.addView(Button(this).apply {
            text = "保存する"
            setPadding(0, pad / 2, 0, pad / 2)
            setOnClickListener {
                WidgetPrefs.save(this@WidgetConfigActivity, widgetId, WidgetConfig(
                    style = styles[styleSpinner.selectedItemPosition],
                    loc = locValues[locSpinner.selectedItemPosition],
                    minutes = minValues[minSpinner.selectedItemPosition],
                    minMinutes = lowValues[lowSpinner.selectedItemPosition],
                    tags = tags.toSet(),
                    kind = kinds[kindSpinner.selectedItemPosition],
                    minPriority = prioValues[prioSpinner.selectedItemPosition],
                    lines = lineValues[lineSpinner.selectedItemPosition],
                    title = titleInput.text.toString().trim()
                ))
                SuggestWidgetProvider.updateOne(applicationContext, widgetId)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                Toast.makeText(this@WidgetConfigActivity, "保存しました", Toast.LENGTH_SHORT).show()
                finish()
            }
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.bg))
            addView(box)
        })
    }

    private fun tagLabel(): String = if (tags.isEmpty()) "絞らない" else tags.joinToString(" ") { "#$it" }

    private fun loadTags() {
        val app = applicationContext
        Api.async({ Tags.known(Repo.current(app).getHobby(app)) }, { knownTags = it }, {})
    }

    private fun pickTags(button: Button) {
        val list = knownTags
        if (list.isEmpty()) {
            Toast.makeText(this, "まだタグがありません。タスクを編集して付けてください", Toast.LENGTH_LONG).show()
            return
        }
        val picked = tags.toMutableSet()
        AlertDialog.Builder(this)
            .setTitle("タグで絞る")
            .setMultiChoiceItems(list.toTypedArray(), BooleanArray(list.size) { list[it] in tags }) { _, i, on ->
                if (on) picked.add(list[i]) else picked.remove(list[i])
            }
            .setPositiveButton("決定") { _, _ ->
                tags = picked
                button.text = tagLabel()
            }
            .setNeutralButton("絞らない") { _, _ ->
                tags = HashSet()
                button.text = tagLabel()
            }
            .setNegativeButton("やめる", null)
            .show()
    }
}
