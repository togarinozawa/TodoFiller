package dev.togar.dynasched.ui

import java.time.LocalDate

/**
 * 「どれだけ片付いたか・どれだけ増えたか」を数える。**DBも画面も触らない。**
 *
 * 数え方を画面から切り離してあるのは、ここが**境界で間違えやすい**ため。
 * 今日の分は「今日の0時以降」であって「24時間以内」ではない。期間の端を1日ずらすと
 * 数字が静かに合わなくなり、しかも見ただけでは気付けない。
 *
 * 日時は端末が `yyyy-MM-dd HH:mm:ss` で持っているので、**文字列の大小比較で足りる。**
 * 日付を解析し直すと、書式のゆれで落ちる場所が増えるだけになる。
 */

/** 数えるのに要る分だけ */
data class StatRow(
    val createdAt: String? = null,
    val completedAt: String? = null,
    val completed: Boolean = false
)

/**
 * [added] 期間内に増えた数、[done] 期間内に片付けた数、[remaining] いま残っている数。
 *
 * [remaining] だけは期間と関係ない。「あと何個あるか」は今の話なので。
 */
data class TaskStats(val added: Int = 0, val done: Int = 0, val remaining: Int = 0) {

    /** ウィジェットや見出しに出す一行 */
    fun line(): String = "済 $done ・ 追加 $added ・ 残り $remaining"
}

object Stats {

    /** よく使う区切り。ラベルは画面にそのまま出す */
    enum class Span(val label: String, val days: Int) {
        TODAY("今日", 1),
        WEEK("7日", 7),
        MONTH("30日", 30)
    }

    /**
     * [from] 以降を数える（その日を含む）。`yyyy-MM-dd` で渡すこと。
     *
     * **作成日時を持たない行は「増えた」に数えない。**この列は後から足したので、
     * それ以前のタスクは記録が無い。0件のところを古いタスクで水増しするより、
     * 数えない方が正しい。
     */
    fun count(rows: List<StatRow>, from: String): TaskStats {
        var added = 0
        var done = 0
        var remaining = 0
        for (r in rows) {
            if (!r.completed) remaining++
            if (inRange(r.createdAt, from)) added++
            if (r.completed && inRange(r.completedAt, from)) done++
        }
        return TaskStats(added = added, done = done, remaining = remaining)
    }

    /** `yyyy-MM-dd HH:mm:ss` の先頭10文字を日付として比べる */
    private fun inRange(at: String?, from: String): Boolean {
        val s = at?.trim().orEmpty()
        if (s.length < 10) return false
        return s.substring(0, 10) >= from
    }

    /**
     * 期間の始まりの日。[Span.TODAY] は今日そのもの、7日なら6日前から
     * （**今日を含めて7日**。7日前からにすると8日分を数えてしまう）。
     */
    fun from(today: LocalDate, span: Span): String =
        today.minusDays((span.days - 1).toLong()).toString()
}
