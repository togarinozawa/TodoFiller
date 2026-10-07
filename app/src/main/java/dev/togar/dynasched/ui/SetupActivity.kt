package dev.togar.dynasched.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.R
import dev.togar.dynasched.calendar.CalendarRepo

/**
 * はじめる前の準備。カレンダー・通知・正確なアラーム・電池の最適化を上から順に済ませる。
 *
 * 友達に渡すと、許可を1つ断っただけで「何も起きないアプリ」になり、
 * 本人はそれに気付けない。初回は使い方の次に必ず出し、設定からも開けるようにしてある。
 */
class SetupActivity : AppCompatActivity() {

    companion object {
        fun hasCalendar(ctx: Context): Boolean =
            granted(ctx, Manifest.permission.READ_CALENDAR) &&
                granted(ctx, Manifest.permission.WRITE_CALENDAR)

        fun hasNotifications(ctx: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
            else androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()

        fun hasExactAlarm(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

        fun ignoresBattery(ctx: Context): Boolean =
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(ctx.packageName)

        /** 済んでいないものがあるか。設定の目次に出す */
        fun needsAttention(ctx: Context): Boolean =
            !hasCalendar(ctx) || !hasNotifications(ctx) || !hasExactAlarm(ctx) || !ignoresBattery(ctx)

        private fun granted(ctx: Context, perm: String) =
            ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    }

    /** 1行ぶん。済んだかどうかは [check] で毎回見直す */
    private class Row(val status: TextView, val button: Button, val check: () -> Boolean, val okText: String)

    private val rows = ArrayList<Row>()
    private var calendarRow: Row? = null

    private val calendarPerm = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { !it } && !hasCalendar(this)) sendToAppSettings("カレンダー")
        refresh()
    }

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        if (!ok) openNotificationSettings()
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        col.addView(TextView(this).apply {
            text = "はじめる前の準備"
            textSize = 20f
            setTextColor(getColor(R.color.on_bg))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "上から順に押してください。済んだものには「済」が付きます。あとから設定タブでも見直せます。"
            textSize = 13f
            setTextColor(getColor(R.color.on_bg_dim))
            setPadding(0, dp(8), 0, dp(8))
        })

        fun addRow(title: String, desc: String, action: String, okText: String = "済",
                   check: () -> Boolean, onClick: () -> Unit): Row {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(getColor(R.color.surface))
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            }
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(TextView(this).apply {
                text = title
                textSize = 15f
                setTextColor(getColor(R.color.on_bg))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val status = TextView(this).apply { textSize = 13f }
            head.addView(status)
            box.addView(head)
            box.addView(TextView(this).apply {
                text = desc
                textSize = 12f
                setTextColor(getColor(R.color.on_bg_dim))
                setPadding(0, dp(4), 0, dp(4))
            })
            val button = Button(this).apply {
                text = action
                setOnClickListener { onClick() }
            }
            box.addView(button)
            col.addView(box)
            return Row(status, button, check, okText).also { rows.add(it) }
        }

        calendarRow = addRow(
            "カレンダーを読み書きする",
            "空いている時間を読み、予定を書き込むのに使います。これが無いと何も置けません。",
            "許可する", check = { hasCalendar(this) }
        ) {
            calendarPerm.launch(arrayOf(
                Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR
            ))
        }
        addRow(
            "使うカレンダー",
            "予定を読み書きする先です。Googleアカウントのカレンダーを選んでください（祝日や購読カレンダーには書き込めません）。",
            "選ぶ", okText = "",
            check = { hasCalendar(this) && currentCalendar() != null }
        ) {
            if (!hasCalendar(this)) {
                Toast.makeText(this, "先にカレンダーを許可してください", Toast.LENGTH_SHORT).show()
            } else {
                CalendarCheckDialog.pickCalendar(this) { refresh() }
            }
        }
        addRow(
            "通知",
            "予定の始まりと終わりに知らせます。「何問やった？」も通知から答えられます。",
            "許可する", check = { hasNotifications(this) }
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else openNotificationSettings()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addRow(
                "時刻どおりに知らせる",
                "予定の時刻ちょうどに通知を出すための許可です。",
                "許可する", check = { hasExactAlarm(this) }
            ) {
                open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName")))
            }
        }
        addRow(
            "電池の最適化から外す",
            "外さないと、機種によっては毎朝の組み直しや通知が止まります。電池の減りはほとんど変わりません。",
            "外す", check = { ignoresBattery(this) }
        ) {
            // 直接たずねる画面が無い機種があるので、一覧の画面へ逃がす
            @Suppress("BatteryLife")
            val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"))
            if (!open(ask, quiet = true)) open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        col.addView(Button(this).apply {
            text = "はじめる"
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.primary))
            setTextColor(getColor(R.color.white))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
            setOnClickListener { finishSetup() }
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.bg))
            addView(col)
        })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // 端末の設定画面から戻ってきた時に「済」を付け直す
        refresh()
    }

    private fun currentCalendar(): String? = try {
        val cals = CalendarRepo.listCalendars(this)
        val id = Prefs.calendarId(this)
        (cals.firstOrNull { it.id == id } ?: CalendarRepo.targetCalendar(this))?.displayName
    } catch (e: Exception) {
        null
    }

    private fun refresh() {
        for (r in rows) {
            val ok = r.check()
            r.status.text = when {
                ok && r.okText.isEmpty() -> currentCalendar() ?: "済"
                ok -> r.okText
                else -> "未"
            }
            r.status.setTextColor(getColor(if (ok) R.color.primary else R.color.accent_goal))
        }
    }

    /** カレンダーだけは無いと本当に何も起きないので、未許可なら一度だけ止める */
    private fun finishSetup() {
        if (!hasCalendar(this)) {
            AlertDialog.Builder(this)
                .setTitle("カレンダーが未許可です")
                .setMessage("このままだと予定を1件も置けません。あとで設定タブの「はじめる前の準備」から許可できます。")
                .setPositiveButton("このまま進む") { _, _ -> done() }
                .setNegativeButton("戻る", null)
                .show()
            return
        }
        done()
    }

    private fun done() {
        Prefs.setSetupShown(this, true)
        // 許可が揃った直後に通知と毎日の組み直しを仕掛け直す
        dev.togar.dynasched.notify.DailyRunReceiver.schedule(this)
        dev.togar.dynasched.notify.BedtimeReceiver.schedule(this)
        finish()
    }

    /** 一度断った許可は、アプリからは聞き直せない */
    private fun sendToAppSettings(what: String) {
        AlertDialog.Builder(this)
            .setTitle("${what}の許可が要ります")
            .setMessage("一度断ると、ここからは聞き直せません。アプリの設定画面の「権限」から許可してください。")
            .setPositiveButton("設定を開く") { _, _ ->
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            }
            .setNegativeButton("あとで", null)
            .show()
    }

    private fun openNotificationSettings() {
        open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    private fun open(intent: Intent, quiet: Boolean = false): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        if (!quiet) Toast.makeText(this, "設定画面を開けませんでした。端末の設定から探してください",
            Toast.LENGTH_LONG).show()
        false
    }
}
