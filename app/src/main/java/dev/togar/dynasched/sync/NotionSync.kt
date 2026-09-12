package dev.togar.dynasched.sync

/**
 * Notion と端末内DBの突き合わせ。**通信もSQLも行わない。**
 *
 * ここを純粋にしてあるのは、同期の事故が「動かして確かめる」のが一番あてにならない
 * 類だから。消したはずのタスクが復活する、圏外で直したものが黙って消える、
 * Notion側で親子を巡回させてしまう——どれも手で再現しにくい。
 * 突き合わせの判断だけを取り出して、テストで固定する。
 *
 * ## 持ち主
 *
 * 整理はNotionでやる、という前提で分けてある。
 *
 * | 項目 | 正 |
 * |---|---|
 * | 名前・親・所要時間・優先度・場所・メモ・タグ | **Notion** |
 * | 完了 | **スキマス**（Notionへ書き戻す） |
 *
 * 例外が1つある。**スキマスで編集した行（[LocalTask.dirty]）はその回だけ端末が勝つ。**
 * 圏外で直したものを黙って捨てないため。押し返した時点で持ち主はNotionに戻る。
 * 代償として、同じ行を両側で直していると**Notion側の変更が消える**。
 * 端末での編集は本来その場で押し返すので、dirty が残るのは通信できなかった時だけ。
 */

/** Notion側の1行。APIのJSONの形からは切り離してある */
data class NotionTask(
    val pageId: String,
    val parentPageId: String? = null,
    val name: String = "",
    val durationMinutes: Int = 30,
    val priority: Int = 5,
    val location: String = "anywhere",
    val note: String = "",
    val tags: String = "",
    val sortOrder: Int = 0,
    val completed: Boolean = false,
    /** スキマスが書き戻した「次にこの予定に入っている時刻」。読むのは突き合わせのためだけ */
    val scheduledAt: String? = null,
    /** Notionで削除（アーカイブ）された */
    val archived: Boolean = false
)

/** スキマス側の1行。突き合わせに要る列だけ */
data class LocalTask(
    val id: Long,
    /** 対応するNotionページ。空なら端末で作られてまだ送っていない */
    val notionPageId: String = "",
    /** 端末で編集したがまだ押し返せていない */
    val dirty: Boolean = false,
    val parentId: Long? = null,
    val name: String = "",
    val durationMinutes: Int = 30,
    val priority: Int = 5,
    val location: String = "anywhere",
    val note: String = "",
    val tags: String = "",
    val sortOrder: Int = 0,
    val completed: Boolean = false,
    /** 完了した時刻。Notionへ書くだけで、突き合わせには使わない */
    val completedAt: String? = null,
    /** 次にこの予定が入っている時刻。スキマスが正 */
    val scheduledAt: String? = null
)

/**
 * 突き合わせの結果。**これを実行する側は判断をしない。**
 *
 * 親の付け替えを [parentOf] としてページID空間で持つのは、
 * 新しく作る行にはまだ端末のIDが無いため。適用側が
 * 「ページID→端末ID」の対応表を作りながら最後に解決する。
 */
data class SyncPlan(
    /** Notionにあって端末に無い。作る */
    val insertLocal: List<NotionTask> = emptyList(),
    /** 両方にあり、Notion側が正なので上書きする */
    val updateLocal: List<LocalUpdate> = emptyList(),
    /** Notionで消された、または端末で消したので消す */
    val deleteLocal: List<Long> = emptyList(),
    /** 端末で作られたのでNotionへ送る */
    val insertRemote: List<Long> = emptyList(),
    /** 端末が勝つ回、または完了状態を書き戻す */
    val updateRemote: List<Long> = emptyList(),
    /** 端末で消したのでNotion側もアーカイブする */
    val archiveRemote: List<String> = emptyList(),
    /**
     * 対応するページが一覧に出てこなかった行。**まだ消してはいけない。**
     * 本当に消えたのか、単に返ってこなかっただけなのかを1件ずつ確かめる。
     */
    val verifyMissing: List<MissingPage> = emptyList(),
    /** 最終的な親。ページID → 親のページID（null は最上位） */
    val parentOf: Map<String, String?> = emptyMap(),
    /** 親子が環になっていたので断ち切ったページ。利用者に見せる */
    val brokenCycles: List<String> = emptyList()
) {
    /** 何もすることが無いか。通知やトーストの出し分けに使う */
    fun isEmpty(): Boolean =
        insertLocal.isEmpty() && updateLocal.isEmpty() && deleteLocal.isEmpty() &&
            insertRemote.isEmpty() && updateRemote.isEmpty() && archiveRemote.isEmpty()

    /** 「取り込み3件・送信1件」のように出すための数 */
    fun pulled(): Int = insertLocal.size + updateLocal.size + deleteLocal.size
    fun pushed(): Int = insertRemote.size + updateRemote.size + archiveRemote.size
}

/** Notionの値で端末の行を上書きする指示 */
data class LocalUpdate(val localId: Long, val from: NotionTask)

/** 一覧に出てこなかった行。確かめてから消す */
data class MissingPage(val localId: Long, val pageId: String)

object NotionSync {

    /**
     * 突き合わせて計画を作る。
     *
     * [full] は **[remote] が全件かどうか**。更新日時で絞って取った差分では
     * 「Notionに無い＝消された」と判断してはいけない（単に更新が無いだけなので、
     * 端末側を全部消してしまう）。絞り込みで取った時は必ず false にすること。
     */
    fun plan(
        local: List<LocalTask>,
        remote: List<NotionTask>,
        full: Boolean,
        /**
         * 端末で消した行のページID（墓標）。行ごと消えているので [local] からは分からず、
         * 別に渡してもらう。これを忘れると**端末で消したタスクがNotionから戻ってくる。**
         */
        deletedPageIds: List<String> = emptyList()
    ): SyncPlan {
        val byPage = local.filter { it.notionPageId.isNotEmpty() }.associateBy { it.notionPageId }
        val seen = HashSet<String>()

        val insertLocal = ArrayList<NotionTask>()
        val updateLocal = ArrayList<LocalUpdate>()
        val deleteLocal = ArrayList<Long>()
        val updateRemote = ArrayList<Long>()
        val parentOf = LinkedHashMap<String, String?>()

        // 端末で消したものはNotionでも畳む。
        // **差分取得だと墓標のページが remote に出てこない**ことがあるので、
        // 出てくるかどうかに関係なく畳む指示を出す（畳み直しは無害）。
        val archiveRemote = deletedPageIds.filter { it.isNotEmpty() }.distinct()
        val tombstones = archiveRemote.toHashSet()

        for (r in remote) {
            if (tombstones.contains(r.pageId)) {
                seen.add(r.pageId)   // 取り込み直さない。消したものが戻ってくるため
                continue
            }
            val mine = byPage[r.pageId]
            if (r.archived) {
                // Notionで消えた。端末にあれば落とす
                if (mine != null) deleteLocal.add(mine.id)
                seen.add(r.pageId)
                continue
            }
            seen.add(r.pageId)
            parentOf[r.pageId] = r.parentPageId

            if (mine == null) {
                insertLocal.add(r)
                continue
            }
            if (mine.dirty) {
                // 圏外で直したものを捨てない。今回だけ端末が勝つ
                updateRemote.add(mine.id)
                continue
            }
            if (contentDiffers(mine, r)) updateLocal.add(LocalUpdate(mine.id, r))
            // 実行の記録はスキマスが正。食い違っていたら書き戻す
            if (statusDiffers(mine, r)) updateRemote.add(mine.id)
        }

        // 端末で作られてまだ送っていないもの
        val insertRemote = local.filter { it.notionPageId.isEmpty() }.map { it.id }

        // **一覧に出てこないことを「消された」の根拠にしない。**
        // Notionの問い合わせは作ったばかりのページをすぐ返さないことがあり、
        // その隙に同期が走ると端末のタスクを巻き添えで消す（v36で実際に起きた）。
        // ここでは「行方が分からない」とだけ言い、1件ずつ確かめるのは呼び出し側。
        val verifyMissing = ArrayList<MissingPage>()
        if (full) {
            for (m in local) {
                if (m.notionPageId.isEmpty()) continue
                if (!seen.contains(m.notionPageId)) verifyMissing.add(MissingPage(m.id, m.notionPageId))
            }
        }

        val gone = deleteLocal.toHashSet()
        val (parents, broken) = breakCycles(parentOf)
        return SyncPlan(
            insertLocal = insertLocal,
            updateLocal = updateLocal.filterNot { gone.contains(it.localId) },
            deleteLocal = deleteLocal.distinct(),
            insertRemote = insertRemote,
            updateRemote = updateRemote.distinct().filterNot { gone.contains(it) },
            archiveRemote = archiveRemote,
            verifyMissing = verifyMissing.filterNot { gone.contains(it.localId) },
            parentOf = parents,
            brokenCycles = broken
        )
    }

    /** 持ち主がNotionの項目に違いがあるか */
    private fun contentDiffers(mine: LocalTask, r: NotionTask): Boolean =
        mine.name != r.name ||
            mine.durationMinutes != r.durationMinutes ||
            mine.priority != r.priority ||
            mine.location != r.location ||
            mine.note != r.note ||
            mine.tags != r.tags ||
            mine.sortOrder != r.sortOrder

    /**
     * 持ち主がスキマスの項目に違いがあるか。
     *
     * **完了日時はここで比べない。**端末は `2026-09-12 05:00:00`、Notionは
     * オフセット付きのISO8601で返すので、書式の差を毎回「違う」と読んでしまい、
     * **何も変わっていないのに押し続ける輪**ができる。完了と一緒に書くだけにする。
     * 時刻は分まで揃えて比べる（秒やミリ秒の表記揺れで回らないように）。
     */
    private fun statusDiffers(mine: LocalTask, r: NotionTask): Boolean =
        mine.completed != r.completed ||
            toMinute(mine.scheduledAt) != toMinute(r.scheduledAt)

    /**
     * 日時を「yyyy-MM-ddTHH:mm」に揃える。読めない物は null 扱い。
     * `2026-09-12 05:00:00` も `2026-09-12T05:00:00.000+09:00` も同じ値になる。
     */
    internal fun toMinute(v: String?): String? {
        val s = v?.trim()?.replace(' ', 'T') ?: return null
        if (s.length < 16) return null
        return s.substring(0, 16)
    }

    /**
     * 親子の環を断ち切る。
     *
     * Notionのリレーションは木を強制しないので、A→B→A が作れてしまう。
     * そのまま取り込むと一覧の組み立てが無限に回る。**環に入った先頭の親を外して**
     * 最上位に上げ、どれを切ったかを返す。黙って消すより、見せて直してもらう。
     */
    internal fun breakCycles(parentOf: Map<String, String?>): Pair<Map<String, String?>, List<String>> {
        val out = LinkedHashMap(parentOf)
        val broken = ArrayList<String>()
        val safe = HashSet<String>()   // 最上位まで辿り着けると分かったもの

        for (start in parentOf.keys) {
            if (safe.contains(start)) continue
            val path = LinkedHashSet<String>()
            var cur: String? = start
            while (cur != null && !safe.contains(cur)) {
                if (!path.add(cur)) {
                    // ここで環が閉じた。閉じた本人の親を外す
                    out[cur] = null
                    broken.add(cur)
                    break
                }
                // 自分自身を親にしている場合もここで落ちる
                val next = out[cur]
                if (next == cur) {
                    out[cur] = null
                    broken.add(cur)
                    break
                }
                cur = next
            }
            safe.addAll(path)
        }
        return out to broken
    }
}
