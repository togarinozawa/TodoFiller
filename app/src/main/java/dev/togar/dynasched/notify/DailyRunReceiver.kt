package dev.togar.dynasched.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.data.Repo
import java.util.Calendar

/**
 * 端末内方式のための毎日の自動実行。
 *
 * サーバー版には毎朝5時の cron があって、そこで予定を組み直してカレンダーを
 * 書き直していた。**移植でこれが抜けていたため、自分で「スケジューラ実行」を
 * 押すまでカレンダーが古いまま残り続けていた。**
 *
 * サーバー方式のときは向こうの cron が働くので、こちらは何もしない。
 */
class DailyRunReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appCtx = context.applicationContext
        // 次回ぶんを先に取り直しておく（1回きりのアラームを毎日繋いでいく）
        schedule(appCtx)

        val pending = goAsync()
        Thread {
            try {
                // 組み直す前にNotionから取り込む。
                // **夜のうちにNotionへ足したタスクが、朝の予定に入らない**のを防ぐ。
                // 繋がらなければそのまま端末の中身だけで組む（ここで止めない）
                try {
                    dev.togar.dynasched.sync.NotionSyncer.sync(appCtx)
                } catch (e: Exception) {
                }
                Repo.current(appCtx).runScheduler(appCtx, Prefs.fillDays(appCtx))
                ScheduleRefresh.refreshAlarms(appCtx)
            } catch (e: Exception) {
                // 失敗しても次の日にまた走る。ここで落ちる方が困る
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val REQUEST_CODE = 900_002
        /** サーバーの cron に合わせて毎朝5時 */
        private const val HOUR = 5

        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val next = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, HOUR)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags = flags or PendingIntent.FLAG_IMMUTABLE
            }
            val pi = PendingIntent.getBroadcast(
                ctx, REQUEST_CODE, Intent(ctx, DailyRunReceiver::class.java), flags
            )
            // 厳密な時刻である必要はないので、端末に優しい setAndAllowWhileIdle を使う
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }
}

/** 予定を組み直した後にアラームとドパチルへの通知を取り直す */
object ScheduleRefresh {
    fun refreshAlarms(ctx: Context) {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        dev.togar.dynasched.api.ScheduleRepo.refresh(ctx, today, force = true)
    }
}
