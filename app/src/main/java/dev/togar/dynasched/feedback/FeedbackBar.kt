package dev.togar.dynasched.feedback

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.togar.dynasched.R

/**
 * 全画面の上端に「作者に送る」の帯を常設する。
 *
 * 画面ごとにレイアウトへ足すと、新しい画面で付け忘れる。そこで
 * ActivityLifecycleCallbacks で、作られた画面すべての中身の上に後から差し込む。
 * 前回強制終了していたら赤くする（[CrashLog]）。
 */
object FeedbackBar : Application.ActivityLifecycleCallbacks {

    private const val TAG = "skimas-feedback-bar"

    /** 画面名を自分で名乗りたい画面（タブで中身が変わる MainActivity など） */
    interface Named {
        fun feedbackScreenName(): String
    }

    fun register(app: Application) = app.registerActivityLifecycleCallbacks(this)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // 中身は setContentView の後に入るので、少し待ってから差し込む
        activity.window.decorView.post { install(activity) }
    }

    override fun onActivityResumed(activity: Activity) {
        // 送った後・落ちた後で色が変わるので、戻ってくるたびに塗り直す
        activity.findViewById<View>(android.R.id.content)
            ?.findViewWithTag<View>(TAG)?.let { paint(activity, it) }
    }

    private fun install(activity: Activity) {
        val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(TAG) != null) return
        val original = content.getChildAt(0) ?: return
        content.removeView(original)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val bar = makeBar(activity)
        column.addView(bar)
        column.addView(original, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        content.addView(column)
        paint(activity, bar)
    }

    private fun makeBar(activity: Activity): View {
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
        return LinearLayout(activity).apply {
            tag = TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
            addView(TextView(activity).apply {
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(activity).apply {
                text = "送る ›"
                textSize = 12f
                setTextColor(activity.getColor(R.color.white))
            })
            setOnClickListener { open(activity) }
        }
    }

    private fun paint(activity: Activity, bar: View) {
        val crashed = CrashLog.unreported(activity) != null
        bar.setBackgroundColor(if (crashed) 0xFFC62828.toInt() else activity.getColor(R.color.surface))
        val label = (bar as ViewGroup).getChildAt(0) as TextView
        label.text = if (crashed) "前回アプリが止まりました。様子を知らせてください"
            else "不具合・要望を作者に送る"
        label.setTextColor(activity.getColor(if (crashed) R.color.white else R.color.on_bg_dim))
    }

    private fun open(activity: Activity) {
        FeedbackDialog.show(activity, screenName(activity)) {
            activity.findViewById<View>(android.R.id.content)
                ?.findViewWithTag<View>(TAG)?.let { paint(activity, it) }
        }
    }

    private fun screenName(activity: Activity): String =
        (activity as? Named)?.feedbackScreenName() ?: activity.javaClass.simpleName

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
