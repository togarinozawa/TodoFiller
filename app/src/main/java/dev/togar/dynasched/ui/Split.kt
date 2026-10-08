package dev.togar.dynasched.ui

import android.content.Context
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import dev.togar.dynasched.R
import dev.togar.dynasched.api.HobbyItem

/** 分けた後の子タスク1件 */
data class SplitPart(val name: String, val minutes: Int)

/**
 * 大きいタスクを子タスクに分ける。止まっているタスクは、たいてい
 * 「次に何をするか」が決まっていないので、手順に割ると動き出す。
 */
object Split {
    const val MAX_PARTS = 20
    const val MIN_MINUTES = 5

    private val MINUTES_AT_END = Regex("(\\d{1,3})\\s*(分|m|min)$")

    /**
     * 1行1件で読む。行末に「30分」とあればその時間、無ければ残りの時間を等分する。
     * 箇条書きの印（- ・ * □ ☐）は外す。
     */
    fun fromLines(text: String, parentMinutes: Int): List<SplitPart> {
        val lines = text.split("\n").map { clean(it) }.filter { it.isNotEmpty() }.take(MAX_PARTS)
        if (lines.isEmpty()) return emptyList()
        val given = lines.map { minutesIn(it) }
        val unknown = given.count { it == null }
        // 時間の書いていない行で、親の残りを分け合う（切り上げ）
        val each = if (unknown <= 0) 0
            else maxOf(MIN_MINUTES, (parentMinutes - given.filterNotNull().sum() + unknown - 1) / unknown)
        return lines.mapIndexed { i, l -> SplitPart(nameOf(l), given[i] ?: each) }
    }

    /** [chunk] 分ずつに割る */
    fun byChunk(item: HobbyItem, chunk: Int): List<SplitPart> {
        val c = maxOf(chunk, MIN_MINUTES)
        return byCount(item, ((maxOf(item.durationMinutes, c) + c - 1) / c).coerceIn(1, MAX_PARTS))
    }

    /** [count] 個に割る。端数は最後に寄せる */
    fun byCount(item: HobbyItem, count: Int): List<SplitPart> {
        val n = count.coerceIn(1, MAX_PARTS)
        val total = maxOf(item.durationMinutes, n * MIN_MINUTES)
        val each = maxOf(MIN_MINUTES, total / n)
        return (1..n).map { i ->
            SplitPart("${item.name} $i/$n", if (i == n) maxOf(MIN_MINUTES, total - (n - 1) * each) else each)
        }
    }

    private fun clean(line: String): String =
        line.trim().removePrefix("-").removePrefix("・").removePrefix("*").removePrefix("□").removePrefix("☐").trim()

    private fun minutesIn(line: String): Int? =
        MINUTES_AT_END.find(line)?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(MIN_MINUTES)

    private fun nameOf(line: String): String = MINUTES_AT_END.replace(line, "").trim().ifEmpty { line }
}

/** 「分ける」を押した時の入力 */
object SplitDialog {

    fun show(ctx: Context, item: HobbyItem, onCreate: (List<SplitPart>) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val reason = Stuck.reason(item, java.time.LocalDate.now())
        val howTo = "手順を1行ずつ書くと、そのぶんが子タスクになります。"
        box.addView(TextView(ctx).apply {
            text = if (reason.isEmpty()) howTo else "$reason。$howTo"
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.on_bg_dim))
        })
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            hint = "例:\n下書き\n図を描く 30分\n仕上げ"
        }
        box.addView(input)
        box.addView(TextView(ctx).apply {
            text = "機械的に割る（押すと上に並びます。そこから直せます）"
            textSize = 12f
            setPadding(0, pad / 2, 0, 0)
            setTextColor(ContextCompat.getColor(ctx, R.color.on_bg_dim))
        })
        val quick = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val presets = listOf<Pair<String, () -> List<SplitPart>>>(
            "20分ずつ" to { Split.byChunk(item, 20) },
            "30分ずつ" to { Split.byChunk(item, 30) },
            "3つに" to { Split.byCount(item, 3) },
            "4つに" to { Split.byCount(item, 4) }
        )
        for ((label, make) in presets) {
            quick.addView(Button(ctx).apply {
                text = label
                textSize = 11f
                minWidth = 0
                val h = (8 * d).toInt()
                setPadding(h, 0, h, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    input.setText(make().joinToString("\n") { "${it.name} ${it.minutes}分" })
                    input.setSelection(input.text.length)
                }
            })
        }
        box.addView(quick)
        box.addView(TextView(ctx).apply {
            text = "子タスクは親の場所・優先度・色・タグを引き継ぎます。"
            textSize = 11f
            setPadding(0, pad / 2, 0, 0)
            setTextColor(ContextCompat.getColor(ctx, R.color.on_bg_dim))
        })
        AlertDialog.Builder(ctx)
            .setTitle("「${item.name}」を分ける")
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("作る") { _, _ ->
                val parts = Split.fromLines(input.text.toString(), item.durationMinutes)
                if (parts.isEmpty()) Toast.makeText(ctx, "何も書かれていません", Toast.LENGTH_SHORT).show()
                else onCreate(parts)
            }
            .setNegativeButton("やめる", null)
            .show()
    }
}
