package dev.togar.dynasched.data

/**
 * 「いまやること」の並べ方。タスクと教材を**同じ物差し**で並べる。
 * いまやるタブ・ウィジェット・暇なとき・ドパチルの警告画面が、どれもこの順で受け取る。
 *
 * 上から順に効く:
 * 1. やる日を今日（か、過ぎた日）に決めた物。絶対 → なるべく
 * 2. 先の日に決めた物は最後（その日より前に消化すると、決めた意味が無くなる）
 * 3. 残りは点数の高い順。点数 = 優先度（0〜10）+ 締切の近さ（0〜10）
 *    - 優先度: 一番上の段が10、一番下が0。教材は 低0・中4・高10
 *    - 締切: 今日が締切なら10、[HORIZON]日以上先なら0。締切の無い物は0（教材の締切・タスクの締切どちらも）
 *    同じ重みにしてあるので、「優先度が一番上で締切なし」と「優先度が一番下で締切が今日」が並ぶ
 * 4. 同点なら古い物から（溜めない）
 */
object Ranking {

    /** 締切がこの日数より先なら、締切の点は付けない */
    const val HORIZON = 14

    data class Input(
        /** 今日の分として決めたやる日の強さ（[Want.rankOn] の今日の値）。2 絶対 / 1 なるべく / 0 */
        val wantToday: Int = 0,
        /** 先の日にやる日を決めている */
        val wantLater: Boolean = false,
        /** 優先度。0（一番下）〜1（一番上） */
        val priority: Double = 0.5,
        /** 締切まで何日か。締切が無ければ null */
        val dueDays: Int? = null,
        /** 作った日時。同点の時に古い方を先にする。教材は空 */
        val createdAt: String = ""
    )

    fun score(r: Input): Double {
        val due = r.dueDays?.let { (HORIZON - it).coerceIn(0, HORIZON) * 10.0 / HORIZON } ?: 0.0
        return r.priority.coerceIn(0.0, 1.0) * 10.0 + due
    }

    val ORDER: Comparator<Input> =
        compareByDescending<Input> { it.wantToday }
            .thenBy { it.wantLater }
            .thenByDescending { score(it) }
            .thenBy { it.createdAt.ifEmpty { "￿" } }

    /** 教材の優先度（低3・中5・高8）を 0〜1 に */
    fun materialPriority(p: Int): Double = ((p - 3) / 5.0).coerceIn(0.0, 1.0)

    /** タスクの優先度を、段の中の位置で 0〜1 に（段の数は設定で変わる） */
    fun taskPriority(rank: Int, levels: Int): Double =
        if (levels <= 1) 1.0 else (1.0 - rank.toDouble() / (levels - 1)).coerceIn(0.0, 1.0)
}
