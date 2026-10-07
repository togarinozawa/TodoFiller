package dev.togar.dynasched.engine

import dev.togar.dynasched.calendar.AvailabilityWindow
import dev.togar.dynasched.calendar.BusyBlock
import dev.togar.dynasched.calendar.ExamPeriod
import java.util.Calendar
import java.util.Locale

/**
 * 空き時間へタスクを配置する本体。サーバーの `services/scheduler.js` の移植。
 *
 * サーバー版で直した不具合はすべて反映済み:
 * - タグ付きの枠どうしが重なると同じ時刻に二重配置されていた → 早い枠が先取りする形に
 * - 1日の量を「締切までの全日数」で割っていた → 実際に枠のある日の割合で割る
 * - コマ数への丸めが切り捨てで、毎日わずかな不足が積み上がっていた → 切り上げ
 * - 周の間隔が同一実行内で効いていなかった → 実行内で埋めた日も見る
 */

/** 配置された1コマ */
data class PlacedEvent(
    val title: String,
    val start: String,      // yyyy-MM-dd'T'HH:mm:ss
    val end: String,
    val eventType: String,  // "study" | "hobby"
    val hobbyTaskId: Long?,
    val materialId: Long?
)

/** 配置に使う単発タスク */
data class HobbyRow(
    val id: Long,
    val name: String,
    val durationMinutes: Int,
    val priority: Int,
    val location: String
)

/** 日内の空き区間（分） */
data class FreeSlot(val start: Int, val end: Int, val location: String)

object Scheduler {

    /**
     * 起きている時間帯の既定値。
     *
     * 実際に使う値は設定（[dev.togar.dynasched.Prefs.wakeMinutes] /
     * [dev.togar.dynasched.Prefs.bedtimeMinutes]）から渡す。ここは
     * テストと、設定を読めない場面のための既定でしかない。
     */
    const val WAKE_START = 6 * 60
    const val WAKE_END = 23 * 60
    const val DAYTIME_END = 18 * 60   // これ以前=昼(演習向き) / これ以降=夜(暗記向き)
    const val MIN_BLOCK = 15
    /** 1教材あたり1日の上限。PlanEngine も同じ上限を使う（画面と配置を一致させるため） */
    const val MAX_PER_DAY = 240
    const val DAY_MS = 24L * 3600 * 1000

    // ---- 日付・時刻の小道具 ----

    fun ymd(cal: Calendar): String = String.format(
        Locale.US, "%04d-%02d-%02d",
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
    )

    fun minToTime(m: Int): String {
        val mm = m.coerceIn(0, 1439)
        return String.format(Locale.US, "%02d:%02d", mm / 60, mm % 60)
    }

    private fun timeToMin(hhmm: String): Int {
        val p = hhmm.split(":")
        return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    /** 'yyyy-MM-ddTHH:mm:ss' を指定日の分に直す。日跨ぎはクリップ。範囲外は null。 */
    private fun clipToDay(startDt: String, endDt: String, dayStr: String): IntRange? {
        val dayS = "${dayStr}T00:00:00"
        val dayE = "${dayStr}T24:00:00"   // 文字列比較用（翌日00:00より大きい）
        if (endDt <= dayS || startDt >= dayE) return null
        val s = if (startDt <= dayS) 0 else timeToMin(startDt.substring(11, 16))
        val e = if (endDt >= dayE) 1440 else timeToMin(endDt.substring(11, 16))
        return if (e > s) s..e else null
    }

    private fun locMatch(taskLoc: String?, slotLoc: String): Boolean =
        taskLoc.isNullOrEmpty() || taskLoc == "anywhere" || taskLoc == slotLoc

    /** 区間から busy を引く */
    private fun subtractBusy(s: Int, e: Int, busy: List<IntRange>): List<IntRange> {
        var parts = listOf(s..e)
        for (b in busy) {
            val next = ArrayList<IntRange>()
            for (p in parts) {
                if (b.last <= p.first || b.first >= p.last) { next.add(p); continue }
                if (b.first > p.first) next.add(p.first..b.first)
                if (b.last < p.last) next.add(b.last..p.last)
            }
            parts = next
        }
        return parts
    }

    /**
     * その日の配置可能な区間。
     *
     * タグ付きの枠の中にだけ置く。タグ無しの予定と重なった部分は潰す。
     * **タグ付きの枠どうしが重なる場合は早い枠が先取りする。**
     * （「終日 家」と「自習室 家」を両方書くと重なる。マニュアルが両方認めているので普通に起きる。
     *   潰さないと同じ時刻に2件配置される）
     */
    fun computeFree(
        dayStr: String, busyRows: List<BusyBlock>, availRows: List<AvailabilityWindow>,
        wakeStart: Int = WAKE_START, wakeEnd: Int = WAKE_END
    ): List<FreeSlot> {
        val busy = busyRows.mapNotNull { clipToDay(it.start, it.end, dayStr) }
            .map { maxOf(it.first, wakeStart)..minOf(it.last, wakeEnd) }
            .filter { it.last > it.first }

        val tagFree = ArrayList<FreeSlot>()
        for (w in availRows) {
            val c = clipToDay(w.start, w.end, dayStr) ?: continue
            val s = maxOf(c.first, wakeStart)
            val e = minOf(c.last, wakeEnd)
            if (e <= s) continue
            val loc = w.location
            for (p in subtractBusy(s, e, busy)) tagFree.add(FreeSlot(p.first, p.last, loc))
        }

        val sorted = tagFree.filter { it.end > it.start }
            .sortedWith(compareBy({ it.start }, { if (it.location == "home") 0 else 1 }))
        val merged = ArrayList<FreeSlot>()
        var covered = 0   // ここまでは既に別の枠が持っている
        for (f in sorted) {
            val s = maxOf(f.start, covered)
            if (f.end > s) {
                merged.add(FreeSlot(s, f.end, f.location))
                covered = f.end
            }
        }
        return merged.filter { it.end - it.start >= MIN_BLOCK }
    }

    /** 時間帯適合度。演習=昼、暗記=夜を優先（空きを埋めるため0にはしない） */
    private fun timeFit(studyType: String, startMin: Int): Double {
        val daytime = startMin < DAYTIME_END
        return if (studyType == "memorize") (if (daytime) 0.4 else 1.0)
        else (if (daytime) 1.0 else 0.5)
    }

    fun parseDeadlineMillis(s: String): Long? {
        val m = Regex("(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2})").find(s) ?: return null
        val (y, mo, d, h, mi) = m.destructured
        return Calendar.getInstance().apply {
            clear()
            set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), 0)
        }.timeInMillis
    }

    /** 配置中の教材の作業状態 */
    private class MatSlot(
        val row: MaterialRow,
        val st: MatState,
        val deadlineMs: Long,
        val daysTo: Int,
        val session: Int,
        val dailyBudget: Int,
        val location: String,
        val blockedByPrereq: Boolean,
        val lastAtMs: Long?
    ) {
        var consumedToday = 0
        var roundDoneOn: String? = null
    }

    /**
     * 予定を組み直す。既存の未完了予定は呼び出し側で消しておくこと。
     *
     * @param days 何日先まで埋めるか
     * @param nowMillis 「いま」。テストから固定できるようにしてある
     */
    fun run(
        materials: List<MaterialRow>,
        hobbies: List<HobbyRow>,
        windows: List<AvailabilityWindow>,
        busy: List<BusyBlock>,
        exams: List<ExamPeriod>,
        engine: StudyEngine,
        days: Int,
        nowMillis: Long = System.currentTimeMillis(),
        wakeStart: Int = WAKE_START,
        wakeEnd: Int = WAKE_END
    ): List<PlacedEvent> {
        val d0 = days.coerceIn(1, 30)
        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val todayMid = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        // その日の空き枠は使い回すので先に全部出す
        val dayStrs = ArrayList<String>(d0)
        val freeByDay = ArrayList<List<FreeSlot>>(d0)
        for (i in 0 until d0) {
            val c = (todayMid.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, i) }
            val ds = ymd(c)
            dayStrs.add(ds)
            freeByDay.add(computeFree(ds, busy, windows, wakeStart, wakeEnd))
        }

        // 「枠のある日」の割合。締切までの全日数で割ると、平日に枠が無いほど
        // 1日の量が過小になり、直前になって詰め込むことになる。
        val daysWithFree = freeByDay.count { it.isNotEmpty() }
        val usableRatio = if (d0 > 0) daysWithFree.toDouble() / d0 else 1.0

        val pool = hobbies.map { it to false }.toMutableList()   // (タスク, 配置済みか)

        val slots = materials.mapNotNull { m ->
            val st = engine.stateOf(m)
            val dlMs = parseDeadlineMillis(m.deadline) ?: return@mapNotNull null
            val daysTo = Math.ceil((dlMs - todayMid.timeInMillis).toDouble() / DAY_MS).toInt()
            if (daysTo <= 0 || st.remainingProblems <= 0) return@mapNotNull null
            val remainingMin = engine.remainingMinutes(m, st)
            val usableDays = maxOf(1, Math.round(daysTo * usableRatio).toInt())
            val perDay = minOf(MAX_PER_DAY, Math.ceil(remainingMin.toDouble() / usableDays).toInt())
            if (perDay <= 0) return@mapNotNull null
            val session = if (m.sessionMinutes in 15..240) m.sessionMinutes else 50
            // コマ数への丸めは切り上げ。切り捨てると毎日わずかに足りず、積み上がって置ききれなくなる
            val budget = maxOf(1, Math.ceil(perDay.toDouble() / session).toInt()) * session
            val blocked = m.prereqMaterialId?.let { pid ->
                materials.firstOrNull { it.id == pid }?.let { engine.stateOf(it).finishedRounds < 1 } ?: false
            } ?: false
            MatSlot(
                row = m, st = st, deadlineMs = dlMs, daysTo = daysTo, session = session,
                dailyBudget = budget,
                // 机・声・PCが要るものは家の枠にしか置かない
                location = if (m.needs == "none") "anywhere" else "home",
                blockedByPrereq = blocked,
                lastAtMs = engine.lastAttempt(m.id)
            )
        }

        val out = ArrayList<PlacedEvent>()
        for (i in 0 until d0) {
            val dayStr = dayStrs[i]
            val dayMid = (todayMid.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, i) }
            slots.forEach { it.consumedToday = 0 }
            val examMode = exams.any { it.startDate <= dayStr && dayStr <= it.endDate }

            for (iv in freeByDay[i]) {
                var cursor = if (i == 0) maxOf(iv.start, nowMin + 5) else iv.start
                while (iv.end - cursor >= MIN_BLOCK) {
                    val remaining = iv.end - cursor
                    val picked = pick(
                        iv.location, remaining, dayMid.timeInMillis, dayStr, cursor,
                        pool, slots, examMode, engine
                    ) ?: break
                    val chunk = minOf(remaining, picked.chunk)
                    out.add(
                        PlacedEvent(
                            title = picked.title,
                            start = "${dayStr}T${minToTime(cursor)}:00",
                            end = "${dayStr}T${minToTime(cursor + chunk)}:00",
                            eventType = picked.type,
                            hobbyTaskId = picked.hobbyId,
                            materialId = picked.materialId
                        )
                    )
                    picked.onPlace(chunk)
                    cursor += chunk
                }
            }
        }
        return out
    }

    private class Picked(
        val title: String, val type: String, val hobbyId: Long?, val materialId: Long?,
        val chunk: Int, val onPlace: (Int) -> Unit
    )

    /**
     * 教材をその日に置いてよいか。前提条件と周の間隔を見る。
     * 締切が迫っている時は間隔を守れないので無視する（守れないことは計画側に出る）。
     */
    private fun ready(m: MatSlot, dayStr: String): Boolean {
        if (m.blockedByPrereq) return false
        if (!(m.st.doneInRound == 0 && m.st.round > 1)) return true   // 周の切れ目でなければ自由
        val gap = StudyEngine.roundGapDays(m.st.round)
        if (m.daysTo <= gap) return true
        // 実績だけでなく、この実行内で埋め終えた日も見る。
        // 見ないと1周目を埋めた翌日に2周目が始まり、間隔が素通りする。
        val marks = listOfNotNull(
            m.lastAtMs,
            m.roundDoneOn?.let { parseDeadlineMillis("${it}T00:00") }
        )
        if (marks.isEmpty()) return true
        val last = marks.max()
        val dayMs = parseDeadlineMillis("${dayStr}T00:00") ?: return true
        return (dayMs - last).toDouble() / DAY_MS >= gap
    }

    private fun pick(
        location: String, remaining: Int, dayMs: Long, dayStr: String, startMin: Int,
        pool: MutableList<Pair<HobbyRow, Boolean>>, slots: List<MatSlot>,
        examMode: Boolean, engine: StudyEngine
    ): Picked? {
        // 1) 単発タスクを最優先（テスト期間中は出さない）
        if (!examMode) {
            var bestIdx = -1
            for (i in pool.indices) {
                val (t, placed) = pool[i]
                if (placed) continue
                if (!locMatch(t.location, location)) continue
                if (t.durationMinutes > remaining) continue
                if (bestIdx < 0 || t.priority > pool[bestIdx].first.priority) bestIdx = i
            }
            if (bestIdx >= 0) {
                val t = pool[bestIdx].first
                return Picked(t.name, "hobby", t.id, null, t.durationMinutes) {
                    pool[bestIdx] = t to true
                }
            }
        }

        // 2) 教材。締切の切迫度 × 優先度 × 時間帯適合度でスコアリング
        var best: MatSlot? = null
        var bestScore = -1.0
        for (m in slots) {
            if (examMode && !m.row.isExam) continue
            if (dayMs > m.deadlineMs) continue
            if (m.consumedToday >= m.dailyBudget) continue
            if (m.st.remainingProblems <= 0) continue
            if (!locMatch(m.location, location)) continue
            if (!ready(m, dayStr)) continue
            val minChunk = maxOf(MIN_BLOCK, Math.ceil(m.session / 2.0).toInt())
            if (remaining < minChunk) continue
            val urgency = m.row.priority * 2 + maxOf(0, 30 - m.daysTo)
            val score = urgency * timeFit(m.row.studyType, startMin)
            if (score > bestScore) { bestScore = score; best = m }
        }
        val bm = best ?: return null
        val chunk = minOf(remaining, bm.session)
        val n = engine.problemsForMinutes(bm.row, bm.st, chunk)
        // 予定名だけで何をやる枠か分かるようにする
        val label = if (bm.st.plannedRounds > 1) "${bm.row.name} ${bm.st.round}周目 ${n}問"
                    else "${bm.row.name} ${n}問"
        return Picked(label, "study", null, bm.row.id, chunk) { c ->
            bm.consumedToday += c
            // 同じ日に同じ教材を出しすぎないよう、見込みぶんを進めておく
            val est = engine.problemsForMinutes(bm.row, bm.st, c)
            bm.st.doneInRound = minOf(bm.st.perRound, bm.st.doneInRound + est)
            bm.st.remainingProblems = maxOf(0, bm.st.remainingProblems - est)
            if (bm.st.doneInRound >= bm.st.perRound && bm.st.round < bm.st.plannedRounds) {
                bm.st.round += 1
                bm.st.doneInRound = 0
                bm.roundDoneOn = dayStr   // 次の周はここから間隔を空ける
            }
        }
    }
}
