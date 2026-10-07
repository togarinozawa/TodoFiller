package dev.togar.dynasched.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.widget.Toast
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.calendar.CalendarRepo
import dev.togar.dynasched.calendar.CalendarWriter
import dev.togar.dynasched.calendar.CalendarSnapshot

/**
 * 端末のカレンダーから何が読めているかを確認するための画面。
 *
 * サーバー経由をやめて CalendarContract へ移す途中の段階で、
 * **タグの付け方と読み取り結果が一致しているか**を実機で確かめるために置いている。
 * ここが合っていないと以降の配置が全部ずれるので、最初に目で見て確認する。
 */
object CalendarCheckDialog {

    private const val REQ_PERMISSION = 4101

    fun show(activity: Activity) {
        if (!hasPermission(activity)) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                REQ_PERMISSION
            )
            AlertDialog.Builder(activity)
                .setTitle("カレンダーの許可が要ります")
                .setMessage("許可したら、もう一度この画面を開いてください。")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val days = Prefs.fillDays(activity)
        val app = activity.applicationContext
        // カレンダーの読み取りは ContentProvider 越しなので、予定が多いと目に見えて待つ。
        // メインスレッドでやると画面が固まるため、必ずワーカーで読む。
        Api.async(
            work = { CalendarRepo.read(app, days, Prefs.calendarId(app)) },
            onSuccess = { snap ->
                if (activity.isFinishing) return@async
                AlertDialog.Builder(activity)
                    .setTitle("カレンダーの読み取り結果")
                    .setMessage(summarize(activity, snap, days))
                    .setPositiveButton("閉じる", null)
                    .setNegativeButton("調べる") { _, _ -> diagnoseMenu(activity) }
                    .setNeutralButton("カレンダーを選ぶ") { _, _ -> chooseCalendar(activity) }
                    .show()
            },
            onError = { e ->
                if (activity.isFinishing) return@async
                Toast.makeText(activity, "読み取りに失敗: ${e.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

    fun hasPermission(activity: Activity): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun summarize(activity: Activity, s: CalendarSnapshot, days: Int): String {
        if (s.calendarName.isEmpty()) {
            return "端末にカレンダーが見つかりませんでした。\nGoogleカレンダーの同期がONか確認してください。"
        }
        val places = dev.togar.dynasched.Places.all(activity)
        val sb = StringBuilder()
        sb.append("カレンダー: ${s.calendarName}\n")
        val cal = CalendarRepo.listCalendars(activity)
            .firstOrNull { it.displayName == s.calendarName }
        if (cal != null && !cal.canWrite) {
            // 読めるのに書けないカレンダー（祝日・購読など）を掴むと、
            // 予定が増えも減りもしないという一番分かりにくい壊れ方をする
            sb.append("⚠ 読み取り専用です。ここには書き込めません。\n")
        }
        sb.append("これから${days}日ぶんを読みました\n\n")
        sb.append("作業できる枠: ${s.windows.size}件\n")
        sb.append("予定あり: ${s.busy.size}件\n")
        sb.append("テスト期間: ${s.examPeriods.size}件\n")
        if (s.skippedGenerated > 0) sb.append("自動生成(%)を読み飛ばし: ${s.skippedGenerated}件\n")

        if (s.windows.isEmpty()) {
            sb.append("\n枠が0件です。予定のタイトルの末尾に「${places.joinToString("」か「") { it.name }}」を付けてください。")
            sb.append("\n枠が無いと何も配置されません。")
        } else {
            sb.append("\n--- 作業できる枠 ---\n")
            for (w in s.windows.take(10)) {
                sb.append("${w.start.substring(5, 16)} 〜 ${w.end.substring(11, 16)}  ")
                sb.append(dev.togar.dynasched.Places.name(places, w.location))
                sb.append("  ${w.title}\n")
            }
            if (s.windows.size > 10) sb.append("…ほか${s.windows.size - 10}件\n")
        }
        if (s.examPeriods.isNotEmpty()) {
            sb.append("\n--- テスト期間 ---\n")
            for (p in s.examPeriods) sb.append("${p.startDate} 〜 ${p.endDate}\n")
        }
        return sb.toString()
    }

    private fun diagnoseMenu(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("調べる")
            .setItems(
                arrayOf("書き込みテスト", "他のカレンダーに残った自動生成を探す")
            ) { _, i ->
                if (i == 0) writeTest(activity) else findLeftovers(activity)
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    /**
     * 対象カレンダー以外に残っている「%」付き予定を探す。
     *
     * サーバー版は自前で作ったカレンダーへ書いていたので、端末内方式へ移った後も
     * そこの予定はアプリの視界の外に残り続ける。「予定が消えない」がこれなのかを
     * 件数で確かめて、**消すかどうかは必ず本人に選ばせる**（他人の予定を勝手に消さない）。
     */
    private fun findLeftovers(activity: Activity) {
        val app = activity.applicationContext
        val days = Prefs.fillDays(app)
        Api.async(
            work = { CalendarRepo.findGeneratedElsewhere(app, currentCalendarId(app), days) },
            onSuccess = { found ->
                if (activity.isFinishing) return@async
                if (found.isEmpty()) {
                    AlertDialog.Builder(activity)
                        .setTitle("残骸は見つかりませんでした")
                        .setMessage(
                            "いま見ているカレンダー以外に、末尾が「%」の予定はありませんでした。\n" +
                                "（過去30日〜これから${days}日を調べました）"
                        )
                        .setPositiveButton("閉じる", null)
                        .show()
                    return@async
                }
                val sb = StringBuilder("いま見ていないカレンダーに、スキマスが作った予定が残っています。\n\n")
                for ((cal, n, sample) in found) {
                    sb.append("・${cal.displayName}（${cal.accountName}）: ${n}件\n")
                    sb.append("　例: $sample\n")
                    if (!cal.canWrite) sb.append("　※読み取り専用なのでアプリからは消せません\n")
                }
                sb.append("\nこれが「予定が消えない」の正体です。まとめて消しますか？")
                AlertDialog.Builder(activity)
                    .setTitle("他のカレンダーに残っています")
                    .setMessage(sb.toString())
                    .setPositiveButton("消す") { _, _ -> deleteLeftovers(activity, found.map { it.first.id }) }
                    .setNegativeButton("そのままにする", null)
                    .show()
            },
            onError = { e ->
                if (activity.isFinishing) return@async
                Toast.makeText(activity, "調べられませんでした: ${e.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun deleteLeftovers(activity: Activity, calendarIds: List<Long>) {
        val app = activity.applicationContext
        val days = Prefs.fillDays(app)
        Api.async(
            work = {
                val from = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
                val to = System.currentTimeMillis() + (days + 1).toLong() * 24 * 3600 * 1000
                calendarIds.sumOf { CalendarWriter.clearGeneratedBetween(app, it, from, to) }
            },
            onSuccess = { n ->
                if (activity.isFinishing) return@async
                AlertDialog.Builder(activity)
                    .setTitle("削除しました")
                    .setMessage(
                        "${n}件を消しました。\n\n" +
                            "0件のままなら、そのカレンダーが読み取り専用でアプリから消せません。" +
                            "Googleカレンダー側で消してください。"
                    )
                    .setPositiveButton("閉じる", null)
                    .show()
            },
            onError = { e ->
                if (activity.isFinishing) return@async
                Toast.makeText(activity, "消せませんでした: ${e.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun currentCalendarId(ctx: android.content.Context): Long? =
        Prefs.calendarId(ctx) ?: CalendarRepo.targetCalendar(ctx)?.id

    /**
     * 実際に書いて・読み直して・消せるかを確かめる。
     *
     * 「読み取りはできるが書き込みができない」という状態は、権限やカレンダーの
     * アクセス権によっては普通に起こりうる。しかも**黙って失敗する**ので、
     * 予定が消えない・増え続けるという形でしか表に出てこない。
     * 推測で語らずに済むよう、各段階の結果をそのまま出す。
     */
    private fun writeTest(activity: Activity) {
        val app = activity.applicationContext
        Api.async(
            work = {
                val sb = StringBuilder()
                val cal = CalendarRepo.listCalendars(app)
                    .firstOrNull { it.id == Prefs.calendarId(app) }
                    ?: CalendarRepo.targetCalendar(app)
                if (cal == null) {
                    sb.append("カレンダーが見つかりません")
                    return@async sb.toString()
                }
                sb.append("対象: ${cal.displayName}\n")
                sb.append("権限: ${if (cal.canWrite) "書き込みできるはず" else "読み取り専用"}\n\n")

                // 1) 30分後に1件書く
                val start = System.currentTimeMillis() + 30 * 60_000L
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                val added = CalendarWriter.replaceGenerated(
                    app, cal.id,
                    listOf(Triple("書き込みテスト", fmt.format(java.util.Date(start)),
                        fmt.format(java.util.Date(start + 30 * 60_000L)))),
                    1
                )
                sb.append("1) 書き込み: 削除${added.first}件 / 追加${added.second}件\n")
                if (added.second == 0) {
                    sb.append("\n**書き込めていません。**\n")
                    sb.append("カレンダーの権限（変更）が無いか、このカレンダーが読み取り専用です。")
                    return@async sb.toString()
                }

                // 2) 読み直して自動生成として認識されるか
                val snap = CalendarRepo.read(app, 1, cal.id)
                sb.append("2) 読み直し: 自動生成として${snap.skippedGenerated}件を認識\n")

                // 3) 消せるか
                val removed = CalendarWriter.clearGenerated(app, cal.id, 1)
                sb.append("3) 削除: ${removed}件\n")
                val after = CalendarRepo.read(app, 1, cal.id)
                sb.append("4) 削除後に残った自動生成: ${after.skippedGenerated}件\n\n")
                sb.append(
                    if (removed > 0 && after.skippedGenerated == 0) "書き込みも削除もできています。"
                    else "**消せていません。**予定が残り続ける原因はここです。"
                )
                sb.toString()
            },
            onSuccess = { msg ->
                if (activity.isFinishing) return@async
                AlertDialog.Builder(activity)
                    .setTitle("書き込みテスト")
                    .setMessage(msg)
                    .setPositiveButton("閉じる", null)
                    .show()
            },
            onError = { e ->
                if (activity.isFinishing) return@async
                AlertDialog.Builder(activity)
                    .setTitle("書き込みテスト")
                    .setMessage("失敗しました:\n${e}")
                    .setPositiveButton("閉じる", null)
                    .show()
            }
        )
    }

    /** カレンダーが複数ある時に、どれを見るかを選ばせる */
    private fun chooseCalendar(activity: Activity) = pickCalendar(activity) { show(activity) }

    /** 読み書きするカレンダーを選ばせる。準備画面からも使う */
    fun pickCalendar(activity: Activity, onPicked: () -> Unit) {
        val cals = CalendarRepo.listCalendars(activity)
        if (cals.isEmpty()) {
            android.widget.Toast.makeText(activity,
                "端末にカレンダーが見つかりませんでした。Googleカレンダーの同期がONか確認してください",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val labels = cals.map {
            (if (it.isPrimary) "★ " else "") + it.displayName +
                (if (!it.canWrite) "（読み取り専用）" else "") + "\n" + it.accountName
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("読み取るカレンダー")
            .setItems(labels) { _, i ->
                Prefs.setCalendarId(activity, cals[i].id)
                onPicked()
            }
            .setNegativeButton("閉じる", null)
            .show()
    }
}
