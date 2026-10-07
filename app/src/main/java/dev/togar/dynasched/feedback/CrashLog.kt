package dev.togar.dynasched.feedback

import android.content.Context
import dev.togar.dynasched.Prefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 強制終了を端末に1件だけ記録する。**どこへも送らない。**
 *
 * サーバーは畳んだので自動で集める先が無い。友達の端末で落ちても、
 * 本人が気付いて話してくれない限り分からない。そこで次に開いた時に
 * 「作者に送る」の帯を赤くし、送る時に記録を添えられるようにする。
 */
object CrashLog {

    private const val FILE = "last_crash.txt"

    /** これより古い記録では帯を赤くしない。何週間も前の話を毎回せがまない */
    private const val FRESH_MS = 7L * 24 * 3600 * 1000

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val at = System.currentTimeMillis()
                File(app.filesDir, FILE).writeText("$at\n${android.util.Log.getStackTraceString(e)}")
            } catch (_: Throwable) {
                // 記録に失敗しても、本来の落ち方は邪魔しない
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** 最後の記録。無ければ null */
    fun latest(ctx: Context): Feedback.Crash? = try {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) null else {
            val text = f.readText()
            val at = text.substringBefore('\n').toLongOrNull() ?: 0L
            Feedback.Crash(
                at = at,
                atText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(at)),
                trace = text.substringAfter('\n', "")
            )
        }
    } catch (_: Exception) {
        null
    }

    /** まだ知らせていない、新しめの記録 */
    fun unreported(ctx: Context): Feedback.Crash? {
        val c = latest(ctx) ?: return null
        if (c.at <= Prefs.crashReportedAt(ctx)) return null
        if (System.currentTimeMillis() - c.at > FRESH_MS) return null
        return c
    }
}
