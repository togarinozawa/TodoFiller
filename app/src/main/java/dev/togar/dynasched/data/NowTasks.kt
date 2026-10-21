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

    data class Now(val place: String, val minutes: Int, val items: List<SuggestItem>)

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
    fun pick(ctx: Context, filter: TaskFilter?): Now {
        val loc = Prefs.widgetLoc(ctx)
        val minutes = availableMinutes(ctx).coerceAtLeast(5)
        val repo = Repo.current(ctx)
        val items = repo.getSuggestions(ctx, loc, minutes, 100, emptySet())
        val f = filter?.takeUnless { it.isEmpty } ?: return Now(loc, minutes, items)
        val allowed = f.leafIds(repo.getHobby(ctx))
        return Now(loc, minutes, items.filter { it.kind == "hobby" && it.id in allowed })
    }

    // ---- 外へ出す形（ドパチル向け）。中の呼び名をそのまま出さない ----

    const val KIND_TASK = "task"
    const val KIND_STUDY = "study"

    /** 中の種類（hobby / material）を外の呼び名へ */
    fun externalKind(kind: String): String = if (kind == "material") KIND_STUDY else KIND_TASK

    fun internalKind(kind: String?): String = if (kind == KIND_STUDY) "material" else "hobby"

    /** なぜこれか、の一言。警告画面で「なんでこれ？」と思わせないため */
    fun reason(item: SuggestItem): String =
        if (item.kind == "material") "予定（教材）"
        else "優先度 " + TaskFilter.priorityLabel(TaskFilter.priorityBand(item.priority))
}
