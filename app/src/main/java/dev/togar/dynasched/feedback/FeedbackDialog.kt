package dev.togar.dynasched.feedback

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import dev.togar.dynasched.BuildConfig
import dev.togar.dynasched.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 「作者に送る」の入力。種類を選び、一言書いて、共有シートで送る */
object FeedbackDialog {

    fun show(activity: Activity, screen: String, onSent: () -> Unit) {
        val ctx = activity
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
        val crash = CrashLog.unreported(ctx)
        val (draftKind, draftText) = Prefs.feedbackDraft(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        root.addView(TextView(ctx).apply {
            text = if (crash != null) "止まる直前に何をしていたか、分かる範囲で書いてください。"
                else "不具合も、欲しい機能も、ここを変えてほしいも歓迎です。"
            textSize = 13f
        })

        val kinds = Feedback.Kind.entries
        val group = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
        for ((i, k) in kinds.withIndex()) {
            group.addView(RadioButton(ctx).apply { id = i + 1; text = k.label; textSize = 13f })
        }
        root.addView(group)

        val input = EditText(ctx).apply {
            minLines = 4
            gravity = android.view.Gravity.TOP
            setText(draftText)
        }
        root.addView(input)

        val skipCrash = CheckBox(ctx).apply {
            text = "記録は送らない"
            textSize = 12f
            visibility = if (crash != null) android.view.View.VISIBLE else android.view.View.GONE
        }
        root.addView(skipCrash)

        val envText = TextView(ctx).apply { textSize = 11f; setPadding(0, dp(8), 0, 0) }
        root.addView(envText)

        fun env() = Feedback.Env(
            screen = screen,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            sentAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        )
        fun kind() = kinds.getOrElse(group.checkedRadioButtonId - 1) { Feedback.Kind.BUG }
        fun refresh() {
            val k = kind()
            input.hint = k.hint
            val withCrash = crash != null && Feedback.attachesCrash(k)
            skipCrash.visibility = if (withCrash) android.view.View.VISIBLE else android.view.View.GONE
            envText.text = "自動で付く情報:\n" + Feedback.envLines(env()).joinToString("\n") +
                if (withCrash && !skipCrash.isChecked) "\n（止まった時の記録も付けます）" else ""
        }
        // 落ちた後は不具合から始める。それ以外は書きかけの種類を戻す
        val initial = if (crash != null) Feedback.Kind.BUG
            else if (draftKind.isNotEmpty()) Feedback.Kind.from(draftKind) else Feedback.Kind.BUG
        group.check(kinds.indexOf(initial) + 1)
        group.setOnCheckedChangeListener { _, _ -> refresh() }
        skipCrash.setOnCheckedChangeListener { _, _ -> refresh() }
        refresh()

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (crash != null) "前回アプリが止まりました" else "作者に送る")
            .setView(root)
            .setPositiveButton("送る", null)   // 空の時は閉じないよう後で差し替える
            .setNegativeButton("やめる", null)
            .create()
        var sent = false
        dialog.setOnDismissListener {
            // 送らずに閉じた時は書きかけを残す
            if (!sent) Prefs.setFeedbackDraft(ctx, kind().name, input.text.toString())
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString()
                if (!Feedback.canSend(text)) {
                    input.error = "一言だけでも書いてください"
                    return@setOnClickListener
                }
                val k = kind()
                val body = Feedback.compose(k, text, env(), if (skipCrash.isChecked) null else crash)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "スキマス: ${k.label}")
                    putExtra(Intent.EXTRA_TEXT, body)
                }
                try {
                    activity.startActivity(Intent.createChooser(send, "送り先を選ぶ（作者のLINEなど）"))
                } catch (e: Exception) {
                    Toast.makeText(ctx, "送れるアプリが見つかりませんでした", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                // 共有シートを開いた時点で済んだことにする。実際に送ったかは分からないが、
                // 帯を赤いままにして何度もせがむよりよい
                sent = true
                Prefs.setFeedbackDraft(ctx, "", "")
                crash?.let { Prefs.setCrashReportedAt(ctx, it.at) }
                dialog.dismiss()
                onSent()
            }
        }
        dialog.show()
    }
}
