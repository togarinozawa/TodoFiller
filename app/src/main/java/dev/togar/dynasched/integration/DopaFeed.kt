package dev.togar.dynasched.integration

import android.content.Context
import android.database.MatrixCursor
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.api.HobbyItem
import dev.togar.dynasched.data.Repo
import dev.togar.dynasched.db.LocalDb
import dev.togar.dynasched.db.bool
import dev.togar.dynasched.db.int
import dev.togar.dynasched.db.long
import dev.togar.dynasched.db.longOrNull
import dev.togar.dynasched.db.mapRows
import dev.togar.dynasched.db.str
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * ドパチル（FlexHarness）が [NowTasksProvider] から読む中身。読むだけ。
 * 列の名前と意味は docs/dopachiru-tasks.md が正。形を変えたら [API_VERSION] を上げる。
 *
 * 時刻はすべて UTC のエポック「秒」。日付だけのもの（締切・やる日）は、その日の終わり（23:59:59）にする。
 * 完了・中断はスキマスでだけ付ける（ユーザーが決めた）。ここから書き換える口は作らない。
 */
object DopaFeed {

    /** 1: /now だけ。2: /schedule /deadlines /remaining /day を足した */
    const val API_VERSION = 2

    /** 終わった物を /deadlines に残す日数。向こうが「終わった」に気づいて集中を解けるように */
    const val KEEP_DONE_DAYS = 3L

    val SCHEDULE = arrayOf("id", "kind", "startAt", "endAt", "done", "title", "refId")
    val DEADLINES = arrayOf(
        "id", "kind", "title", "dueAt", "dueSource", "wantAt", "wantStrength",
        "priority", "estimateMinutes", "progress", "doneAt"
    )
    val REMAINING = arrayOf("id", "title", "priority", "estimateMinutes", "scheduledStartAt")
    val DAY = arrayOf("wakeMinute", "bedtimeMinute", "apiVersion")

    /** 今日と明日（端末の日付）の全予定。id は学習予定のブロードキャストと同じ値（向こうが重ねる鍵） */
    fun schedule(ctx: Context): MatrixCursor {
        val today = LocalDate.now()
        val c = MatrixCursor(SCHEDULE)
        LocalDb.get(ctx).readableDatabase.rawQuery(
            "SELECT e.*, COALESCE(h.is_completed,0) AS task_done FROM scheduled_events e " +
                "LEFT JOIN hobby_tasks h ON h.id = e.hobby_task_id " +
                "WHERE e.start_datetime >= ? AND e.start_datetime < ? ORDER BY e.start_datetime",
            arrayOf("${today}T00:00:00", "${today.plusDays(2)}T00:00:00")
        ).mapRows { r ->
            val kind = r.str("event_type", "study")
            val ref = if (kind == "study") r.longOrNull("material_id")?.let { "material_$it" }
                else r.longOrNull("hobby_task_id")?.let { "task_$it" }
            arrayOf<Any?>(
                "sched_${r.long("id")}", kind,
                epoch(r.str("start_datetime")), epoch(r.str("end_datetime")),
                if (r.bool("is_completed") || r.bool("task_done")) 1 else 0,
                r.str("title"), ref.orEmpty()
            )
        }.forEach { c.addRow(it) }
        return c
    }

    /**
     * 締切のある物。教材の締切と、タスクの締切・やる日。
     * 終わった物は [KEEP_DONE_DAYS] 日だけ doneAt を付けて残す。
     */
    fun deadlines(ctx: Context): MatrixCursor {
        val c = MatrixCursor(DEADLINES)
        val keepFrom = LocalDate.now().minusDays(KEEP_DONE_DAYS).atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        val repo = Repo.current(ctx)
        val db = LocalDb.get(ctx).readableDatabase

        for (m in repo.getMaterials(ctx)) {
            val due = epochOfDeadline(m.deadline) ?: continue
            val doneAt = if (m.remainingProblems > 0) 0L else lastAttempt(ctx, m.id)
            if (doneAt in 1 until keepFrom) continue
            c.addRow(arrayOf<Any?>(
                "material_${m.id}", "study", m.name, due, "due", 0L, 0,
                m.priority, m.remainingMinutes.coerceAtLeast(0), "${m.doneProblems}/${m.totalProblems}", doneAt
            ))
        }

        val all = repo.getHobby(ctx)
        val parents = all.mapNotNullTo(HashSet()) { it.parentId }
        val completedAt = db.rawQuery("SELECT id, completed_at FROM hobby_tasks WHERE is_completed=1", null)
            .mapRows { it.long("id") to it.str("completed_at") }.toMap()
        for (t in all) {
            if (t.id in parents) continue
            val want = t.wantDate.takeIf { it.isNotEmpty() && t.wantLevel > 0 }
            if (t.dueDate.isEmpty() && want == null) continue
            val doneAt = if (t.isCompleted) epoch(completedAt[t.id].orEmpty()) else 0L
            if (t.isCompleted && (doneAt == 0L || doneAt < keepFrom)) continue
            // 締切があればそれ。無ければやる日の終わり（どちらから来たかは dueSource）
            val dueAt = endOfDay(t.dueDate.ifEmpty { want!! })
            c.addRow(arrayOf<Any?>(
                "task_${t.id}", "hobby", title(all, t), dueAt,
                if (t.dueDate.isNotEmpty()) "due" else "want",
                want?.let { endOfDay(it) } ?: 0L, if (want != null) t.wantLevel else 0,
                t.priority, t.durationMinutes, "", doneAt
            ))
        }
        return c
    }

    /** 今日の残りタスク。やる日・締切が今日まで（か過ぎた）か、今日に置かれていて、まだ済んでいない物 */
    fun remaining(ctx: Context): MatrixCursor {
        val today = LocalDate.now()
        val t0 = today.toString()
        val c = MatrixCursor(REMAINING)
        val all = Repo.current(ctx).getHobby(ctx)
        val parents = all.mapNotNullTo(HashSet()) { it.parentId }
        val placed = LocalDb.get(ctx).readableDatabase.rawQuery(
            "SELECT hobby_task_id, MIN(start_datetime) s FROM scheduled_events " +
                "WHERE event_type='hobby' AND COALESCE(is_completed,0)=0 AND start_datetime >= ? AND start_datetime < ? " +
                "GROUP BY hobby_task_id",
            arrayOf("${today}T00:00:00", "${today.plusDays(1)}T00:00:00")
        ).mapRows { it.long("hobby_task_id") to it.str("s") }.toMap()
        all.filter { t ->
            t.id !in parents && !t.isCompleted && (
                t.id in placed ||
                    (t.wantDate.isNotEmpty() && t.wantLevel > 0 && t.wantDate <= t0) ||
                    (t.dueDate.isNotEmpty() && t.dueDate <= t0)
                )
        }.sortedWith(compareBy<HobbyItem> { placed[it.id] ?: "￿" }.thenByDescending { it.priority })
            .forEach { t ->
                c.addRow(arrayOf<Any?>(
                    "task_${t.id}", title(all, t), t.priority, t.durationMinutes,
                    placed[t.id]?.let { epoch(it) } ?: 0L
                ))
            }
        return c
    }

    /** 1行だけ。起きる・寝る時刻（0時からの分）と、形の版 */
    fun day(ctx: Context): MatrixCursor = MatrixCursor(DAY).apply {
        addRow(arrayOf<Any?>(Prefs.wakeMinutes(ctx), Prefs.bedtimeMinutes(ctx), API_VERSION))
    }

    /** 分けた子は名前だけだと何の話か分からないので、親を前に付ける（「英語 › 長文」） */
    private fun title(all: List<HobbyItem>, t: HobbyItem): String {
        val parent = dev.togar.dynasched.data.NowTasks.parentPath(all, t.id)
        return if (parent.isEmpty()) t.name else "$parent › ${t.name}"
    }

    private fun lastAttempt(ctx: Context, materialId: Long): Long =
        LocalDb.get(ctx).readableDatabase.rawQuery(
            "SELECT MAX(done_at) d FROM attempts WHERE material_id=?", arrayOf(materialId.toString())
        ).mapRows { epoch(it.str("d")) }.firstOrNull() ?: 0L

    // ---- 時刻（テストで固定する） ----

    /** 端末のローカル日時（`yyyy-MM-ddTHH:mm:ss` か、間が空白の形）を UTC 秒に。読めなければ 0 */
    fun epoch(local: String, zone: ZoneId = ZoneId.systemDefault()): Long = try {
        LocalDateTime.parse(local.trim().replace(' ', 'T')).atZone(zone).toEpochSecond()
    } catch (e: Exception) {
        0L
    }

    /** 日付（yyyy-MM-dd）の終わり（23:59:59）を UTC 秒に */
    fun endOfDay(date: String, zone: ZoneId = ZoneId.systemDefault()): Long = try {
        LocalDate.parse(date).atTime(23, 59, 59).atZone(zone).toEpochSecond()
    } catch (e: Exception) {
        0L
    }

    /** 教材の締切（`yyyy-MM-ddTHH:mm` か日付だけ）を UTC 秒に。日付だけならその日の終わり */
    fun epochOfDeadline(deadline: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val d = deadline.trim()
        val v = when {
            d.length == 10 -> endOfDay(d, zone)
            d.length == 16 -> epoch("$d:00", zone)
            else -> epoch(d, zone)
        }
        return v.takeIf { it > 0 }
    }
}
