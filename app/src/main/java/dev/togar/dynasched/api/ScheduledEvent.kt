package dev.togar.dynasched.api

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * バックエンドの scheduled_events 1件分。
 * start_datetime / end_datetime は "yyyy-MM-dd'T'HH:mm:ss" 形式の文字列。
 */
data class ScheduledEvent(
    val id: Long,
    val title: String,
    val startDatetime: String,
    val endDatetime: String,
    val eventType: String,
    val isCompleted: Boolean,
    val materialId: Long? = null,
    /** 手で置いた・動かした予定。組み直しでは消さず、その時間を避けて置く */
    val isManual: Boolean = false
) {
    /** 開始時刻を Date に変換（失敗したら null） */
    fun startAsDate(): Date? = parse(startDatetime)

    /** 終了時刻を Date に変換（失敗したら null） */
    fun endAsDate(): Date? = parse(endDatetime)

    /** 例: "09:30" */
    fun startTimeLabel(): String {
        val d = startAsDate() ?: return ""
        return TIME_FMT.format(d)
    }

    /** 例: "10:15" */
    fun endTimeLabel(): String {
        val d = endAsDate() ?: return ""
        return TIME_FMT.format(d)
    }

    /** 例: "06/29 (月)" */
    fun startDateLabel(): String {
        val d = startAsDate() ?: return ""
        return DATE_FMT.format(d)
    }

    /** 当日(yyyy-MM-dd)に属するか */
    fun isOnDate(yyyyMMdd: String): Boolean =
        startDatetime.startsWith(yyyyMMdd)

    companion object {
        // バックエンドはタイムゾーンなしのローカル時刻文字列なので、端末ローカルとして解釈する
        private val PARSER = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.JAPAN)
        private val DATE_FMT = SimpleDateFormat("MM/dd (E)", Locale.JAPAN)

        private fun parse(s: String): Date? = try {
            // 秒が無い "yyyy-MM-dd'T'HH:mm" にも一応対応
            val normalized = if (s.length == 16) s + ":00" else s
            PARSER.parse(normalized)
        } catch (e: Exception) {
            null
        }

        fun from(o: JSONObject): ScheduledEvent = ScheduledEvent(
            id = o.optLong("id"),
            title = o.optString("title", "(無題)"),
            startDatetime = o.optString("start_datetime", ""),
            endDatetime = o.optString("end_datetime", ""),
            eventType = o.optString("event_type", "study"),
            isCompleted = o.optInt("is_completed", 0) == 1,
            materialId = o.optLong("material_id", 0L).let { if (it > 0L) it else null },
            isManual = o.optInt("is_manual", 0) == 1
        )

        /** 端末内キャッシュ用のシリアライズ（サーバーのJSONと同じ形） */
        fun toJsonArray(events: List<ScheduledEvent>): String {
            val arr = JSONArray()
            for (ev in events) {
                arr.put(
                    JSONObject()
                        .put("id", ev.id)
                        .put("title", ev.title)
                        .put("start_datetime", ev.startDatetime)
                        .put("end_datetime", ev.endDatetime)
                        .put("event_type", ev.eventType)
                        .put("is_completed", if (ev.isCompleted) 1 else 0)
                        .put("material_id", ev.materialId ?: 0L)
                        .put("is_manual", if (ev.isManual) 1 else 0)
                )
            }
            return arr.toString()
        }

        /**
         * いまから次の未完了予定が始まるまでの分数。
         * ウィジェット・早期完了の提案・「暇なとき」ダイアログの既定値に使う。
         * 次の予定が無ければ 60分。短すぎ/長すぎは 15〜240 に丸める。
         */
        fun freeMinutesUntilNext(
            events: List<ScheduledEvent>,
            excludeId: Long? = null,
            now: Long = System.currentTimeMillis()
        ): Int {
            val next = events
                .filter { !it.isCompleted && it.id != excludeId }
                .mapNotNull { it.startAsDate()?.time }
                .filter { it > now }
                .minOrNull() ?: return 60
            return ((next - now) / 60000L).toInt().coerceIn(15, 240)
        }

        /** 壊れたキャッシュで落ちないよう、失敗時は空リストを返す */
        fun fromJsonArray(json: String): List<ScheduledEvent> = try {
            val arr = JSONArray(json)
            val list = ArrayList<ScheduledEvent>(arr.length())
            for (i in 0 until arr.length()) list.add(from(arr.getJSONObject(i)))
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
}
