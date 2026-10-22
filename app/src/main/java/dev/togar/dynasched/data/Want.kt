package dev.togar.dynasched.data

import java.time.LocalDate

/**
 * やる日。「明日これをやる」と決めておく（何時にやるかは配置に任せる）。
 *
 * - **なるべく**: その日の枠に、ほかのタスクより先に置く。入らなければ次の日以降へ
 * - **絶対**: その日の枠に入らなければ、枠の外でも空いている時間（起きている間で、予定の無い所）に置く
 *
 * どちらも**その日より前には置かない。**「明日やる」と決めた物が今日の空きに
 * 入ってしまうと、決めた意味が無くなる。日が過ぎて残っている物は、毎日いちばん先に置く。
 */
object Want {

    const val NONE = 0
    const val PREFER = 1
    const val MUST = 2

    fun levelLabel(level: Int): String = when (level) {
        MUST -> "絶対"
        PREFER -> "なるべく"
        else -> ""
    }

    /** 「今日」「明日」「10/25(土)」。過ぎていれば「10/19(日)の残り」 */
    fun dayLabel(date: String, today: LocalDate = LocalDate.now()): String {
        val d = try { LocalDate.parse(date) } catch (e: Exception) { return "" }
        val wd = "月火水木金土日"[d.dayOfWeek.value - 1]
        return when {
            d == today -> "今日"
            d == today.plusDays(1) -> "明日"
            d.isBefore(today) -> "${d.monthValue}/${d.dayOfMonth}($wd)の残り"
            else -> "${d.monthValue}/${d.dayOfMonth}($wd)"
        }
    }

    /** 一覧に出す一言。決めていなければ空 */
    fun badge(date: String, level: Int, today: LocalDate = LocalDate.now()): String =
        if (date.isEmpty() || level <= NONE) "" else "📌 ${dayLabel(date, today)}・${levelLabel(level)}"

    /** その日に置いてよいか。決めた日より前は置かない */
    fun allowedOn(date: String, day: String): Boolean = date.isEmpty() || day >= date

    /** その日の順位。決めた日（か、過ぎた日）なら強さ、それ以外は 0。大きいほど先 */
    fun rankOn(date: String, level: Int, day: String): Int =
        if (date.isEmpty() || level <= NONE || day < date) 0 else level.coerceAtMost(MUST)
}
