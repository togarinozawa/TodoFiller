package dev.togar.dynasched.engine

/**
 * 教材の進み具合と所要見積り。サーバーの `services/study.js` の移植。
 *
 * 設計の要点（`DESIGN_STUDY_v2.md`）:
 * - 聞くのは「何問やったか」だけ。所要時間は聞かずに実績から測る
 * - 難易度・理解度は持たない。難しい教材は実際に遅く、それは実測ペースに出る
 * - 「浅く何回も」は周回で表す。どの問題をやったかは管理しない
 *
 * サーバー版は都度SQLを叩いていたが、こちらは**実績の集計を先に渡す**形にした。
 * DBに依存しないので、そのままJVMのテストに載る。
 */

/** 教材1件（DBの行そのまま） */
data class MaterialRow(
    val id: Long,
    val subject: String = "",
    val name: String,
    val totalProblems: Int,
    val advancedRanges: String = "",
    val targetRounds: Int = 1,
    val plannedRounds: Int? = null,
    val dropAdvanced: Boolean = false,
    val doneProblems: Int = 0,
    val studyType: String = "exercise",
    val needs: String = "none",
    val deadline: String,
    val firstRoundDeadline: String = "",
    val prereqMaterialId: Long? = null,
    val sessionMinutes: Int = 50,
    val priority: Int = 5,
    val color: String = "#E24A90",
    val memo: String = "",
    val isExam: Boolean = false,
    /** 場所のid。空なら [needs] から決める（[dev.togar.dynasched.Places.materialPlace]） */
    val location: String = ""
) {
    /** 実際に置いてよい場所 */
    val place: String get() = dev.togar.dynasched.Places.materialPlace(location, needs)
}

/** 実績の集計（教材×周ごと） */
data class AttemptAgg(val materialId: Long, val round: Int, val problems: Int, val minutes: Int)

/** ある時点の教材の状態 */
data class MatState(
    val advanced: Int,
    val dropAdvanced: Boolean,
    val target: Int,
    val plannedRounds: Int,
    val total: Int,
    val perRound: Int,
    val totalNeeded: Int,
    val done: Int,
    var round: Int,
    var doneInRound: Int,
    var remainingProblems: Int,
    val finishedRounds: Int
)

class StudyEngine(
    aggregates: List<AttemptAgg> = emptyList(),
    private val lastAttemptAt: Map<Long, Long> = emptyMap()
) {
    private val byRound: Map<Pair<Long, Int>, AttemptAgg> =
        aggregates.associateBy { it.materialId to it.round }
    private val byMaterial: Map<Long, AttemptAgg> = aggregates
        .groupBy { it.materialId }
        .mapValues { (id, list) ->
            AttemptAgg(id, 0, list.sumOf { it.problems }, list.sumOf { it.minutes })
        }

    /**
     * その教材・その周の実測ペース（分/問）。
     * 周ごとに測る。2周目は速いのが普通なので、1周目のペースで見積もると過大になる。
     */
    fun paceFor(materialId: Long, round: Int): Double {
        byRound[materialId to round]?.let {
            if (it.problems >= MIN_TRUSTED_PROBLEMS && it.minutes > 0) {
                return it.minutes.toDouble() / it.problems
            }
        }
        byMaterial[materialId]?.let {
            if (it.problems >= MIN_TRUSTED_PROBLEMS && it.minutes > 0) {
                val base = it.minutes.toDouble() / it.problems
                return if (round > 1) base * LATER_ROUND_FACTOR else base
            }
        }
        return if (round > 1) DEFAULT_PACE * LATER_ROUND_FACTOR else DEFAULT_PACE
    }

    /** 実測が溜まっていて、推測値でないか */
    fun isMeasured(materialId: Long): Boolean =
        (byMaterial[materialId]?.problems ?: 0) >= MIN_TRUSTED_PROBLEMS

    /** 最後に手を付けた時刻（周の間隔判定に使う） */
    fun lastAttempt(materialId: Long): Long? = lastAttemptAt[materialId]

    /**
     * 教材の現在状態。
     * overrideDrop / overrideRounds を渡すと、その条件で試算する（計画の削り込み用）。
     */
    fun stateOf(m: MaterialRow, overrideDrop: Boolean? = null, overrideRounds: Int? = null): MatState {
        val advanced = countRanges(m.advancedRanges)
        val drop = overrideDrop ?: m.dropAdvanced
        val target = maxOf(1, m.targetRounds)
        val planned = minOf(maxOf(1, overrideRounds ?: (m.plannedRounds ?: target)), target)
        val total = maxOf(0, m.totalProblems)
        val perRound = maxOf(1, total - if (drop) advanced else 0)
        val totalNeeded = perRound * planned
        val done = m.doneProblems.coerceIn(0, totalNeeded)
        return MatState(
            advanced = advanced,
            dropAdvanced = drop,
            target = target,
            plannedRounds = planned,
            total = total,
            perRound = perRound,
            totalNeeded = totalNeeded,
            done = done,
            round = minOf(planned, done / perRound + 1),
            doneInRound = done % perRound,
            remainingProblems = totalNeeded - done,
            finishedRounds = done / perRound
        )
    }

    /** 残り時間（分）。周ごとに違うペースで積む。 */
    fun remainingMinutes(m: MaterialRow, st: MatState): Int {
        var sum = 0.0
        for (r in st.round..st.plannedRounds) {
            val inRound = if (r == st.round) st.perRound - st.doneInRound else st.perRound
            if (inRound <= 0) continue
            sum += inRound * paceFor(m.id, r)
        }
        return Math.round(sum).toInt()
    }

    /** 何分ぶんの枠なら何問やるか（コマの中身の表示に使う） */
    fun problemsForMinutes(m: MaterialRow, st: MatState, minutes: Int): Int {
        val pace = maxOf(0.2, paceFor(m.id, st.round))
        val n = maxOf(1, Math.round(minutes / pace).toInt())
        return minOf(n, maxOf(1, st.remainingProblems))
    }

    companion object {
        const val DEFAULT_PACE = 3.0          // 分/問。実測が溜まるまでの仮値
        const val MIN_TRUSTED_PROBLEMS = 3    // これ未満の実績はペースとして信用しない
        const val LATER_ROUND_FACTOR = 0.6    // 2周目以降は1周目より速い、という仮定

        /** 周の間隔（日）。前の周を終えてからこれだけ空ける。 */
        fun roundGapDays(round: Int): Int = when (round) {
            2 -> 3
            else -> 7
        }

        /** "19-22,45-48" のような指定に含まれる問題数を数える。壊れた入力は0扱い。 */
        fun countRanges(spec: String?): Int {
            if (spec.isNullOrBlank()) return 0
            var n = 0
            for (part in spec.split(Regex("[,\\s]+"))) {
                if (part.isEmpty()) continue
                val m = Regex("^(\\d+)\\s*[-–~]\\s*(\\d+)$").find(part)
                if (m != null) {
                    val a = m.groupValues[1].toIntOrNull() ?: continue
                    val b = m.groupValues[2].toIntOrNull() ?: continue
                    if (b >= a) n += b - a + 1
                } else if (part.all { it.isDigit() }) {
                    n += 1
                }
            }
            return n
        }
    }
}
