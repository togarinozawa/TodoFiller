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
    val completed: Boolean = false,
    /** 「今日片付けたもの」を名前で見せるため */
    val name: String = ""
)

/**
 * 今日の進み具合。カレンダー画面の上に出す。
 *
 * [big] は大きく見せる数で、[emphasis] によって中身が変わる。
 * 目標までが遠いうちは「片付けた数」、近づいたら「目標まであと何件」を大きくする。
 * どちらが励みになるかは段階で違う（始めは積み上がりを、終わり際は残りを見たい）。
 */
data class DayProgress(
    val done: Int,
    val goal: Int,
    val added: Int,
    val undone: Int,
    val toGoal: Int,
    val emphasis: Stats.Emphasis,
    val big: Int,
    val bigLabel: String
) {
    /** 今日の目標に届いたか */
    val reached: Boolean get() = emphasis == Stats.Emphasis.REACHED
}

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

    /** 進み具合で何を大きく見せるか */
    enum class Emphasis { DONE, REMAINING, REACHED }

    /** ある日に片付けた数 */
    data class DayCount(val date: LocalDate, val done: Int)

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

    /**
     * 今日の進み具合。[goal] が0以下なら1として扱う（目標0は「何もしなくて達成」になってしまう）。
     *
     * 残りが片付けた数より少なくなったら、残りの方を大きく見せる。
     */
    fun today(rows: List<StatRow>, today: LocalDate, goal: Int): DayProgress {
        val g = maxOf(goal, 1)
        val s = count(rows, from(today, Span.TODAY))
        val toGoal = maxOf(g - s.done, 0)
        val emphasis = when {
            toGoal == 0 -> Emphasis.REACHED
            s.done < toGoal -> Emphasis.DONE
            else -> Emphasis.REMAINING
        }
        return DayProgress(
            done = s.done, goal = g, added = s.added, undone = s.remaining, toGoal = toGoal,
            emphasis = emphasis,
            big = if (emphasis == Emphasis.REMAINING) toGoal else s.done,
            bigLabel = if (emphasis == Emphasis.REMAINING) "件で今日の目標" else "件 片付けた"
        )
    }

    /**
     * 目標の初期値。**昨日までの [days] 日の真ん中の日**（中央値）に合わせる。
     * 平均にすると、たまに頑張った日に引っ張られて毎日届かない目標になる。
     * 記録が無ければ3。1〜10に収める。
     */
    fun suggestGoal(rows: List<StatRow>, today: LocalDate, days: Int = 14): Int {
        if (rows.isEmpty()) return 3
        val counts = daily(rows, today.minusDays(1), days).map { it.done }.sorted()
        if (counts.isEmpty()) return 3
        return counts[counts.size / 2].coerceIn(1, 10)
    }

    /** 今日片付けたものの名前。新しい順に [limit] 件 */
    fun doneToday(rows: List<StatRow>, today: LocalDate, limit: Int = 3): List<String> {
        val day = today.toString()
        return rows.filter {
            val at = it.completedAt?.trim().orEmpty()
            it.completed && it.name.isNotBlank() && at.length >= 10 && at.startsWith(day)
        }.sortedByDescending { it.completedAt }.take(limit).map { it.name }
    }

    /** [today] までの [days] 日ぶん、日ごとに片付けた数（古い順） */
    fun daily(rows: List<StatRow>, today: LocalDate, days: Int): List<DayCount> {
        val byDay = doneByDay(rows)
        return (days - 1 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            DayCount(d, byDay[d.toString()] ?: 0)
        }
    }

    /**
     * 続いている日数。**休みを少しだけ許す**（7日につき1日）。
     * 1日休んだだけで0に戻ると、そこで心が折れて続ける意味が無くなるため。
     * 今日まだ何もしていなければ、昨日から数える（朝の時点で0と出さない）。
     */
    fun streak(rows: List<StatRow>, today: LocalDate): Int {
        val byDay = doneByDay(rows)
        fun doneOn(d: LocalDate) = byDay[d.toString()] ?: 0
        var day = if (doneOn(today) > 0) today else today.minusDays(1)
        var misses = 0
        var walked = 0
        var streak = 0
        while (walked <= 3650) {
            if (doneOn(day) > 0) streak++
            else {
                if (misses >= walked / 7 + 1) break
                misses++
            }
            walked++
            day = day.minusDays(1)
        }
        return streak
    }

    private fun doneByDay(rows: List<StatRow>): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (r in rows) {
            if (!r.completed) continue
            val at = r.completedAt?.trim().orEmpty()
            if (at.length < 10) continue
            val d = at.substring(0, 10)
            out[d] = (out[d] ?: 0) + 1
        }
        return out
    }
}
