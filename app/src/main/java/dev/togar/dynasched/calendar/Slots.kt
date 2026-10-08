package dev.togar.dynasched.calendar

import java.time.LocalDate

/**
 * アプリの中で決める「タスクを置いてよい時間」。カレンダー画面でドラッグして作る。
 *
 * 曜日で繰り返す枠（[weekday] 1=月〜7=日、[date] 空）と、その日だけの枠（[date] あり）がある。
 * [isBlock] はその日だけ「ここは置かない」と穴を開ける印（繰り返しの枠を1日だけ外す時に使う）。
 * 時刻は0時からの分。
 */
data class Slot(
    val id: Long = 0,
    val weekday: Int = 0,
    val date: String = "",
    val startMin: Int = 0,
    val endMin: Int = 0,
    val location: String = "home",
    val isBlock: Boolean = false
) {
    val isWeekly: Boolean get() = weekday in 1..7 && date.isEmpty()

    fun minutes(): Int = (endMin - startMin).coerceAtLeast(0)

    fun label(places: List<dev.togar.dynasched.Place>): String =
        "${hhmm(startMin)}-${hhmm(endMin)} ${dev.togar.dynasched.Places.name(places, location)}"

    companion object {
        fun hhmm(min: Int): String {
            val h = (min / 60).coerceIn(0, 23)
            val m = min % 60
            return String.format(java.util.Locale.US, "%02d:%02d", h, m)
        }
    }
}

/** ある日に当てはめた枠 */
data class DaySlot(val date: LocalDate, val startMin: Int, val endMin: Int, val location: String)

object Slots {

    /** ドラッグで動かす刻み（分） */
    const val STEP = 15

    /**
     * [from] から [days] 日ぶん、枠をその日の区間に広げる。
     * その日だけの穴（[Slot.isBlock]）を抜き、同じ場所で重なる区間はまとめる。
     */
    fun expand(slots: List<Slot>, from: LocalDate, days: Int): List<DaySlot> {
        val out = ArrayList<DaySlot>()
        for (i in 0 until days.coerceAtLeast(0)) {
            val d = from.plusDays(i.toLong())
            val ds = d.toString()
            val wd = d.dayOfWeek.value
            var list: List<DaySlot> = slots.filter {
                !it.isBlock && ((it.isWeekly && it.weekday == wd) || it.date == ds) && it.minutes() > 0
            }.map { DaySlot(d, it.startMin, it.endMin, it.location) }
            for (b in slots.filter { it.isBlock && it.date == ds }) {
                list = list.flatMap { cut(it, b.startMin, b.endMin) }
            }
            out.addAll(merge(list))
        }
        return out
    }

    private fun cut(s: DaySlot, from: Int, to: Int): List<DaySlot> {
        if (to <= s.startMin || from >= s.endMin) return listOf(s)
        val out = ArrayList<DaySlot>(2)
        if (from > s.startMin) out.add(s.copy(endMin = from))
        if (to < s.endMin) out.add(s.copy(startMin = to))
        return out
    }

    /** 同じ場所で重なる・接する区間をつなぐ */
    fun merge(list: List<DaySlot>): List<DaySlot> {
        if (list.size <= 1) return list
        val sorted = list.sortedWith(compareBy({ it.location }, { it.startMin }))
        val out = ArrayList<DaySlot>()
        var cur = sorted.first()
        for (s in sorted.drop(1)) {
            if (s.location == cur.location && s.startMin <= cur.endMin) {
                cur = cur.copy(endMin = maxOf(cur.endMin, s.endMin))
            } else {
                out.add(cur)
                cur = s
            }
        }
        out.add(cur)
        return out.sortedBy { it.startMin }
    }

    /**
     * スケジューラに渡す形にする。場所はそのまま渡す（家・外・設定で足した場所）。
     * 「どこでも」の枠は家として扱う（どの場所のタスクでも入る枠は作れないため）。
     */
    fun toWindows(daySlots: List<DaySlot>): List<AvailabilityWindow> = daySlots.map {
        AvailabilityWindow(
            at(it.date, it.startMin), at(it.date, it.endMin),
            if (it.location == dev.togar.dynasched.Places.ANYWHERE) dev.togar.dynasched.Places.HOME
                else it.location,
            "枠", 0L
        )
    }

    /** 刻みに丸める */
    fun snap(min: Int): Int = (Math.round(min / STEP.toFloat()) * STEP).coerceIn(0, 1440)

    private fun at(date: LocalDate, min: Int): String {
        // 24:00 は翌日の0時。文字列で「T24:00」と書くと日付の比較が狂う
        if (min >= 1440) return "${date.plusDays(1)}T00:00:00"
        return "${date}T${Slot.hhmm(min)}:00"
    }
}
