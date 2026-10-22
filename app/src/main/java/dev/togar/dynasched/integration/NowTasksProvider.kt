package dev.togar.dynasched.integration

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import dev.togar.dynasched.Places
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.data.NowTasks

/**
 * ドパチルが警告画面を出す瞬間に「いまやれるタスク」を読みに来る口。読むだけ。
 * 仕様は docs/dopachiru-tasks.md。
 *
 * **こちらから送らず、向こうに読ませる。** 送る形だと、片付けたタスクや前の場所の候補が
 * 次に送るまで残る。開いた瞬間に読めば、いまの場所・時間・完了がそのまま反映される。
 *
 * 壊れても制限には響かない。読めなければ向こうはタスクを出さないだけ
 * （ドパチルの判定はスキマスに一切頼らない）。
 *
 * 配置を絞っていれば（タスク画面の「いまの絞り込みだけ配置する」）、それに当たるものだけを出す。
 * 「いまはこれに集中する」と決めた範囲の外を、警告画面で勧めないため。
 */
class NowTasksProvider : ContentProvider() {

    companion object {
        /** 読みに来る URI の末尾。`content://dev.togar.dynasched.nowtasks/now` */
        const val PATH = "now"
        const val DEFAULT_LIMIT = 3
        const val MAX_LIMIT = 10

        val COLUMNS = arrayOf("kind", "id", "title", "minutes", "priority", "reason", "parent")
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        val pkg = callingPackage
        if (!TrustedCallers.allowed(pkg, pkg?.let { TrustedCallers.certDigests(ctx, it) }.orEmpty())) {
            throw SecurityException("スキマスのタスクは登録された相手にしか読ませません: $pkg")
        }
        if (uri.lastPathSegment != PATH) return null
        val limit = uri.getQueryParameter("limit")?.toIntOrNull()?.coerceIn(1, MAX_LIMIT) ?: DEFAULT_LIMIT

        val now = NowTasks.pick(ctx, Prefs.placeFilter(ctx))
        val cursor = MatrixCursor(COLUMNS)
        for (item in now.items.take(limit)) {
            cursor.addRow(arrayOf<Any?>(
                NowTasks.externalKind(item.kind), item.id, item.title, item.minutes,
                item.priority, NowTasks.reason(item, dev.togar.dynasched.Priorities.all(ctx)),
                now.parents[item.id].orEmpty()
            ))
        }
        cursor.extras = Bundle().apply {
            putString("place", now.place)
            putString("place_name", Places.name(Places.all(ctx), now.place))
            putInt("available_minutes", now.minutes)
            putLong("generated_at", System.currentTimeMillis() / 1000)
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.dev.togar.dynasched.nowtask"

    // 読むだけ。書き換えはスキマスの中でしかさせない（完了が二か所で付くと食い違う）
    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("読み取り専用")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("読み取り専用")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("読み取り専用")
}
