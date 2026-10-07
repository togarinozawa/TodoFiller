package dev.togar.dynasched

import android.app.Application
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.notify.Notifications

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 通知チャンネルを起動時に必ず用意しておく
        Notifications.ensureChannel(this)

        Api.appContext = applicationContext
        // 強制終了は端末に1件だけ残し、次に開いた時に「作者に送る」の帯を赤くする。
        // 自動では送らない（送り先のサーバーは畳んだ）
        dev.togar.dynasched.feedback.CrashLog.install(this)
        dev.togar.dynasched.feedback.FeedbackBar.register(this)
    }
}
