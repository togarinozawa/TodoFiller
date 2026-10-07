package dev.togar.dynasched.calendar

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 端末のカレンダーDB（CalendarContract）を直接読む。
 *
 * **Google の OAuth を使わない**のが要点。Googleカレンダーアプリが端末へ同期した
 * 内容をそのまま読むので、サーバーもトークンも審査も要らない。
 * 書き戻した予定はGoogleカレンダーへ同期して戻る。
 *
 * 分類ルールはサーバー版（googleCalendar.syncAvailabilityFromPrimary）と同一:
 * - 末尾が `%` … スキマスの自動生成。読み飛ばす（同期のたびに作り直すため）
 * - 「テスト期間」で始まる … その期間は定期テストONの教材だけを配置
 * - 末尾が場所の名前 … その場所で作業できる枠（[dev.togar.dynasched.Places]）。
 *   家は `家` / `&h`、外は `外` / `&o` も読む。設定で足した場所は名前そのものが印
 * - タグ無しの**終日**予定（祝日など）… 何もしない。busy にしない
 * - タグ無しの時間指定予定 … busy（埋まっている）
 */
object CalendarRepo {

    /** 繰り返し予定を展開して読むため Instances を使う（Events だとRRULEが展開されない） */
    private val INSTANCE_COLS = arrayOf(
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY
    )

    private val CALENDAR_COLS = arrayOf(
        CalendarContract.Calendars._ID,
        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
        CalendarContract.Calendars.ACCOUNT_NAME,
        CalendarContract.Calendars.OWNER_ACCOUNT,
        CalendarContract.Calendars.IS_PRIMARY,
        CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
    )

    /** 末尾に付けるとスキマスの自動生成とみなされる印 */
    const val GENERATED_SUFFIX = "%"

    // ---- カレンダーの選択 ----

    /** 端末上のカレンダー一覧（読み取り専用のものも含む。`canWrite` で判別する） */
    fun listCalendars(ctx: Context): List<DeviceCalendar> {
        val out = ArrayList<DeviceCalendar>()
        query(ctx, CalendarContract.Calendars.CONTENT_URI, CALENDAR_COLS, null, null)?.use { c ->
            while (c.moveToNext()) {
                val account = c.getString(2) ?: ""
                val owner = c.getString(3) ?: ""
                // IS_PRIMARY が null の端末があるので、所有者＝アカウントでも主とみなす
                val primary = c.getInt(4) == 1 || (owner.isNotEmpty() && owner == account)
                out.add(
                    DeviceCalendar(
                        id = c.getLong(0),
                        displayName = c.getString(1) ?: account,
                        accountName = account,
                        isPrimary = primary,
                        canWrite = c.getInt(5) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
                    )
                )
            }
        }
        return out
    }

    /**
     * 読み書きの対象カレンダー。
     *
     * **書き込めるものを優先する。** 祝日カレンダーなどを掴むと、読めるのに
     * 書けない・消せないという一番分かりにくい壊れ方をする。
     */
    fun targetCalendar(ctx: Context): DeviceCalendar? {
        val all = listCalendars(ctx)
        return all.firstOrNull { it.isPrimary && it.canWrite }
            ?: all.firstOrNull { it.canWrite }
            ?: all.firstOrNull { it.isPrimary }
            ?: all.firstOrNull()
    }

    /**
     * 対象カレンダー以外に残っている自動生成予定（末尾「%」）を数える。
     *
     * サーバー版は専用のカレンダーを自前で作ってそこへ書いていた。端末内方式は
     * 主カレンダーを見るので、**サーバー時代の予定はアプリの視界の外に残り続ける**。
     * 「消えない」の正体がこれかどうかを、推測ではなく件数で確かめるためのもの。
     *
     * @return カレンダー → (予定の件数, 見本のタイトル)
     */
    fun findGeneratedElsewhere(
        ctx: Context, exceptCalendarId: Long?, daysAhead: Int
    ): List<Triple<DeviceCalendar, Int, String>> {
        val from = System.currentTimeMillis() - 30L * DAY_MS   // 過去の残骸も拾う
        val to = System.currentTimeMillis() + (daysAhead + 1).toLong() * DAY_MS
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, from)
            ContentUris.appendId(this, to)
        }.build()
        val out = ArrayList<Triple<DeviceCalendar, Int, String>>()
        for (cal in listCalendars(ctx)) {
            if (cal.id == exceptCalendarId) continue
            var n = 0
            var sample = ""
            query(
                ctx, uri,
                arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE),
                "${CalendarContract.Instances.CALENDAR_ID}=?", arrayOf(cal.id.toString())
            )?.use { c ->
                val seen = HashSet<Long>()
                while (c.moveToNext()) {
                    val t = (c.getString(1) ?: "").trim()
                    if (!t.endsWith(GENERATED_SUFFIX)) continue
                    if (!seen.add(c.getLong(0))) continue
                    n++
                    if (sample.isEmpty()) sample = t
                }
            }
            if (n > 0) out.add(Triple(cal, n, sample))
        }
        return out
    }

    // ---- 読み取り ----

    /**
     * 今日から daysAhead 日ぶんを読み、枠・予定・テスト期間に振り分ける。
     * 権限が無い場合やカレンダーが無い場合は空の結果を返す（例外は投げない）。
     */
    fun read(ctx: Context, daysAhead: Int = 7, calendarId: Long? = null): CalendarSnapshot {
        val cal = calendarId?.let { id -> listCalendars(ctx).firstOrNull { it.id == id } }
            ?: targetCalendar(ctx)
            ?: return CalendarSnapshot(emptyList(), emptyList(), emptyList(), "", 0)

        val dayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        // サーバー版と同じく1日多く見る（終日予定の末尾を取りこぼさないため）
        val rangeEnd = dayStart + (daysAhead + 1).toLong() * DAY_MS

        val windows = ArrayList<AvailabilityWindow>()
        val busy = ArrayList<BusyBlock>()
        val exams = ArrayList<ExamPeriod>()
        var skipped = 0
        val places = dev.togar.dynasched.Places.all(ctx)

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, dayStart)
            ContentUris.appendId(this, rangeEnd)
        }.build()

        query(
            ctx, uri, INSTANCE_COLS,
            "${CalendarContract.Instances.CALENDAR_ID}=?", arrayOf(cal.id.toString())
        )?.use { c ->
            while (c.moveToNext()) {
                when (val entry = classify(
                    summary = (c.getString(1) ?: "").trim(),
                    beginMs = c.getLong(2),
                    endMs = c.getLong(3),
                    allDay = c.getInt(4) == 1,
                    eventId = c.getLong(0),
                    places = places
                )) {
                    is CalEntry.Window -> windows.add(entry.value)
                    is CalEntry.Busy -> busy.add(entry.value)
                    is CalEntry.Exam -> exams.add(entry.value)
                    CalEntry.Generated -> skipped++
                    CalEntry.Ignored -> Unit
                }
            }
        }
        return CalendarSnapshot(windows, busy, exams, cal.displayName, skipped)
    }

    // ---- 分類（Androidに依存しない純関数。テストで固定する） ----

    /** 1件の予定をどう扱うか */
    sealed class CalEntry {
        data class Window(val value: AvailabilityWindow) : CalEntry()
        data class Busy(val value: BusyBlock) : CalEntry()
        data class Exam(val value: ExamPeriod) : CalEntry()
        /** 末尾「%」＝スキマスが作った予定 */
        object Generated : CalEntry()
        /** タグ無しの終日予定など、材料にしないもの */
        object Ignored : CalEntry()
    }

    /**
     * カレンダー1件をどう扱うか決める。
     *
     * **終日予定の begin/end は UTC の深夜**で入っている点に注意。
     * ローカルとして読むと日本では前日09:00になり、日付が1日ずれる。
     * 終日とそれ以外で変換を分けているのはそのため。
     */
    fun classify(
        summary: String, beginMs: Long, endMs: Long, allDay: Boolean, eventId: Long,
        places: List<dev.togar.dynasched.Place> = dev.togar.dynasched.Places.DEFAULT
    ): CalEntry {
        // 自分で作った予定は読まない。同期のたびに作り直すので、材料にすると循環する
        if (summary.endsWith(GENERATED_SUFFIX)) return CalEntry.Generated

        if (summary.startsWith("テスト期間")) {
            // 終日の end は翌日（排他）なので1日戻して両端を含む形にする
            val sd = if (allDay) utcDate(beginMs) else localDate(beginMs)
            val ed = if (allDay) utcDate(endMs - DAY_MS) else localDate(endMs)
            return CalEntry.Exam(ExamPeriod(sd, ed, summary, eventId))
        }

        val matched = dev.togar.dynasched.Places.matchSuffix(places, summary)
        val loc = matched?.first
        val title = matched?.second ?: summary

        return if (allDay) {
            // タグ無しの終日予定（祝日など）で1日を潰さない。
            // これが無いと祝日のたびに何も配置されなくなる。
            if (loc == null) CalEntry.Ignored
            else CalEntry.Window(
                AvailabilityWindow(
                    utcDate(beginMs) + "T00:00:00", utcDate(endMs) + "T00:00:00", loc, title, eventId
                )
            )
        } else {
            val s = localNaive(beginMs)
            val e = localNaive(endMs)
            if (loc != null) CalEntry.Window(AvailabilityWindow(s, e, loc, title, eventId))
            else CalEntry.Busy(BusyBlock(s, e, title, eventId))
        }
    }

    // ---- 時刻の変換 ----
    //
    // 終日予定の BEGIN/END は **UTCの深夜**で入っている。ローカルとして読むと
    // 日付が1日ずれる（日本なら前日の09:00になる）。終日とそれ以外で必ず分ける。

    private const val DAY_MS = 24L * 3600 * 1000

    private fun fmt(pattern: String, tz: TimeZone, millis: Long): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = tz }.format(Date(millis))

    /** 終日予定用: UTCで日付を取る */
    private fun utcDate(millis: Long): String =
        fmt("yyyy-MM-dd", TimeZone.getTimeZone("UTC"), millis)

    /** 時間指定予定用: 端末のタイムゾーンで日付を取る */
    private fun localDate(millis: Long): String =
        fmt("yyyy-MM-dd", TimeZone.getDefault(), millis)

    /** 時間指定予定用: 端末のタイムゾーンで `yyyy-MM-dd'T'HH:mm:ss` */
    private fun localNaive(millis: Long): String =
        fmt("yyyy-MM-dd'T'HH:mm:ss", TimeZone.getDefault(), millis)

    /**
     * 権限が無いと SecurityException が飛ぶ。読み取りは「できなければ空」で
     * 済ませたいので、ここで吸収して null を返す。
     */
    private fun query(
        ctx: Context, uri: android.net.Uri, cols: Array<String>,
        selection: String?, args: Array<String>?
    ): Cursor? = try {
        ctx.contentResolver.query(uri, cols, selection, args, null)
    } catch (e: SecurityException) {
        null
    } catch (e: Exception) {
        null
    }
}
