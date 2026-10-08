package dev.togar.dynasched.data

import android.content.Context
import android.database.Cursor
import dev.togar.dynasched.BuildConfig
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.db.LocalDb
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 端末内データの書き出しと取り込み。
 *
 * **これが無いと詰む場面が2つある。**
 * 1. 署名鍵を変える時。Androidは鍵の違うAPKの上書きを拒むので一度アンインストールが要り、
 *    その時に端末内のDBは丸ごと消える
 * 2. 機種変更。端末内完結にした以上、サーバーに控えは無い
 *
 * 形式はJSONで、**表も列も総当たりで写す**。足すたびにここを直すことにすると、
 * いつか直し忘れて「書き出したのに一部だけ消えている」という最悪の壊れ方をする
 * （v44までは習慣と枠がそれで漏れていた）。取り込み側は、いま存在する表と列だけを拾う。
 *
 * 版2はv45からの形。版1（教材・実績・単発タスクだけ）の控えもそのまま読める。
 */
object Backup {

    const val FORMAT = "skimas-backup"
    const val VERSION = 2

    /** 控えない表。予定は配置し直せば作れる。墓標はNotionとの対応で、別の端末では意味が無い */
    private val SKIP_TABLES = setOf("scheduled_events", "notion_tombstones", "android_metadata")

    /**
     * 一緒に控える設定。カレンダーIDは端末ごとに違うので入れない。
     * **Notionのトークンは入れない**（持ち出したファイルが漏れるとワークスペースごと触られる）。
     */
    private val PREF_KEYS = setOf(
        "fill_days", "task_sort", "task_done_mode", "owner_mode", "notion_schema",
        "wake_min", "bedtime_min", "bedtime_notice", "avoid_busy", "animations",
        "task_order", "grouping", "calendar_horizontal", "daily_goal",
        "task_show_stats", "task_stats_span", "task_tab_source", "task_all_hides_grouped",
        "task_groups_last", "widget_loc",
        // v47で足したもの
        "places", "task_tab_order"
    )

    /** 控える表。端末にある表から、控えないものを除く */
    fun tablesToBackUp(all: Collection<String>): List<String> =
        all.filter { it !in SKIP_TABLES && !it.startsWith("sqlite_") }.sorted()

    data class Report(
        val materials: Int, val attempts: Int, val hobbies: Int,
        val routines: Int = 0, val frames: Int = 0
    ) {
        fun describe(): String =
            "単発タスク ${hobbies}件 / 習慣 ${routines}件 / 枠 ${frames}件 / 教材 ${materials}件 / 実績 ${attempts}件"
    }

    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "skimas-backup-$stamp.json"
    }

    // ---- 書き出し ----

    fun export(ctx: Context): String {
        val db = LocalDb.get(ctx).readableDatabase
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("app_version_code", BuildConfig.VERSION_CODE)
        root.put(
            "exported_at",
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        )
        for (t in tablesToBackUp(tablesOf(db))) {
            val arr = JSONArray()
            db.rawQuery("SELECT * FROM $t", null).use { c ->
                while (c.moveToNext()) arr.put(rowToJson(c))
            }
            root.put(t, arr)
        }
        val prefs = JSONObject()
        for ((k, v) in Prefs.values(ctx, PREF_KEYS)) prefs.put(k, v)
        // 本人用モードは「未設定ならNotionのトークンで決める」ので、決めた結果を書いておく
        prefs.put("owner_mode", Prefs.ownerMode(ctx))
        root.put("prefs", prefs)
        return root.toString(2)
    }

    private fun tablesOf(db: android.database.sqlite.SQLiteDatabase): List<String> =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }

    private fun rowToJson(c: Cursor): JSONObject {
        val o = JSONObject()
        for (i in 0 until c.columnCount) {
            val name = c.getColumnName(i)
            when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> o.put(name, JSONObject.NULL)
                Cursor.FIELD_TYPE_INTEGER -> o.put(name, c.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> o.put(name, c.getDouble(i))
                else -> o.put(name, c.getString(i))
            }
        }
        return o
    }

    // ---- 取り込み ----

    /** 中身を見ずに件数だけ数える（復元前に「何が入るのか」を見せるため） */
    fun peek(json: String): Report {
        val root = JSONObject(json)
        check(root.optString("format") == FORMAT) { "スキマスの控えではありません" }
        fun n(t: String) = root.optJSONArray(t)?.length() ?: 0
        return Report(
            materials = n("materials"), attempts = n("attempts"), hobbies = n("hobby_tasks"),
            routines = n("routines"), frames = n("availability")
        )
    }

    /** 控えの設定のうち、戻してよいものだけ。JSONの数は Long で来ることがあるので Int に直す */
    fun prefsFromJson(o: JSONObject): Map<String, Any> {
        val out = HashMap<String, Any>()
        for (k in o.keys()) {
            if (k !in PREF_KEYS) continue
            when (val v = o.get(k)) {
                is Boolean, is String, is Int -> out[k] = v
                is Long -> out[k] = v.toInt()
            }
        }
        return out
    }

    /**
     * 控えで**まるごと置き換える**。差分にはしない。
     *
     * 教材ID・親タスクIDといった参照が控えの中で閉じているので、
     * 混ぜると「前提の教材」や親子関係がよそを指しかねない。
     * 予定は消して作り直す（教材IDを指しているため）。
     * 控えに無い表は触らない（版1の控えで習慣や枠を消さないため）。
     *
     * @param withPrefs 設定も戻すか。同期前の控えから戻す時は、設定はいまのままにする
     */
    fun restore(ctx: Context, json: String, withPrefs: Boolean = true): Report {
        val root = JSONObject(json)
        check(root.optString("format") == FORMAT) { "スキマスの控えではありません" }
        val db = LocalDb.get(ctx).writableDatabase
        val report = peek(json)
        val tables = tablesToBackUp(tablesOf(db)).filter { root.has(it) }
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM scheduled_events")
            for (t in tables) {
                db.execSQL("DELETE FROM $t")
                val arr = root.getJSONArray(t)
                val columns = columnsOf(db, t)
                for (i in 0 until arr.length()) {
                    insertRow(db, t, arr.getJSONObject(i), columns)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (withPrefs) root.optJSONObject("prefs")?.let { Prefs.putValues(ctx, prefsFromJson(it)) }
        return report
    }

    private fun columnsOf(db: android.database.sqlite.SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val out = HashSet<String>()
            val idx = c.getColumnIndex("name")
            while (c.moveToNext()) out.add(c.getString(idx))
            out
        }

    private fun insertRow(
        db: android.database.sqlite.SQLiteDatabase, table: String,
        row: JSONObject, columns: Set<String>
    ) {
        val v = android.content.ContentValues()
        for (key in row.keys()) {
            // いまのスキーマに無い列は捨てる。古い控えでも落ちずに読めるようにする
            if (key !in columns) continue
            when (val value = row.get(key)) {
                JSONObject.NULL -> v.putNull(key)
                is Int -> v.put(key, value.toLong())
                is Long -> v.put(key, value)
                is Double -> v.put(key, value)
                is Boolean -> v.put(key, if (value) 1 else 0)
                else -> v.put(key, value.toString())
            }
        }
        if (v.size() > 0) db.insert(table, null, v)
    }
}
