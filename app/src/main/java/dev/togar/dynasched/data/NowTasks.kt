package dev.togar.dynasched.data

import android.content.Context
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.api.ScheduledEvent
import dev.togar.dynasched.api.SuggestItem
import dev.togar.dynasched.ui.TaskFilter

/**
 * 「いまやること」を選ぶ。いまいる場所（ウィジェットと共有）と、次の予定までの時間で。
 *
 * タスク画面の上の欄と、ドパチルが読みに来る口（[dev.togar.dynasched.integration.NowTasksProvider]）が
 * 同じものを出すように、ここに一つだけ置く。片方だけ直して食い違うと、
 * 「スキマスではこれと言われ、ドパチルでは別のものを言われる」になる。
 */
object NowTasks {

    /** [parents] はタスクのID → 親の並び（「英語 › 長文」）。分けた子は名前だけだと何の話か分からない */
    data class Now(
        val place: String, val minutes: Int, val items: List<SuggestItem>,
        val parents: Map<Long, String> = emptyMap(),
        /** 候補のタスクそのもの（メモ・タグ・やる日を見せるため） */
        val tasks: Map<Long, dev.togar.dynasched.api.HobbyItem> = emptyMap()
    )

    /** 親から順に、祖先の名前を「 › 」でつなぐ。最上位なら空 */
    fun parentPath(all: List<dev.togar.dynasched.api.HobbyItem>, id: Long): String {
        val byId = all.associateBy { it.id }
        val names = ArrayList<String>()
        val seen = HashSet<Long>()
        var p = byId[id]?.parentId
        while (p != null && seen.add(p)) {
            val x = byId[p] ?: break
            names.add(x.name)
            p = x.parentId
        }
        return names.asReversed().joinToString(" › ")
    }

    /** いま使える時間。次の予定まで（分からなければ30分）、ただし就寝時刻は越えない */
    fun availableMinutes(ctx: Context): Int {
        val untilBed = minutesUntilBedtime(ctx)
        return (Prefs.cachedEvents(ctx)
            ?.let { ScheduledEvent.freeMinutesUntilNext(ScheduledEvent.fromJsonArray(it)) }
            ?: 30).let { if (untilBed in 1 until it) untilBed else it }
    }

    /** 就寝時刻まであと何分か。すでに過ぎていれば 0 */
    fun minutesUntilBedtime(ctx: Context): Int {
        val now = java.util.Calendar.getInstance()
        val nowMin = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        return (Prefs.bedtimeMinutes(ctx) - nowMin).coerceAtLeast(0)
    }

    /**
     * 候補を良い順に。ワーカースレッドから呼ぶ。
     * [filter] で絞ると、当てはまるタスクだけ（教材にはタグも場所の絞り込みも無いので外す）
     */
    fun pick(ctx: Context, filter: TaskFilter?, minutesOverride: Int? = null): Now {
        val loc = Prefs.widgetLoc(ctx)
        val minutes = (minutesOverride ?: availableMinutes(ctx)).coerceAtLeast(5)
        val repo = Repo.current(ctx)
        val all = repo.getHobby(ctx)
        var items = repo.getSuggestions(ctx, loc, minutes, 100, emptySet())
        val f = filter?.takeUnless { it.isEmpty }
        if (f != null) {
            val allowed = f.leafIds(all, dev.togar.dynasched.Priorities.all(ctx))
            items = items.filter { it.kind == "hobby" && it.id in allowed }
        }
        val hobbyIds = items.filter { it.kind == "hobby" }.mapTo(HashSet()) { it.id }
        val parents = hobbyIds.associateWith { parentPath(all, it) }.filterValues { it.isNotEmpty() }
        return Now(loc, minutes, items, parents, all.filter { it.id in hobbyIds }.associateBy { it.id })
    }

    // ---- 外へ出す形（ドパチル向け）。中の呼び名をそのまま出さない ----

    const val KIND_TASK = "task"
    const val KIND_STUDY = "study"

    /** 中の種類（hobby / material）を外の呼び名へ */
    fun externalKind(kind: String): String = if (kind == "material") KIND_STUDY else KIND_TASK

    fun internalKind(kind: String?): String = if (kind == KIND_STUDY) "material" else "hobby"

    /** なぜこれか、の一言。警告画面で「なんでこれ？」と思わせないため。目安があれば添える */
    fun reason(
        item: SuggestItem,
        levels: List<dev.togar.dynasched.PriorityLevel> = dev.togar.dynasched.Priorities.DEFAULT
    ): String =
        if (item.kind == "material") "予定（教材）"
        else "優先度 " + dev.togar.dynasched.Priorities.levelOf(levels, item.priority).label
}
