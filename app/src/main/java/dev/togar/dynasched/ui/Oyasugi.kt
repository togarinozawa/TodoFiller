package dev.togar.dynasched.ui

import dev.togar.dynasched.api.HobbyItem

/** 片付けた時に入るオヤスギ。[isGroup] はまとまり（親）を丸ごと片付けた時 */
data class Award(val taskId: Long, val amount: Int, val isGroup: Boolean) {
    fun label(): String = if (isGroup) "まとまり達成 +$amount" else "+$amount"
}

/**
 * オヤスギ（ポイント）の数え方。**今は貯めるだけ**で、使い道は本人の指示待ち。
 *
 * 1件片付けると「下に何段あるか＋1」が入る。葉なら1、子を持つ親なら2…。
 * 親の配下が全部片付いた時は、親のぶんも入る（まとまり達成）。
 * 同じタスクで二度は入らない（外して付け直しても増えない）。
 */
object Oyasugi {

    /** その下に何段あるか。葉は0 */
    fun height(all: List<HobbyItem>, id: Long): Int {
        val byParent = all.groupBy { it.parentId }
        fun walk(x: Long, seen: MutableSet<Long>): Int {
            if (!seen.add(x)) return 0
            val kids = byParent[x].orEmpty()
            if (kids.isEmpty()) return 0
            return (kids.maxOfOrNull { walk(it.id, seen) } ?: 0) + 1
        }
        return walk(id, HashSet())
    }

    fun value(all: List<HobbyItem>, id: Long): Int = height(all, id) + 1

    /**
     * [completedId] を片付けた時に入るもの。[already] は既に入ったタスク。
     * 親をたどり、配下が全部済んでいる間は親のぶんも足す。
     */
    fun awards(all: List<HobbyItem>, completedId: Long, already: Set<Long>): List<Award> {
        val byId = all.associateBy { it.id }
        val item = byId[completedId] ?: return emptyList()
        if (!item.isCompleted) return emptyList()
        val out = ArrayList<Award>()
        val given = HashSet(already)
        if (given.add(completedId)) out.add(Award(completedId, value(all, completedId), false))
        var parent = item.parentId
        val seen = HashSet<Long>()
        while (parent != null && seen.add(parent)) {
            val p = byId[parent] ?: break
            if (!allDone(all, parent)) break
            if (given.add(parent)) out.add(Award(parent, value(all, parent), true))
            parent = p.parentId
        }
        return out
    }

    /** その配下の葉が全部済んでいるか（葉そのものなら自分が済んでいるか） */
    fun allDone(all: List<HobbyItem>, id: Long): Boolean {
        val byParent = all.groupBy { it.parentId }
        val byId = all.associateBy { it.id }
        fun walk(x: Long, seen: MutableSet<Long>): Boolean {
            if (!seen.add(x)) return true
            val kids = byParent[x].orEmpty()
            if (kids.isEmpty()) return byId[x]?.isCompleted == true
            return kids.all { walk(it.id, seen) }
        }
        return walk(id, HashSet())
    }

    /** 片付けた直後に一瞬出す一言 */
    fun popText(awards: List<Award>): String {
        if (awards.isEmpty()) return ""
        val sum = awards.sumOf { it.amount }
        return if (awards.any { it.isGroup }) "+$sum オヤスギ（まとまり達成）" else "+$sum オヤスギ"
    }
}

/**
 * 手が止まっていそうなタスク。分けることを勧める印に使う。
 * 一度に片付けるには長い（[LONG_MINUTES]分以上）か、[OLD_DAYS]日以上そのまま。
 */
object Stuck {
    const val LONG_MINUTES = 45
    const val OLD_DAYS = 7

    fun isStuck(
        item: HobbyItem, hasChildren: Boolean, today: java.time.LocalDate,
        longMinutes: Int = LONG_MINUTES, oldDays: Int = OLD_DAYS
    ): Boolean {
        if (hasChildren || item.isCompleted) return false
        if (item.durationMinutes >= longMinutes) return true
        return isOld(item.createdAt, today, oldDays)
    }

    fun reason(item: HobbyItem, today: java.time.LocalDate): String = when {
        item.durationMinutes >= LONG_MINUTES -> "${item.durationMinutes}分は一度に片付けるには長い"
        isOld(item.createdAt, today, OLD_DAYS) -> "${OLD_DAYS}日以上そのままになっている"
        else -> ""
    }

    private fun isOld(createdAt: String?, today: java.time.LocalDate, days: Int): Boolean {
        val s = createdAt?.trim().orEmpty()
        if (s.length < 10) return false
        return s.substring(0, 10) <= today.minusDays(days.toLong()).toString()
    }
}

/** 文中の `**ここ**` を太字にする。使い方の文と同じ書き方で強調できるように */
object Emphasis {

    fun segments(text: String): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        var rest = text
        while (true) {
            val a = rest.indexOf("**")
            val b = if (a < 0) -1 else rest.indexOf("**", a + 2)
            if (a < 0 || b < 0) break
            if (a > 0) out.add(rest.substring(0, a) to false)
            out.add(rest.substring(a + 2, b) to true)
            rest = rest.substring(b + 2)
        }
        if (rest.isNotEmpty()) out.add(rest to false)
        return out
    }

    fun render(text: String): CharSequence {
        val sb = android.text.SpannableStringBuilder()
        for ((part, bold) in segments(text)) {
            val start = sb.length
            sb.append(part)
            if (bold) sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }
}
