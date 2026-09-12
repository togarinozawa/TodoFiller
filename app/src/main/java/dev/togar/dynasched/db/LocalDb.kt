package dev.togar.dynasched.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 端末内DB。サーバーの SQLite スキーマをそのまま持ってきている。
 *
 * Room ではなく `SQLiteOpenHelper` + 生SQL なのは、
 * - このプロジェクトの「外部ライブラリ不使用」の方針を守れる（Roomは注釈処理が要る）
 * - **サーバーのSQLをほぼ書き写すだけで済み、移植が「設計し直し」ではなく「転記」になる**
 * という2点のため。列名もサーバーと揃えてあるので、初回の取り込みも素直に書ける。
 */
class LocalDb(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS materials (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                subject TEXT NOT NULL DEFAULT '',
                name TEXT NOT NULL,
                total_problems INTEGER NOT NULL DEFAULT 0,
                advanced_ranges TEXT DEFAULT '',
                target_rounds INTEGER NOT NULL DEFAULT 1,
                planned_rounds INTEGER,
                drop_advanced INTEGER DEFAULT 0,
                done_problems INTEGER NOT NULL DEFAULT 0,
                study_type TEXT DEFAULT 'exercise',
                needs TEXT DEFAULT 'none',
                deadline TEXT NOT NULL,
                first_round_deadline TEXT,
                prereq_material_id INTEGER,
                session_minutes INTEGER DEFAULT 50,
                priority INTEGER DEFAULT 5,
                color TEXT DEFAULT '#E24A90',
                memo TEXT DEFAULT '',
                is_exam INTEGER DEFAULT 0,
                is_active INTEGER DEFAULT 1,
                cal_event_id INTEGER,
                created_at TEXT DEFAULT (datetime('now','localtime'))
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS attempts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                material_id INTEGER NOT NULL,
                event_id INTEGER,
                round INTEGER NOT NULL DEFAULT 1,
                problems INTEGER NOT NULL,
                minutes INTEGER NOT NULL,
                done_at TEXT DEFAULT (datetime('now','localtime'))
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_attempts_material ON attempts(material_id, round)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS scheduled_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                cal_event_id INTEGER,
                title TEXT NOT NULL,
                start_datetime TEXT NOT NULL,
                end_datetime TEXT NOT NULL,
                event_type TEXT DEFAULT 'study',
                hobby_task_id INTEGER,
                material_id INTEGER,
                is_completed INTEGER DEFAULT 0,
                completed_at TEXT
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_start ON scheduled_events(start_datetime)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS hobby_tasks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                parent_id INTEGER,
                duration_minutes INTEGER DEFAULT 30,
                priority INTEGER DEFAULT 5,
                location TEXT DEFAULT 'anywhere',
                note TEXT DEFAULT '',
                color TEXT DEFAULT '',
                is_active INTEGER DEFAULT 1,
                is_completed INTEGER DEFAULT 0,
                completed_at TEXT,
                sort_order INTEGER DEFAULT 0
            )"""
        )
        /**
         * 端末で消したタスクの墓標。行ごと消えるので hobby_tasks からは辿れず、
         * **これが無いと消したタスクが次の同期でNotionから戻ってくる。**
         * Notion側を畳めたら消す。
         */
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS notion_tombstones (
                page_id TEXT PRIMARY KEY,
                deleted_at TEXT DEFAULT (datetime('now','localtime'))
            )"""
        )
        migrate(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    /**
     * 列の追加。サーバー側と同じく「無ければ足す」の冪等ブロックで積む。
     * `onCreate` からも呼ぶので、新規作成でも更新でも同じ形になる。
     */
    private fun migrate(db: SQLiteDatabase) {
        for ((table, column, decl) in MIGRATIONS) {
            if (hasColumn(db, table, column)) continue
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $decl")
        }
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val idx = c.getColumnIndex("name")
            while (c.moveToNext()) if (c.getString(idx) == column) return true
            false
        }

    companion object {
        private const val NAME = "skimas.db"
        /**
         * 2: hobby_tasks.sort_order（手動の並び順） / 3: hobby_tasks.tags
         * 4: Notion同期の対応付け（notion_page_id・dirty）と notion_tombstones
         */
        private const val VERSION = 4

        /** (テーブル, 列, 定義) */
        private val MIGRATIONS = listOf(
            Triple("hobby_tasks", "sort_order", "INTEGER DEFAULT 0"),
            // 対応するNotionページ。空なら端末で作られてまだ送っていない
            Triple("hobby_tasks", "notion_page_id", "TEXT DEFAULT ''"),
            // 端末で編集したがまだ押し返せていない（圏外など）
            Triple("hobby_tasks", "dirty", "INTEGER DEFAULT 0"),
            Triple("hobby_tasks", "tags", "TEXT DEFAULT ''")
        )

        @Volatile private var instance: LocalDb? = null

        fun get(ctx: Context): LocalDb =
            instance ?: synchronized(this) {
                instance ?: LocalDb(ctx).also { instance = it }
            }
    }
}

// ---- Cursor / ContentValues の小道具 ----
// 生SQLを読みやすく保つための最小限のヘルパー。列が無い場合も既定値で通す。

fun Cursor.str(name: String, dflt: String = ""): String {
    val i = getColumnIndex(name)
    return if (i < 0 || isNull(i)) dflt else getString(i) ?: dflt
}

fun Cursor.int(name: String, dflt: Int = 0): Int {
    val i = getColumnIndex(name)
    return if (i < 0 || isNull(i)) dflt else getInt(i)
}

fun Cursor.long(name: String, dflt: Long = 0L): Long {
    val i = getColumnIndex(name)
    return if (i < 0 || isNull(i)) dflt else getLong(i)
}

fun Cursor.longOrNull(name: String): Long? {
    val i = getColumnIndex(name)
    return if (i < 0 || isNull(i)) null else getLong(i)
}

fun Cursor.bool(name: String): Boolean = int(name, 0) == 1

/** 1行ずつ読んで閉じる。閉じ忘れでカーソルが漏れるのを防ぐ */
inline fun <T> Cursor.mapRows(map: (Cursor) -> T): List<T> = use {
    val out = ArrayList<T>(count)
    while (moveToNext()) out.add(map(this))
    out
}

fun values(vararg pairs: Pair<String, Any?>): ContentValues = ContentValues().apply {
    for ((k, v) in pairs) when (v) {
        null -> putNull(k)
        is String -> put(k, v)
        is Int -> put(k, v)
        is Long -> put(k, v)
        is Boolean -> put(k, if (v) 1 else 0)
        is Double -> put(k, v)
        else -> put(k, v.toString())
    }
}
