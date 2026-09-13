package dev.togar.dynasched.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 同期でDBを書き換える直前の控え。**事故った時に直前へ戻すためだけのもの。**
 *
 * v36で「Notionの一覧に出てこないだけ」のタスクを全部消したことがある。
 * 判断の方は直したが、**取り返しが付くかどうかは別の話**なので、
 * 書き換える前に必ず1枚残す。
 *
 * 置き場はアプリの内部領域。SAFで書き出す控え（[Backup]）とは別物で、
 * **アプリを消せば一緒に消える。**長期の保管はあちらの役目。
 */
object Snapshots {

    private const val DIR = "snapshots"

    /** 残す枚数。多くても直前の数回しか使わない */
    private const val KEEP = 5

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /**
     * いまの状態を1枚残す。**書き込みに失敗しても呼び出し側は止めない。**
     * 控えが取れないことより、同期そのものが動かなくなる方が困る。
     */
    fun save(ctx: Context, label: String) {
        try {
            val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            File(dir(ctx), "$name-$label.json").writeText(Backup.export(ctx))
            prune(ctx)
        } catch (e: Exception) {
            // 控えは保険。取れなくても本来の処理は続ける
        }
    }

    /** 新しい順。画面に出す並び */
    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedByDescending { it.name } ?: emptyList()

    /** ファイル名から「9/13 19:41 同期前」のような見出しを作る */
    fun label(file: File): String {
        val n = file.nameWithoutExtension
        // yyyyMMdd-HHmmss-ラベル
        if (n.length < 15) return n
        val mm = n.substring(4, 6).trimStart('0')
        val dd = n.substring(6, 8).trimStart('0')
        val hh = n.substring(9, 11)
        val mi = n.substring(11, 13)
        val rest = if (n.length > 16) n.substring(16) else ""
        return "$mm/$dd $hh:$mi $rest".trim()
    }

    fun restore(ctx: Context, file: File): Backup.Report =
        Backup.restore(ctx, file.readText())

    /** 古い分を落とす */
    private fun prune(ctx: Context) {
        val files = list(ctx)
        if (files.size <= KEEP) return
        for (f in files.drop(KEEP)) runCatching { f.delete() }
    }
}
