package dev.togar.dynasched.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.db.LocalDb
import dev.togar.dynasched.db.bool
import dev.togar.dynasched.db.int
import dev.togar.dynasched.db.long
import dev.togar.dynasched.db.longOrNull
import dev.togar.dynasched.db.mapRows
import dev.togar.dynasched.db.str
import dev.togar.dynasched.db.values
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 同期の結果。画面に一言出すためのもの */
data class SyncResult(
    val pulled: Int = 0,
    val pushed: Int = 0,
    val brokenCycles: Int = 0,
    val skipped: Boolean = false,
    val error: String? = null
) {
    fun message(): String = when {
        skipped -> "Notionと繋がっていません"
        error != null -> error
        pulled == 0 && pushed == 0 -> "変わりはありません"
        else -> buildString {
            if (pulled > 0) append("取り込み${pulled}件")
            if (pulled > 0 && pushed > 0) append("・")
            if (pushed > 0) append("送信${pushed}件")
            if (brokenCycles > 0) append("（親子が輪になっていた${brokenCycles}件を最上位に戻しました）")
        }
    }
}

/**
 * Notionと端末を実際に同期する。
 *
 * 判断は [NotionSync] が持ち、ここは**読む・呼ぶ・書く**だけ。分けてあるのは、
 * 判断の方がテストで固めたい部分で、こちらは通信とSQLで固めにくいため。
 *
 * **毎回すべて取る。**更新日時で絞ると通信は減るが、「Notionから消えた」の判断が
 * できなくなり（差分に出てこないだけなのか、消されたのか区別が付かない）、
 * 間違えると端末のタスクを全部消す。個人の持ち物なので全件でも1〜2往復で済む。
 *
 * ワーカースレッドから呼ぶこと。
 */
object NotionSyncer {

    /** 同時に走らせない。開いた直後と編集直後が重なると、同じページを二重に作る */
    private val lock = Any()

    /** まとめ待ちの予約が入っているか */
    private val scheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 少し待ってから同期する。編集のたびに呼んでよい。
     *
     * **待つのは、並び替えのドラッグ1回で更新が何十回も飛ぶため。**
     * 予約が既にあれば何もしないので、連打してもNotionへの往復は1回にまとまる。
     */
    fun syncSoon(ctx: Context) {
        if (!Prefs.notionReady(ctx)) return
        val app = ctx.applicationContext
        if (!scheduled.compareAndSet(false, true)) return
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            scheduled.set(false)
            dev.togar.dynasched.api.Api.async({ sync(app) }, {}, {})
        }, 1500)
    }

    fun sync(ctx: Context): SyncResult = synchronized(lock) {
        if (!Prefs.notionReady(ctx)) return SyncResult(skipped = true)
        val api = NotionApi(Prefs.notionToken(ctx))
        val ds = Prefs.notionDataSourceId(ctx)
        return try {
            val result = run(ctx, api, ds)
            Prefs.setNotionLastFullSync(ctx, nowIso())
            Prefs.setNotionLastResult(ctx, "${stamp()} ${result.message()}")
            result
        } catch (e: Exception) {
            val msg = dev.togar.dynasched.api.Api.friendlyMessage(e)
            Prefs.setNotionLastResult(ctx, "${stamp()} 失敗: $msg")
            SyncResult(error = msg)
        }
    }

    /** いま揃っているべき列の世代。上げると次の同期で不足分を足す */
    private const val SCHEMA = 2

    /**
     * Notion側に足りない列を足し、**端末の値を先に押し出す。**
     *
     * 列を足した直後のNotion側は当然すべて空。そのまま取り込むと
     * **端末の色が一斉に消える。**新しい列の持ち主はNotionだが、
     * 移行の1回だけは端末が勝つようにして、空で上書きされるのを防ぐ。
     */
    private fun migrateSchema(ctx: Context, api: NotionApi, ds: String) {
        if (Prefs.notionSchemaVersion(ctx) >= SCHEMA) return
        api.ensureSchema(ds)
        LocalDb.get(ctx).writableDatabase.execSQL(
            "UPDATE hobby_tasks SET dirty=1 WHERE COALESCE(notion_page_id,'') <> ''"
        )
        Prefs.setNotionSchemaVersion(ctx, SCHEMA)
    }

    private fun run(ctx: Context, api: NotionApi, ds: String): SyncResult {
        migrateSchema(ctx, api, ds)
        val local = readLocal(ctx)
        val remote = api.query(ds)
        val plan = NotionSync.plan(local, remote, full = true, deletedPageIds = tombstones(ctx))

        val byId = local.associateBy { it.id }
        // ページID → 端末ID。新しく作った行もここに積んで、最後に親を繋ぐ
        val pageToLocal = HashMap<String, Long>()
        for (m in local) if (m.notionPageId.isNotEmpty()) pageToLocal[m.notionPageId] = m.id

        val db = LocalDb.get(ctx).writableDatabase

        // 0. 一覧に出てこなかった行を1件ずつ確かめる。
        // **ここを省くと、Notionが返しそびれただけの行を端末から消す**（v36の事故）。
        val confirmedGone = ArrayList<Long>()
        for (m in plan.verifyMissing) {
            if (!api.isPageAlive(m.pageId)) confirmedGone.add(m.localId)
        }

        // 安全弁。まとめて大量に消える計画は、一度だけ見送る。
        // **同じ結果がもう一度出たら実行する**（そうしないと、Notionで本当に
        // 大量に消した時に二度と追随できなくなる）。事故なら二度目は違う結果になる
        val toDelete = (plan.deleteLocal + confirmedGone).distinct()
        val tooMany = toDelete.size >= 5 && toDelete.size * 2 >= local.size
        val deleteKey = toDelete.sorted().joinToString(",")
        if (tooMany && Prefs.notionPendingDelete(ctx) != deleteKey) {
            Prefs.setNotionPendingDelete(ctx, deleteKey)
            return SyncResult(
                error = "${local.size}件中${toDelete.size}件が消える計画だったので、" +
                    "今回は見送りました。Notionで本当に消したのなら、" +
                    "もう一度同期すると実行します"
            )
        }
        Prefs.setNotionPendingDelete(ctx, "")

        // 1. Notion発の取り込み。親を繋ぐのは全部の行が出来てから
        db.beginTransaction()
        try {
            for (r in plan.insertLocal) {
                val id = insertFromNotion(db, r)
                pageToLocal[r.pageId] = id
            }
            for (u in plan.updateLocal) updateFromNotion(db, u)
            for (id in toDelete) deleteLocalRow(db, id)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // 2. 端末発の送信。ページIDが要るので通信しながら1件ずつ
        val localToPage = HashMap<Long, String>()
        for (m in local) if (m.notionPageId.isNotEmpty()) localToPage[m.id] = m.notionPageId

        val justCreated = ArrayList<Long>()
        for (id in plan.insertRemote) {
            val t = byId[id] ?: continue
            val pageId = api.createPage(ds, t, localToPage[t.parentId])
            if (pageId.isEmpty()) continue
            pageToLocal[pageId] = id
            localToPage[id] = pageId
            justCreated.add(id)
            db.update("hobby_tasks", values("notion_page_id" to pageId, "dirty" to 0),
                "id=?", arrayOf(id.toString()))
        }
        // 親も今回作った場合、作る時点では相手のページがまだ無い。
        // **ここで繋ぎ直さないと、端末で作った子タスクの親がNotion側で永久に外れる**
        // （次の同期では dirty が付いていないので押し直されない）
        for (id in justCreated) {
            val parent = byId[id]?.parentId ?: continue
            val parentPage = localToPage[parent] ?: continue
            val myPage = localToPage[id] ?: continue
            api.updateParent(myPage, parentPage)
        }
        for (id in plan.updateRemote) {
            val t = byId[id] ?: continue
            if (t.notionPageId.isEmpty()) continue
            if (t.dirty) {
                // 圏外で直した分。中身ごと押し返してから持ち主をNotionへ戻す
                api.updatePage(t.notionPageId, t, localToPage[t.parentId])
                db.update("hobby_tasks", values("dirty" to 0), "id=?", arrayOf(id.toString()))
            } else {
                api.updateStatus(t.notionPageId, t)
            }
        }
        for (pageId in plan.archiveRemote) {
            api.archivePage(pageId)
            db.delete("notion_tombstones", "page_id=?", arrayOf(pageId))
        }

        // 3. 親を繋ぐ。ページIDから端末IDへ引き直す
        applyParents(db, plan.parentOf, pageToLocal)

        return SyncResult(
            pulled = plan.insertLocal.size + plan.updateLocal.size + toDelete.size,
            pushed = plan.pushed(),
            brokenCycles = plan.brokenCycles.size
        )
    }

    // ---- 端末側を読む ----

    fun readLocal(ctx: Context): List<LocalTask> {
        val db = LocalDb.get(ctx).readableDatabase
        // 次にその予定が入っている時刻。まだ済んでいないものの一番早い開始
        val next = db.rawQuery(
            "SELECT hobby_task_id h, MIN(start_datetime) s FROM scheduled_events " +
                "WHERE hobby_task_id IS NOT NULL AND COALESCE(is_completed,0)=0 " +
                "GROUP BY hobby_task_id", null
        ).mapRows { it.long("h") to it.str("s") }.toMap()

        return db.rawQuery(
            "SELECT * FROM hobby_tasks WHERE is_active=1 ORDER BY COALESCE(parent_id,0), sort_order, id",
            null
        ).mapRows { c ->
            val id = c.long("id")
            LocalTask(
                id = id,
                notionPageId = c.str("notion_page_id"),
                dirty = c.bool("dirty"),
                parentId = c.longOrNull("parent_id"),
                name = c.str("name"),
                durationMinutes = c.int("duration_minutes", 30),
                priority = c.int("priority", 5),
                location = c.str("location", "anywhere"),
                note = c.str("note"),
                tags = c.str("tags"),
                sortOrder = c.int("sort_order"),
                color = c.str("color"),
                completed = c.bool("is_completed"),
                completedAt = c.str("completed_at").ifEmpty { null },
                scheduledAt = next[id]?.ifEmpty { null }
            )
        }
    }

    private fun tombstones(ctx: Context): List<String> =
        LocalDb.get(ctx).readableDatabase
            .rawQuery("SELECT page_id FROM notion_tombstones", null)
            .mapRows { it.str("page_id") }
            .filter { it.isNotEmpty() }

    // ---- 端末側へ書く ----

    private fun insertFromNotion(db: SQLiteDatabase, r: NotionTask): Long =
        db.insert("hobby_tasks", null, values(
            "name" to r.name, "parent_id" to null,
            "duration_minutes" to r.durationMinutes, "priority" to r.priority,
            "location" to r.location, "note" to r.note, "tags" to r.tags,
            "sort_order" to r.sortOrder, "color" to r.color, "is_active" to 1,
            "created_at" to nowNaive(),
            "is_completed" to r.completed, "notion_page_id" to r.pageId, "dirty" to 0
        ))

    /** 持ち主がNotionの項目だけ。完了や予定は踏まない */
    private fun updateFromNotion(db: SQLiteDatabase, u: LocalUpdate) {
        val r = u.from
        db.update("hobby_tasks", values(
            "name" to r.name, "duration_minutes" to r.durationMinutes,
            "priority" to r.priority, "location" to r.location,
            "note" to r.note, "tags" to r.tags, "sort_order" to r.sortOrder,
            "color" to r.color
        ), "id=?", arrayOf(u.localId.toString()))
    }

    /**
     * Notionで消された行を落とす。
     * **墓標は残さない。**Notion発の削除なので、押し返す相手がもう居ない。
     */
    private fun deleteLocalRow(db: SQLiteDatabase, id: Long) {
        db.execSQL("DELETE FROM scheduled_events WHERE hobby_task_id=?", arrayOf(id))
        // 子は最上位へ上げる。まとめて消すと、Notionに残っている子まで消える
        db.execSQL("UPDATE hobby_tasks SET parent_id=NULL WHERE parent_id=?", arrayOf(id))
        db.delete("hobby_tasks", "id=?", arrayOf(id.toString()))
    }

    private fun applyParents(
        db: SQLiteDatabase, parentOf: Map<String, String?>, pageToLocal: Map<String, Long>
    ) {
        db.beginTransaction()
        try {
            for ((page, parentPage) in parentOf) {
                val id = pageToLocal[page] ?: continue
                // 親が今回の取得に入っていなければ触らない（最上位へ上げてしまわない）
                val parentId = if (parentPage == null) null
                else pageToLocal[parentPage] ?: continue
                if (parentId == id) continue
                db.execSQL("UPDATE hobby_tasks SET parent_id=? WHERE id=?", arrayOf(parentId, id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---- 編集の合図 ----

    /**
     * 端末で編集した印を付ける。押し返せなかった時のため。
     * 同期が成功した時点で消える。
     */
    fun markDirty(ctx: Context, id: Long) {
        if (!Prefs.notionReady(ctx)) return
        LocalDb.get(ctx).writableDatabase
            .execSQL("UPDATE hobby_tasks SET dirty=1 WHERE id=?", arrayOf(id))
    }

    /**
     * 消す行のページIDを墓標に積む。**行を消す前に呼ぶこと。**
     * 消した後では notion_page_id が引けない。
     */
    fun tombstone(ctx: Context, ids: List<Long>) {
        if (!Prefs.notionReady(ctx) || ids.isEmpty()) return
        val db = LocalDb.get(ctx).writableDatabase
        val ph = ids.joinToString(",") { "?" }
        val pages = db.rawQuery(
            "SELECT notion_page_id p FROM hobby_tasks WHERE id IN ($ph)",
            ids.map { it.toString() }.toTypedArray()
        ).mapRows { it.str("p") }.filter { it.isNotEmpty() }
        for (p in pages) {
            db.insertWithOnConflict("notion_tombstones", null, values("page_id" to p),
                SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    // ---- 時刻 ----

    /** 端末の他の日時と同じ形。[dev.togar.dynasched.ui.Stats] が先頭10文字で比べる */
    private fun nowNaive(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN).format(Date())

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZZZZ", Locale.JAPAN).format(Date())

    private fun stamp(): String =
        SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(Date())
}
