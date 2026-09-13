package dev.togar.dynasched.data

import android.content.Context
import dev.togar.dynasched.api.HobbyItem
import dev.togar.dynasched.api.MaterialItem
import dev.togar.dynasched.api.PlanResult
import dev.togar.dynasched.api.ScheduledEvent
import dev.togar.dynasched.api.SuggestItem

/**
 * データの出どころ。**端末内で完結する [LocalRepo] だけ。**
 *
 * 以前はサーバーに問い合わせる実装も持っていて設定で切り替えていたが、
 * 端末内方式で不足が出なくなったので消した。分岐が残っていると、
 * 不具合を追うたびに「どちらで動いていたのか」から確かめる羽目になる。
 *
 * インターフェースは残してある。画面はここだけを見ればよく、
 * DB・カレンダーの触り方を知らずに済む。
 *
 * すべてワーカースレッドから呼ぶこと（`Api.async` の中など）。
 */
interface Repo {

    /** 指定日から7日分 */
    fun getSchedule(ctx: Context, dateYmd: String): List<ScheduledEvent>

    /** 完了。problems < 0 なら実測ペースからの推定で記録する */
    fun completeTask(ctx: Context, id: Long, problems: Int = -1)

    /** できなかった。予定を消して後の空き時間へ再配置する */
    fun skipTask(ctx: Context, id: Long)

    /** 予定を組み直す。何が起きたか（何件置けたか・なぜ置けなかったか）を返す */
    fun runScheduler(ctx: Context, days: Int): RunReport

    fun getHobby(ctx: Context): List<HobbyItem>

    /** 片付いた数・増えた数を数えるための最小限の行（[dev.togar.dynasched.ui.Stats]） */
    fun statRows(ctx: Context): List<dev.togar.dynasched.ui.StatRow>
    fun addHobby(
        ctx: Context, name: String, parentId: Long?, durationMinutes: Int,
        priority: Int, location: String, note: String, color: String, tags: String = ""
    )
    fun editHobby(
        ctx: Context, id: Long, name: String, durationMinutes: Int,
        priority: Int, location: String, note: String, color: String, tags: String = ""
    )
    fun completeHobby(ctx: Context, id: Long)
    fun setHobbyCompleted(ctx: Context, id: Long, completed: Boolean)
    fun deleteHobby(ctx: Context, id: Long)

    /** 同じ親を持つタスクの並び順を、渡された順で保存する */
    fun reorderHobby(ctx: Context, orderedIds: List<Long>)

    /** 親を付け替える（子タスク化／子をやめる）。自分の子孫は親にできない */
    fun setHobbyParent(ctx: Context, id: Long, parentId: Long?)

    /** 優先度だけを変える（優先度順で並び替えたとき） */
    fun setHobbyPriority(ctx: Context, id: Long, priority: Int)

    /**
     * 親の設定を配下のタスクへまとめて適用する。null を渡した項目は触らない。
     *
     * グループ単位で「全部これ家でやる」「全部優先度を上げる」と決めたい場面のため。
     * 1件ずつ開いて直すのは、10件もあれば現実的に続かない。
     */
    fun applyToSubtree(
        ctx: Context, parentId: Long,
        priority: Int? = null, location: String? = null,
        color: String? = null, tags: String? = null
    )

    /** 1件だけ取る（子タスク追加時に親の設定を引き継ぐため） */
    fun getHobbyItem(ctx: Context, id: Long): HobbyItem?

    fun getMaterials(ctx: Context): List<MaterialItem>
    fun addMaterial(ctx: Context, m: MaterialInput)
    fun editMaterial(ctx: Context, id: Long, m: MaterialInput)
    fun deleteMaterial(ctx: Context, id: Long)

    /** 実績の記録。これが唯一の進捗入力 */
    fun recordAttempt(ctx: Context, id: Long, problems: Int, minutes: Int, eventId: Long? = null)
    fun undoAttempt(ctx: Context, id: Long)

    /** 「間に合うのか」 */
    fun getPlan(ctx: Context, days: Int = 45): PlanResult

    /** @param tags 空でなければ、このタグが付いた単発タスクだけを候補にする */
    fun getSuggestions(
        ctx: Context, loc: String, min: Int, limit: Int, tags: Set<String> = emptySet()
    ): List<SuggestItem>

    companion object {
        /** 実装は1つしかない。呼び出し側の形を変えずに済ませるために残している */
        fun current(ctx: Context): Repo = LocalRepo
    }
}

/** 教材の入力値。引数が多いので束ねる */
data class MaterialInput(
    val subject: String,
    val name: String,
    val totalProblems: Int,
    val advancedRanges: String,
    val targetRounds: Int,
    val deadline: String,
    val firstRoundDeadline: String,
    val prereqMaterialId: Long?,
    val studyType: String,
    val needs: String,
    val sessionMinutes: Int,
    val priority: Int,
    val color: String,
    val memo: String,
    val isExam: Boolean
)
