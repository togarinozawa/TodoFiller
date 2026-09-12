package dev.togar.dynasched.data

import android.content.Context
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.api.HobbyItem
import dev.togar.dynasched.api.MaterialItem
import dev.togar.dynasched.api.PlanItem
import dev.togar.dynasched.api.PlanResult
import dev.togar.dynasched.api.ScheduledEvent
import dev.togar.dynasched.api.SuggestItem
import dev.togar.dynasched.calendar.CalendarRepo
import dev.togar.dynasched.calendar.CalendarWriter
import dev.togar.dynasched.db.LocalDb
import dev.togar.dynasched.db.bool
import dev.togar.dynasched.db.int
import dev.togar.dynasched.db.long
import dev.togar.dynasched.db.longOrNull
import dev.togar.dynasched.db.mapRows
import dev.togar.dynasched.db.str
import dev.togar.dynasched.db.values
import dev.togar.dynasched.engine.AttemptAgg
import dev.togar.dynasched.engine.HobbyRow
import dev.togar.dynasched.engine.MaterialRow
import dev.togar.dynasched.engine.PlanEngine
import dev.togar.dynasched.engine.Scheduler
import dev.togar.dynasched.engine.StudyEngine
import dev.togar.dynasched.sync.NotionSyncer
import dev.togar.dynasched.ui.Tags
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 端末内で完結する方式。サーバーもGoogleログインも使わない。
 *
 * - 空き時間は端末のカレンダー（CalendarContract）から読む
 * - 教材・実績・予定は端末のSQLiteに持つ
 * - 配置と「間に合うか」の計算は engine/ にある（サーバーの JS から移植）
 *
 * サーバー版の各ルートがやっていたことを、そのままここに集めてある。
 */
object LocalRepo : Repo {

    private fun ymd(d: Date = Date()) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)
    private fun nowNaive() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

    // ---- 予定 ----

    override fun getSchedule(ctx: Context, dateYmd: String): List<ScheduledEvent> {
        val end = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateYmd)?.let {
            ymd(Date(it.time + 7L * 24 * 3600 * 1000))
        } ?: dateYmd
        return LocalDb.get(ctx).readableDatabase.rawQuery(
            "SELECT * FROM scheduled_events WHERE start_datetime >= ? AND start_datetime < ? ORDER BY start_datetime",
            arrayOf("${dateYmd}T00:00:00", "${end}T00:00:00")
        ).mapRows { c ->
            ScheduledEvent(
                id = c.long("id"),
                title = c.str("title"),
                startDatetime = c.str("start_datetime"),
                endDatetime = c.str("end_datetime"),
                eventType = c.str("event_type", "study"),
                isCompleted = c.bool("is_completed"),
                materialId = c.longOrNull("material_id")?.let { if (it > 0) it else null }
            )
        }
    }

    override fun completeTask(ctx: Context, id: Long, problems: Int) {
        val db = LocalDb.get(ctx).writableDatabase
        val ev = db.rawQuery("SELECT * FROM scheduled_events WHERE id=?", arrayOf(id.toString()))
            .mapRows { c ->
                Triple(c.bool("is_completed"), c.longOrNull("material_id"),
                    c.str("start_datetime") to c.str("end_datetime"))
            }.firstOrNull() ?: return

        // すでに完了済みなら実績を積まない。画面と通知の両方から押すと二重に数えられる
        val alreadyDone = ev.first
        db.execSQL(
            "UPDATE scheduled_events SET is_completed=1, completed_at=? WHERE id=?",
            arrayOf(nowNaive(), id)
        )
        val materialId = ev.second ?: return
        if (materialId <= 0 || alreadyDone) return

        // 実際にかかった時間を測る。コマの長さで代用するとペースが遅い側に歪む
        val (startStr, endStr) = ev.third
        val startMs = Scheduler.parseDeadlineMillis(startStr) ?: return
        val endMs = Scheduler.parseDeadlineMillis(endStr) ?: return
        val slot = ((endMs - startMs) / 60000L).toInt().coerceAtLeast(0)
        val elapsed = ((System.currentTimeMillis() - startMs) / 60000L).toInt()
        val minutes = if (elapsed in 1..slot) elapsed else slot

        val n = if (problems >= 0) problems else {
            val eng = engine(ctx)
            val m = materialRow(ctx, materialId) ?: return
            eng.problemsForMinutes(m, eng.stateOf(m), minutes)
        }
        recordAttempt(ctx, materialId, n, minutes, id)
    }

    override fun skipTask(ctx: Context, id: Long) {
        LocalDb.get(ctx).writableDatabase
            .execSQL("DELETE FROM scheduled_events WHERE id=? AND COALESCE(is_completed,0)=0", arrayOf(id))
        runScheduler(ctx, Prefs.fillDays(ctx))
    }

    // ---- 配置 ----

    /**
     * 計画を見る日数。配置する日数（既定7日）より必ず長く取る。
     *
     * **ここを配置日数に合わせると壊れる。** 計画側は締切日までの空き時間を数えて
     * 「間に合うか」を判定し、足りなければ応用を外して周回を減らす。カレンダーを
     * 7日ぶんしか読んでいないと、8日目以降の空きが全部0とみなされ、締切が先の教材ほど
     * 「絶対に間に合わない」と判定されて1周まで削られる。すでに1周終えていれば
     * 残り0問になり、配置対象から丸ごと消える——つまり**予定が生成されなくなる**。
     */
    private const val PLAN_DAYS = 45

    override fun runScheduler(ctx: Context, days: Int): RunReport {
        val db = LocalDb.get(ctx).writableDatabase
        val planDays = maxOf(days, PLAN_DAYS)
        val calId = Prefs.calendarId(ctx) ?: CalendarRepo.targetCalendar(ctx)?.id
        // 計画に必要な日数ぶん読む。配置側は先頭 days 日しか見ないので余分は害にならない
        val snap = CalendarRepo.read(ctx, planDays, calId)
        val materials = materialRows(ctx)
        val hobbies = hobbyRows(ctx)
        val eng = engine(ctx)

        // 先に計画を立てて「応用を外す/周回を減らす」を確定させる。
        // 画面と配置がここで必ず一致する。
        val wake = Prefs.wakeMinutes(ctx)
        val bed = Prefs.bedtimeMinutes(ctx)
        val plan = PlanEngine.build(
            materials, hobbies, snap.windows, snap.busy, eng, planDays,
            wakeStart = wake, wakeEnd = bed
        )
        for ((id, decision) in plan.decisions) {
            db.execSQL(
                "UPDATE materials SET drop_advanced=?, planned_rounds=? WHERE id=?",
                arrayOf(if (decision.first) 1 else 0, decision.second, id)
            )
        }
        val refreshed = materialRows(ctx)

        // 未完了かつこれからの予定だけ作り直す。済んだものは記録なので残す
        db.execSQL(
            "DELETE FROM scheduled_events WHERE COALESCE(is_completed,0)=0 AND start_datetime >= ?",
            arrayOf(nowNaive())
        )

        val placed = Scheduler.run(
            refreshed, hobbies, snap.windows, snap.busy, snap.examPeriods, eng, days,
            wakeStart = wake, wakeEnd = bed
        )
        db.beginTransaction()
        try {
            for (p in placed) {
                db.insert("scheduled_events", null, values(
                    "title" to p.title,
                    "start_datetime" to p.start,
                    "end_datetime" to p.end,
                    "event_type" to p.eventType,
                    "hobby_task_id" to p.hobbyTaskId,
                    "material_id" to p.materialId,
                    "is_completed" to 0
                ))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // カレンダーへ書き戻す（末尾「%」で全置換）
        val written = if (calId != null) {
            CalendarWriter.replaceGenerated(
                ctx, calId, placed.map { Triple(it.title, it.start, it.end) }, days
            )
        } else 0 to 0

        val cal = calId?.let { id -> CalendarRepo.listCalendars(ctx).firstOrNull { it.id == id } }
        return RunReport(
            calendarName = cal?.displayName ?: snap.calendarName,
            calendarWritable = cal?.canWrite ?: true,
            fillDays = days,
            planDays = planDays,
            windows = snap.windows.size,
            busy = snap.busy.size,
            examPeriods = snap.examPeriods.size,
            freeMinutes = freeMinutesWithin(ctx, snap, days),
            materialsActive = refreshed.size,
            hobbiesActive = hobbies.size,
            placedStudy = placed.count { it.eventType == "study" },
            placedHobby = placed.count { it.eventType != "study" },
            placedMinutes = placed.sumOf { minutesBetween(it.start, it.end) },
            calRemoved = written.first,
            calAdded = written.second,
            skipped = skipReasons(refreshed, snap, eng, days)
        )
    }

    /** 配置対象の日数ぶんの空き時間（分）。就寝時刻より後は数えない */
    private fun freeMinutesWithin(
        ctx: Context, snap: dev.togar.dynasched.calendar.CalendarSnapshot, days: Int
    ): Int {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        var total = 0
        for (i in 0 until days.coerceIn(1, 30)) {
            val ds = Scheduler.ymd((cal.clone() as java.util.Calendar).apply {
                add(java.util.Calendar.DAY_OF_MONTH, i)
            })
            val free = Scheduler.computeFree(
                ds, snap.busy, snap.windows, Prefs.wakeMinutes(ctx), Prefs.bedtimeMinutes(ctx)
            )
            for (f in free) total += f.end - f.start
        }
        return total
    }

    private fun minutesBetween(start: String, end: String): Int {
        val s = Scheduler.parseDeadlineMillis(start) ?: return 0
        val e = Scheduler.parseDeadlineMillis(end) ?: return 0
        return ((e - s) / 60000L).toInt().coerceAtLeast(0)
    }

    /**
     * 配置対象から外れた教材と、その理由。
     *
     * 除外はどれも `Scheduler.run` の中で黙って起こる。理由が見えないと
     * 「設定が悪いのか壊れているのか」が永遠に判別できないので、同じ条件をここで数える。
     */
    private fun skipReasons(
        materials: List<MaterialRow>, snap: dev.togar.dynasched.calendar.CalendarSnapshot,
        eng: StudyEngine, days: Int
    ): List<String> {
        val today = ymd()
        val examToday = snap.examPeriods.any { it.startDate <= today && today <= it.endDate }
        val out = ArrayList<String>()
        for (m in materials) {
            val st = eng.stateOf(m)
            val dlMs = Scheduler.parseDeadlineMillis(m.deadline)
            val reason = when {
                dlMs == null -> "試験日が読めない（${m.deadline}）"
                dlMs <= System.currentTimeMillis() -> "試験日を過ぎている（${m.deadline.take(10)}）"
                st.remainingProblems <= 0 ->
                    if (st.plannedRounds < st.target)
                        "残り0問（間に合わないので${st.plannedRounds}周に削られた結果）"
                    else "残り0問（やり終えた）"
                m.prereqMaterialId != null &&
                    materials.firstOrNull { it.id == m.prereqMaterialId }
                        ?.let { eng.stateOf(it).finishedRounds < 1 } == true ->
                    "前提の教材が1周終わっていない"
                examToday && !m.isExam -> "テスト期間中（定期テスト対象ではない）"
                m.needs != "none" && snap.windows.none { it.location == "home" } ->
                    "家の枠が無い（机・声・PCが要る教材）"
                else -> null
            }
            if (reason != null) out.add("${m.name}: $reason")
        }
        return out
    }

    // ---- 単発タスク ----

    override fun getHobby(ctx: Context): List<HobbyItem> =
        LocalDb.get(ctx).readableDatabase.rawQuery(
            "SELECT * FROM hobby_tasks WHERE is_active=1 ORDER BY COALESCE(parent_id,0), sort_order, id",
            null
        ).mapRows { c ->
            HobbyItem(
                id = c.long("id"),
                name = c.str("name"),
                parentId = c.longOrNull("parent_id"),
                isCompleted = c.bool("is_completed"),
                durationMinutes = c.int("duration_minutes", 30),
                location = c.str("location", "anywhere"),
                note = c.str("note"),
                priority = c.int("priority", 5),
                color = c.str("color"),
                sortOrder = c.int("sort_order"),
                tags = c.str("tags")
            )
        }

    override fun addHobby(
        ctx: Context, name: String, parentId: Long?, durationMinutes: Int,
        priority: Int, location: String, note: String, color: String, tags: String
    ) {
        val db = LocalDb.get(ctx).writableDatabase
        // 追加は同じ親の末尾へ。既存の手動順を崩さない
        val next = db.rawQuery(
            "SELECT COALESCE(MAX(sort_order),0)+1 n FROM hobby_tasks WHERE " +
                (if (parentId == null) "parent_id IS NULL" else "parent_id=?"),
            if (parentId == null) null else arrayOf(parentId.toString())
        ).mapRows { it.int("n") }.firstOrNull() ?: 1
        db.insert("hobby_tasks", null, values(
            "name" to name, "parent_id" to parentId, "duration_minutes" to durationMinutes,
            "priority" to priority, "location" to location, "note" to note, "color" to color,
            "tags" to Tags.normalize(tags),
            "is_active" to 1, "is_completed" to 0, "sort_order" to next
        ))
        NotionSyncer.syncSoon(ctx)
    }


    override fun reorderHobby(ctx: Context, orderedIds: List<Long>) {
        val db = LocalDb.get(ctx).writableDatabase
        db.beginTransaction()
        try {
            orderedIds.forEachIndexed { i, id ->
                db.execSQL("UPDATE hobby_tasks SET sort_order=? WHERE id=?", arrayOf(i + 1, id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        orderedIds.forEach { NotionSyncer.markDirty(ctx, it) }
        NotionSyncer.syncSoon(ctx)
    }

    override fun setHobbyParent(ctx: Context, id: Long, parentId: Long?) {
        // 自分の子孫を親にすると木が輪になって、一覧が二度と出てこなくなる
        if (parentId != null && (parentId == id || isDescendant(ctx, parentId, id))) return
        val db = LocalDb.get(ctx).writableDatabase
        val next = db.rawQuery(
            "SELECT COALESCE(MAX(sort_order),0)+1 n FROM hobby_tasks WHERE " +
                (if (parentId == null) "parent_id IS NULL" else "parent_id=?"),
            if (parentId == null) null else arrayOf(parentId.toString())
        ).mapRows { it.int("n") }.firstOrNull() ?: 1
        db.execSQL(
            "UPDATE hobby_tasks SET parent_id=?, sort_order=? WHERE id=?",
            arrayOf(parentId, next, id)
        )
        NotionSyncer.markDirty(ctx, id)
        NotionSyncer.syncSoon(ctx)
    }

    override fun setHobbyPriority(ctx: Context, id: Long, priority: Int) {
        LocalDb.get(ctx).writableDatabase
            .execSQL("UPDATE hobby_tasks SET priority=? WHERE id=?", arrayOf(priority, id))
        NotionSyncer.markDirty(ctx, id)
        NotionSyncer.syncSoon(ctx)
    }

    override fun applyToSubtree(
        ctx: Context, parentId: Long,
        priority: Int?, location: String?, color: String?, tags: String?
    ) {
        val sets = ArrayList<String>()
        val args = ArrayList<Any>()
        priority?.let { sets.add("priority=?"); args.add(it) }
        location?.let { sets.add("location=?"); args.add(it) }
        color?.let { sets.add("color=?"); args.add(it) }
        tags?.let { sets.add("tags=?"); args.add(Tags.normalize(it)) }
        if (sets.isEmpty()) return
        // 親自身は既に保存済みなので配下だけ。孫より下まで全部降りる
        args.add(parentId)
        LocalDb.get(ctx).writableDatabase.execSQL(
            "UPDATE hobby_tasks SET " + sets.joinToString(", ") +
                " WHERE id IN (WITH RECURSIVE sub(id) AS (" +
                "SELECT id FROM hobby_tasks WHERE parent_id=? " +
                "UNION ALL SELECT h.id FROM hobby_tasks h JOIN sub s ON h.parent_id=s.id" +
                ") SELECT id FROM sub)",
            args.toTypedArray()
        )
        subtreeIds(ctx, parentId).forEach { NotionSyncer.markDirty(ctx, it) }
        NotionSyncer.syncSoon(ctx)
    }

    /** parentId の配下すべて（自分は含まない） */
    private fun subtreeIds(ctx: Context, parentId: Long): List<Long> =
        LocalDb.get(ctx).readableDatabase.rawQuery(
            "WITH RECURSIVE sub(id) AS (SELECT id FROM hobby_tasks WHERE parent_id=? " +
                "UNION ALL SELECT h.id FROM hobby_tasks h JOIN sub s ON h.parent_id=s.id) " +
                "SELECT id FROM sub",
            arrayOf(parentId.toString())
        ).mapRows { it.long("id") }

    override fun getHobbyItem(ctx: Context, id: Long): HobbyItem? =
        getHobby(ctx).firstOrNull { it.id == id }

    /** candidate が root の子孫か */
    private fun isDescendant(ctx: Context, candidate: Long, root: Long): Boolean =
        LocalDb.get(ctx).readableDatabase.rawQuery(
            "WITH RECURSIVE sub(id) AS (SELECT id FROM hobby_tasks WHERE id=? " +
                "UNION ALL SELECT h.id FROM hobby_tasks h JOIN sub s ON h.parent_id=s.id) " +
                "SELECT COUNT(*) c FROM sub WHERE id=?",
            arrayOf(root.toString(), candidate.toString())
        ).mapRows { it.int("c") }.firstOrNull().let { (it ?: 0) > 0 }

    override fun editHobby(
        ctx: Context, id: Long, name: String, durationMinutes: Int,
        priority: Int, location: String, note: String, color: String, tags: String
    ) {
        LocalDb.get(ctx).writableDatabase.update("hobby_tasks", values(
            "name" to name, "duration_minutes" to durationMinutes, "priority" to priority,
            "location" to location, "note" to note, "color" to color,
            "tags" to Tags.normalize(tags)
        ), "id=?", arrayOf(id.toString()))
        NotionSyncer.markDirty(ctx, id)
        NotionSyncer.syncSoon(ctx)
    }

    override fun completeHobby(ctx: Context, id: Long) = setHobbyCompleted(ctx, id, true)

    override fun setHobbyCompleted(ctx: Context, id: Long, completed: Boolean) {
        LocalDb.get(ctx).writableDatabase.update("hobby_tasks", values(
            "is_completed" to completed,
            "completed_at" to if (completed) nowNaive() else null
        ), "id=?", arrayOf(id.toString()))
        // dirty は付けない。完了はスキマスが正なので、次の同期で食い違いとして
        // 拾われる。圏外で落ちても繋がった時に勝手に揃う
        NotionSyncer.syncSoon(ctx)
    }

    override fun deleteHobby(ctx: Context, id: Long) {
        val db = LocalDb.get(ctx).writableDatabase
        // 子も一緒に消す（サーバー版と同じ）
        val ids = db.rawQuery(
            "WITH RECURSIVE sub(id) AS (SELECT id FROM hobby_tasks WHERE id=? " +
                "UNION ALL SELECT h.id FROM hobby_tasks h JOIN sub s ON h.parent_id=s.id) SELECT id FROM sub",
            arrayOf(id.toString())
        ).mapRows { it.long("id") }
        if (ids.isEmpty()) return
        // **消す前に**ページIDを控える。消した後では引けず、Notion側を畳めない
        NotionSyncer.tombstone(ctx, ids)
        val ph = ids.joinToString(",") { "?" }
        val args = ids.map { it.toString() }.toTypedArray()
        db.execSQL("DELETE FROM scheduled_events WHERE hobby_task_id IN ($ph)", args)
        db.execSQL("DELETE FROM hobby_tasks WHERE id IN ($ph)", args)
        NotionSyncer.syncSoon(ctx)
    }

    // ---- 教材 ----

    override fun getMaterials(ctx: Context): List<MaterialItem> {
        val eng = engine(ctx)
        return materialRows(ctx).map { m ->
            val st = eng.stateOf(m)
            MaterialItem(
                id = m.id, subject = m.subject, name = m.name,
                totalProblems = m.totalProblems, advancedRanges = m.advancedRanges,
                targetRounds = m.targetRounds, plannedRounds = st.plannedRounds,
                dropAdvanced = st.dropAdvanced, doneProblems = st.done,
                studyType = m.studyType, needs = m.needs, deadline = m.deadline,
                firstRoundDeadline = m.firstRoundDeadline, prereqMaterialId = m.prereqMaterialId,
                sessionMinutes = m.sessionMinutes, priority = m.priority, color = m.color,
                memo = m.memo, isExam = m.isExam,
                round = st.round, perRound = st.perRound, doneInRound = st.doneInRound,
                remainingProblems = st.remainingProblems, advancedProblems = st.advanced,
                paceMinutes = Math.round(eng.paceFor(m.id, st.round) * 10) / 10.0,
                remainingMinutes = eng.remainingMinutes(m, st)
            )
        }
    }

    override fun addMaterial(ctx: Context, m: MaterialInput) {
        val db = LocalDb.get(ctx).writableDatabase
        val id = db.insert("materials", null, materialValues(m))
        syncDeadlineToCalendar(ctx, id, m.name, m.deadline)
    }

    override fun editMaterial(ctx: Context, id: Long, m: MaterialInput) {
        LocalDb.get(ctx).writableDatabase
            .update("materials", materialValues(m), "id=?", arrayOf(id.toString()))
        syncDeadlineToCalendar(ctx, id, m.name, m.deadline)
    }

    override fun deleteMaterial(ctx: Context, id: Long) {
        val db = LocalDb.get(ctx).writableDatabase
        val calEventId = db.rawQuery("SELECT cal_event_id FROM materials WHERE id=?", arrayOf(id.toString()))
            .mapRows { it.longOrNull("cal_event_id") }.firstOrNull()
        db.execSQL("DELETE FROM attempts WHERE material_id=?", arrayOf(id))
        db.execSQL("DELETE FROM materials WHERE id=?", arrayOf(id))
        if (calEventId != null && calEventId > 0) CalendarWriter.deleteEvent(ctx, calEventId)
    }

    override fun recordAttempt(ctx: Context, id: Long, problems: Int, minutes: Int, eventId: Long?) {
        if (problems <= 0) return
        val eng = engine(ctx)
        val m = materialRow(ctx, id) ?: return
        val st = eng.stateOf(m)
        val db = LocalDb.get(ctx).writableDatabase
        db.insert("attempts", null, values(
            "material_id" to id, "event_id" to eventId, "round" to st.round,
            "problems" to problems, "minutes" to minutes, "done_at" to nowNaive()
        ))
        db.execSQL(
            "UPDATE materials SET done_problems = MIN(?, COALESCE(done_problems,0) + ?) WHERE id=?",
            arrayOf(st.totalNeeded, problems, id)
        )
    }

    override fun undoAttempt(ctx: Context, id: Long) {
        val db = LocalDb.get(ctx).writableDatabase
        val last = db.rawQuery(
            "SELECT id, problems FROM attempts WHERE material_id=? ORDER BY id DESC LIMIT 1",
            arrayOf(id.toString())
        ).mapRows { it.long("id") to it.int("problems") }.firstOrNull() ?: return
        db.execSQL("DELETE FROM attempts WHERE id=?", arrayOf(last.first))
        db.execSQL(
            "UPDATE materials SET done_problems = MAX(0, COALESCE(done_problems,0) - ?) WHERE id=?",
            arrayOf(last.second, id)
        )
    }

    // ---- 計画 ----

    override fun getPlan(ctx: Context, days: Int): PlanResult {
        val snap = CalendarRepo.read(ctx, days, Prefs.calendarId(ctx))
        val eng = engine(ctx)
        val outcome = PlanEngine.build(
            materialRows(ctx), hobbyRows(ctx), snap.windows, snap.busy, eng, days,
            wakeStart = Prefs.wakeMinutes(ctx), wakeEnd = Prefs.bedtimeMinutes(ctx)
        )
        return PlanResult(
            items = outcome.rows.map { r ->
                PlanItem(
                    id = r.materialId, subject = r.subject, name = r.name,
                    round = r.round, plannedRounds = r.plannedRounds, targetRounds = r.targetRounds,
                    perRound = r.perRound, doneInRound = r.doneInRound,
                    remainingProblems = r.remainingProblems, paceMinutes = r.paceMinutes,
                    measured = r.measured, needMinutes = r.needMinutes,
                    capacityMinutes = r.capacityMinutes, slackMinutes = r.slackMinutes,
                    ok = r.ok, trimmed = r.trimmed, dropAdvanced = r.dropAdvanced,
                    advancedProblems = r.advancedProblems, deadline = r.deadline,
                    blockedByPrereq = r.blockedByPrereq,
                    firstRoundDeadline = r.firstRoundDeadline, firstRoundOk = r.firstRoundOk,
                    firstRoundSlack = r.firstRoundSlack, hasFirstRound = r.hasFirstRound
                )
            },
            // 端末のカレンダーは何日先でも読めるので、サーバー版のような概算は要らない
            calendarHorizon = "",
            estimatedPerDay = 0,
            knownDays = days,
            freeMinutesTotal = outcome.freeMinutesTotal,
            hobbyMinutes = outcome.hobbyMinutes
        )
    }

    // ---- 提案 ----

    override fun getSuggestions(
        ctx: Context, loc: String, min: Int, limit: Int, tags: Set<String>
    ): List<SuggestItem> {
        val out = ArrayList<SuggestItem>()
        val minutes = min.coerceIn(5, 600)

        // タグで絞る時は、そのタグの付いた単発タスクだけを候補にする。
        // 教材にはタグが無いので、絞り込み中は教材を出さない
        val allowed = if (tags.isEmpty()) null
            else getHobby(ctx).filter { Tags.hasAny(it, tags) }.mapTo(HashSet()) { it.id }

        for (h in hobbyRows(ctx)) {
            if (allowed != null && h.id !in allowed) continue
            val dur = maxOf(15, h.durationMinutes)
            if (dur > minutes) continue
            if (!(h.location == "anywhere" || loc == "anywhere" || h.location == loc)) continue
            out.add(SuggestItem("hobby", h.id, h.name, dur, h.priority, 0))
        }
        if (allowed != null) return out.take(limit.coerceIn(1, 100))

        val eng = engine(ctx)
        val todayMs = System.currentTimeMillis()
        val scored = ArrayList<Pair<Int, SuggestItem>>()
        for (m in materialRows(ctx)) {
            val dlMs = Scheduler.parseDeadlineMillis(m.deadline) ?: continue
            val daysTo = Math.ceil((dlMs - todayMs).toDouble() / Scheduler.DAY_MS).toInt()
            if (daysTo < 0) continue
            val need = if (m.needs == "none") "anywhere" else "home"
            if (!(need == "anywhere" || loc == "anywhere" || need == loc)) continue
            m.prereqMaterialId?.let { pid ->
                val p = materialRows(ctx).firstOrNull { it.id == pid }
                if (p != null && eng.stateOf(p).finishedRounds < 1) return@let
            }
            val st = eng.stateOf(m)
            if (st.remainingProblems <= 0) continue
            val remaining = eng.remainingMinutes(m, st)
            if (remaining <= 0) continue
            val chunk = minOf(minutes, if (daysTo <= 2) 50 else if (daysTo <= 5) 40 else 30, remaining)
            if (chunk < 15) continue
            val n = eng.problemsForMinutes(m, st, chunk)
            val label = if (st.plannedRounds > 1) "${m.name} ${st.round}周目 ${n}問" else "${m.name} ${n}問"
            val urgency = m.priority * 2 + maxOf(0, 30 - daysTo)
            scored.add(urgency to SuggestItem("material", m.id, label, chunk, m.priority, n))
        }
        scored.sortByDescending { it.first }
        out.addAll(scored.map { it.second })
        return out.take(limit.coerceIn(1, 100))
    }

    // ---- 内部 ----

    /** 実績の集計を先に読み込んだ計算エンジン。1回の操作につき1つ作る */
    private fun engine(ctx: Context): StudyEngine {
        val db = LocalDb.get(ctx).readableDatabase
        val agg = db.rawQuery(
            "SELECT material_id, round, SUM(problems) p, SUM(minutes) m FROM attempts GROUP BY material_id, round",
            null
        ).mapRows { c -> AttemptAgg(c.long("material_id"), c.int("round"), c.int("p"), c.int("m")) }
        val last = db.rawQuery(
            "SELECT material_id, MAX(done_at) t FROM attempts GROUP BY material_id", null
        ).mapRows { c -> c.long("material_id") to c.str("t") }
            .mapNotNull { (id, t) -> Scheduler.parseDeadlineMillis(t.replace(' ', 'T'))?.let { id to it } }
            .toMap()
        return StudyEngine(agg, last)
    }

    private fun materialRows(ctx: Context): List<MaterialRow> =
        LocalDb.get(ctx).readableDatabase
            .rawQuery("SELECT * FROM materials WHERE is_active=1 ORDER BY deadline, id", null)
            .mapRows { c -> toMaterialRow(c) }

    private fun materialRow(ctx: Context, id: Long): MaterialRow? =
        LocalDb.get(ctx).readableDatabase
            .rawQuery("SELECT * FROM materials WHERE id=?", arrayOf(id.toString()))
            .mapRows { c -> toMaterialRow(c) }.firstOrNull()

    private fun toMaterialRow(c: android.database.Cursor) = MaterialRow(
        id = c.long("id"),
        subject = c.str("subject"),
        name = c.str("name"),
        totalProblems = c.int("total_problems"),
        advancedRanges = c.str("advanced_ranges"),
        targetRounds = c.int("target_rounds", 1),
        plannedRounds = c.longOrNull("planned_rounds")?.toInt(),
        dropAdvanced = c.bool("drop_advanced"),
        doneProblems = c.int("done_problems"),
        studyType = c.str("study_type", "exercise"),
        needs = c.str("needs", "none"),
        deadline = c.str("deadline"),
        firstRoundDeadline = c.str("first_round_deadline"),
        prereqMaterialId = c.longOrNull("prereq_material_id")?.let { if (it > 0) it else null },
        sessionMinutes = c.int("session_minutes", 50),
        priority = c.int("priority", 5),
        color = c.str("color", "#E24A90"),
        memo = c.str("memo"),
        isExam = c.bool("is_exam")
    )

    private fun hobbyRows(ctx: Context): List<HobbyRow> =
        LocalDb.get(ctx).readableDatabase.rawQuery(
            "SELECT * FROM hobby_tasks WHERE is_active=1 AND COALESCE(is_completed,0)=0 " +
                "AND id NOT IN (SELECT parent_id FROM hobby_tasks WHERE parent_id IS NOT NULL) " +
                "ORDER BY priority DESC, id", null
        ).mapRows { c ->
            HobbyRow(
                id = c.long("id"),
                name = c.str("name"),
                durationMinutes = maxOf(15, c.int("duration_minutes", 30)),
                priority = c.int("priority", 5),
                location = c.str("location", "anywhere")
            )
        }

    private fun materialValues(m: MaterialInput) = values(
        "subject" to m.subject, "name" to m.name, "total_problems" to m.totalProblems,
        "advanced_ranges" to m.advancedRanges, "target_rounds" to m.targetRounds,
        "deadline" to m.deadline,
        "first_round_deadline" to m.firstRoundDeadline.ifEmpty { null },
        "prereq_material_id" to m.prereqMaterialId,
        "study_type" to m.studyType, "needs" to m.needs,
        "session_minutes" to m.sessionMinutes, "priority" to m.priority,
        "color" to m.color.ifEmpty { "#E24A90" }, "memo" to m.memo,
        "is_exam" to m.isExam, "is_active" to 1
    )

    /** 試験日をカレンダーへ書き、作られたイベントIDを控える */
    private fun syncDeadlineToCalendar(ctx: Context, id: Long, name: String, deadline: String) {
        val calId = Prefs.calendarId(ctx) ?: CalendarRepo.targetCalendar(ctx)?.id ?: return
        val db = LocalDb.get(ctx).writableDatabase
        val existing = db.rawQuery("SELECT cal_event_id FROM materials WHERE id=?", arrayOf(id.toString()))
            .mapRows { it.longOrNull("cal_event_id") }.firstOrNull()
        val eventId = CalendarWriter.upsertDeadline(ctx, calId, name, deadline, existing)
        if (eventId != null) {
            db.execSQL("UPDATE materials SET cal_event_id=? WHERE id=?", arrayOf(eventId, id))
        }
    }

    /** 取り込み用。サーバーから引いた行をそのまま入れる */
    fun importMaterial(ctx: Context, m: MaterialInput, doneProblems: Int): Long {
        val v = materialValues(m).apply { put("done_problems", doneProblems) }
        return LocalDb.get(ctx).writableDatabase.insert("materials", null, v)
    }

    fun isEmpty(ctx: Context): Boolean {
        val db = LocalDb.get(ctx).readableDatabase
        val n = db.rawQuery("SELECT COUNT(*) c FROM materials", null).mapRows { it.int("c") }.firstOrNull() ?: 0
        val h = db.rawQuery("SELECT COUNT(*) c FROM hobby_tasks", null).mapRows { it.int("c") }.firstOrNull() ?: 0
        return n == 0 && h == 0
    }
}
