package dev.togar.dynasched.integration

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * ドパチル（FlexHarness）へ「中身が変わった」とだけ知らせる合図。中身は向こうが [NowTasksProvider] を読みに来る。
 *
 * Android 11 以下では、鍵違いを通す knownSigner が効かず、学習予定のブロードキャスト（[StudySync]）が
 * 黙って捨てられる。合図には権限を付けない。偽の合図が来ても向こうが読み直すだけで害は無く、
 * 中身は読む側（provider）で相手の証明書を確かめてから渡す。
 *
 * 書き込みのたびに呼んでよい。0.5秒まとめてから1回だけ送る（並び替えなどで何十回も書くため）。
 * 書き込みの**前に**呼んでも、送るのはまとめた後なので、書き終わった中身が読まれる。
 */
object DopaSignal {

    const val ACTION = "dev.togar.dynasched.action.DATA_CHANGED"
    private const val TARGET = "com.dopachiru"
    private const val DELAY_MS = 500L

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var app: Context? = null
    private val fire = Runnable {
        val ctx = app ?: return@Runnable
        try {
            ctx.sendBroadcast(Intent(ACTION).setPackage(TARGET))
        } catch (e: Exception) {
            // 入っていない・送れないは無視する。連携の失敗で本体を止めない
        }
    }

    fun changed(ctx: Context) {
        app = ctx.applicationContext
        handler.removeCallbacks(fire)
        handler.postDelayed(fire, DELAY_MS)
    }
}
