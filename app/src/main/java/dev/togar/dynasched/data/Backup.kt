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
 * 形式はJSONで、**列は総当たりで写す**。列を足すたびにここを直すことにすると、
 * いつか直し忘れて「書き出したのに一部だけ消えている」という最悪の壊れ方をする。
 * 取り込み側は、いま存在する列だけを拾う（古い控えも新しい控えも読める）。
 */
object Backup {

    const val FORMAT = "skimas-backup"
    const val VERSION = 1

    /** 予定(scheduled_events)は配置し直せば作れるので控えない */
    private val TABLES = listOf("materials", "attempts", "hobby_tasks")

    /** 一緒に控える設定。カレンダーIDは端末ごとに違うので入れない */
    private val PREF_KEYS = listOf("fill_days", "task_sort", "task_done_mode", "places")

    data class Report(val materials: Int, val attempts: Int, val hobbies: Int)

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
        for (t in TABLES) {
            val arr = JSONArray()
            db.rawQuery("SELECT * FROM $t", null).use { c ->
                while (c.moveToNext()) arr.put(rowToJson(c))
            }
            root.put(t, arr)
        }
        root.put("prefs", prefsToJson(ctx))
        return root.toString(2)
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

    private fun prefsToJson(ctx: Context): JSONObject {
        val o = JSONObject()
        o.put("fill_days", Prefs.fillDays(ctx))
        o.put("task_sort", Prefs.taskSort(ctx))
        o.put("task_done_mode", Prefs.taskDoneMode(ctx))
        // 場所はタスクが id で指している。控えに無いと、戻した後の「学校のみ」が読めなくなる
        o.put("places", dev.togar.dynasched.Places.toJson(dev.togar.dynasched.Places.all(ctx)))
        return o
    }

    // ---- 取り込み ----

    /** 中身を見ずに件数だけ数える（復元前に「何が入るのか」を見せるため） */
    fun peek(json: String): Report {
        val root = JSONObject(json)
        check(root.optString("format") == FORMAT) { "スキマスの控えではありません" }
        return Report(
            materials = root.optJSONArray("materials")?.length() ?: 0,
            attempts = root.optJSONArray("attempts")?.length() ?: 0,
            hobbies = root.optJSONArray("hobby_tasks")?.length() ?: 0
        )
    }

    /**
     * 控えで**まるごと置き換える**。差分にはしない。
     *
     * 教材ID・親タスクIDといった参照が控えの中で閉じているので、
     * 混ぜると「前提の教材」や親子関係がよそを指しかねない。
     * 予定は消して作り直す（教材IDを指しているため）。
     */
    fun restore(ctx: Context, json: String): Report {
        val root = JSONObject(json)
        check(root.optString("format") == FORMAT) { "スキマスの控えではありません" }
        val db = LocalDb.get(ctx).writableDatabase
        val report = peek(json)
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM scheduled_events")
            for (t in TABLES) {
                db.execSQL("DELETE FROM $t")
                val arr = root.optJSONArray(t) ?: continue
                val columns = columnsOf(db, t)
                for (i in 0 until arr.length()) {
                    insertRow(db, t, arr.getJSONObject(i), columns)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        root.optJSONObject("prefs")?.let { restorePrefs(ctx, it) }
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

    private fun restorePrefs(ctx: Context, o: JSONObject) {
        for (key in PREF_KEYS) {
            if (!o.has(key)) continue
            when (key) {
                "fill_days" -> Prefs.setFillDays(ctx, o.optInt(key, 7))
                "task_sort" -> Prefs.setTaskSort(ctx, o.optString(key, "MANUAL"))
                "task_done_mode" -> Prefs.setTaskDoneMode(ctx, o.optString(key, "INLINE"))
                "places" -> o.optJSONArray(key)?.let {
                    dev.togar.dynasched.Places.save(ctx, dev.togar.dynasched.Places.parse(it.toString()))
                }
            }
        }
    }
}
