package dev.togar.dynasched.data

import java.time.LocalDate

/**
 * 習慣。決まった日に単発タスクを1件作る元。
 *
 * [kind] は `daily`（毎日）/ `weekly`（[weekdays] の曜日。1=月〜7=日）/
 * `monthly`（毎月 [dayOfMonth] 日。その月に無い日なら月末）。
 * [lastMade] は最後に作った日（`yyyy-MM-dd`）。同じ日に二度作らないため。
 */
data class Routine(
    val id: Long = 0,
    val name: String = "",
    val kind: String = "daily",
    val weekdays: Set<Int> = emptySet(),
    val dayOfMonth: Int = 1,
    val durationMinutes: Int = 30,
    val priority: Int = 5,
    val location: String = "anywhere",
    val color: String = "",
    val tags: String = "",
    val lastMade: String = "",
    val isActive: Boolean = true
) {
    fun scheduleLabel(): String = when (kind) {
        "weekly" -> if (weekdays.isEmpty()) "毎週（曜日未設定）"
            else "毎週 " + weekdays.sorted().joinToString("") { Routines.WEEKDAY_NAMES[it - 1] }
        "monthly" -> "毎月 ${dayOfMonth}日"
        else -> "毎日"
    }

    fun summary(): String = "${scheduleLabel()} ・ ${durationMinutes}分"
}

object Routines {

    val WEEKDAY_NAMES = arrayOf("月", "火", "水", "木", "金", "土", "日")

    /**
     * 今日作るか。**前に作った物がまだ片付いていなければ作らない**（[hasOpen]）。
     * 溜まった習慣が毎日1件ずつ増えていくと、一覧が同じ名前で埋まって見る気が無くなる。
     */
    fun isDue(routine: Routine, today: LocalDate, hasOpen: Boolean): Boolean {
        if (!routine.isActive || hasOpen || routine.lastMade == today.toString()) return false
        return matches(routine, today)
    }

    fun matches(routine: Routine, date: LocalDate): Boolean = when (routine.kind) {
        "weekly" -> date.dayOfWeek.value in routine.weekdays
        "monthly" -> date.dayOfMonth == routine.dayOfMonth.coerceIn(1, date.lengthOfMonth())
        else -> true
    }

    /** [from] 以降で次に当たる日。止めている習慣は null */
    fun nextDate(routine: Routine, from: LocalDate): LocalDate? {
        if (!routine.isActive) return null
        for (i in 0 until 367) {
            val d = from.plusDays(i.toLong())
            if (matches(routine, d)) return d
        }
        return null
    }

    fun encodeWeekdays(days: Set<Int>): String = days.filter { it in 1..7 }.sorted().joinToString(",")

    fun decodeWeekdays(raw: String?): Set<Int> =
        (raw ?: "").split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet()
}

/** オヤスギの合計と今日の分 */
data class PointsSummary(val total: Int = 0, val today: Int = 0)
