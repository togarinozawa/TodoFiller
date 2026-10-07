package dev.togar.dynasched.api

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 教材（materials）。goals の置き換え。
 *
 * 聞くのは**数えられるもの**だけ:
 * - totalProblems: 総問数
 * - advancedRanges: 応用の番号（"19-22,45-48"）。足りないとき自動で外す対象の個数を出すためだけに持つ
 * - targetRounds: 目標周回（「浅く何回も」の回数）
 *
 * **総時間・進捗%・難易度・理解度は持たない。**
 * どれも正直に答えられない質問で、適当に答えた数字から出た予定は自分が一番信用できないため。
 * 所要時間は attempts（何問やったか）から測る。
 *
 * サーバー計算済みの値（round / perRound / paceMinutes / remainingMinutes 等）を同梱して返す。
 */
data class MaterialItem(
    val id: Long,
    val subject: String,
    val name: String,
    val totalProblems: Int,
    val advancedRanges: String,
    val targetRounds: Int,
    val plannedRounds: Int,
    val dropAdvanced: Boolean,
    val doneProblems: Int,
    val studyType: String,
    val needs: String,
    val deadline: String,
    val firstRoundDeadline: String,
    val prereqMaterialId: Long?,
    val sessionMinutes: Int,
    val priority: Int,
    val color: String,
    val memo: String,
    val isExam: Boolean,
    /** 場所のid。空なら needs から決める */
    val location: String = "",
    // --- サーバーが計算して返す ---
    val round: Int,
    val perRound: Int,
    val doneInRound: Int,
    val remainingProblems: Int,
    val advancedProblems: Int,
    val paceMinutes: Double,
    val remainingMinutes: Int
) {
    fun deadlineDate(): Date? = try {
        val s = if (deadline.length == 16) "$deadline:00" else deadline
        PARSER.parse(s)
    } catch (e: Exception) { null }

    /** 例: "09/24 (木) 23:59" */
    fun deadlineLabel(): String = deadlineDate()?.let { LABEL.format(it) } ?: deadline

    fun daysRemaining(): Long {
        val d = deadlineDate() ?: return 0
        return TimeUnit.MILLISECONDS.toDays(d.time - System.currentTimeMillis())
    }

    fun isExercise(): Boolean = studyType != "memorize"
    fun studyTypeLabel(): String = if (isExercise()) "演習" else "暗記"

    /** 例: "2周目 34/107問" */
    fun progressLabel(): String =
        if (plannedRounds > 1) "${round}周目 $doneInRound/$perRound 問"
        else "$doneInRound/$perRound 問"

    /** 実測ペースが溜まる前は既定値なので、そう分かるように出す */
    fun paceLabel(): String =
        if (paceMinutes <= 0) "—" else String.format(Locale.US, "%.1f分/問", paceMinutes)

    companion object {
        private val PARSER = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        private val LABEL = SimpleDateFormat("MM/dd (E) HH:mm", Locale.JAPAN)

        const val TYPE_EXERCISE = "exercise"
        const val TYPE_MEMORIZE = "memorize"

        /** 必要なもの。外の枠に置けるのは NEEDS_NONE だけ。 */
        const val NEEDS_NONE = "none"
        const val NEEDS_DESK = "desk"
        const val NEEDS_VOICE = "voice"
        const val NEEDS_PC = "pc"

        val NEEDS_VALUES = arrayOf(NEEDS_NONE, NEEDS_DESK, NEEDS_VOICE, NEEDS_PC)
        val NEEDS_LABELS = arrayOf("どこでも", "机が要る", "声を出す", "PCが要る")

        fun from(o: JSONObject): MaterialItem = MaterialItem(
            id = o.optLong("id"),
            subject = o.optString("subject", ""),
            name = o.optString("name", ""),
            totalProblems = o.optInt("total_problems", 0),
            advancedRanges = o.optString("advanced_ranges", ""),
            targetRounds = o.optInt("target_rounds", 1),
            plannedRounds = o.optInt("planned_rounds", o.optInt("target_rounds", 1)),
            dropAdvanced = o.optInt("drop_advanced", 0) == 1,
            doneProblems = o.optInt("done_problems", 0),
            studyType = o.optString("study_type", TYPE_EXERCISE),
            needs = o.optString("needs", NEEDS_NONE),
            deadline = o.optString("deadline", ""),
            firstRoundDeadline = o.optString("first_round_deadline", ""),
            prereqMaterialId = o.optLong("prereq_material_id", 0L).let { if (it > 0L) it else null },
            sessionMinutes = o.optInt("session_minutes", 50),
            priority = o.optInt("priority", 5),
            color = o.optString("color", "#E24A90"),
            memo = o.optString("memo", ""),
            isExam = o.optInt("is_exam", 0) == 1,
            round = o.optInt("round", 1),
            perRound = o.optInt("per_round", o.optInt("total_problems", 0)),
            doneInRound = o.optInt("done_in_round", 0),
            remainingProblems = o.optInt("remaining_problems", 0),
            advancedProblems = o.optInt("advanced_problems", 0),
            paceMinutes = o.optDouble("pace_minutes", 0.0),
            remainingMinutes = o.optInt("remaining_minutes", 0)
        )
    }
}
