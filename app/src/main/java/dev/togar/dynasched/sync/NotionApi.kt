package dev.togar.dynasched.sync

import dev.togar.dynasched.api.ApiException
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * NotionのREST APIを叩く。**外部ライブラリなし**（HttpURLConnection と org.json のみ）。
 *
 * 使うのは内部インテグレーションのトークン。**自分のワークスペースだけで使う分には
 * OAuthも審査も要らない**ので、Googleカレンダーで審査を避けたのと同じ形になる。
 *
 * ## データソース
 *
 * 2025-09-03版から、データベースは「入れ物」で、中身は**データソース**に変わった。
 * 問い合わせも書き込みも `data_source_id` に対して行う。古い `databases/{id}/query`
 * ではもう届かない。作成時だけはデータベースを作り、そこから data_source_id を拾う。
 *
 * すべてワーカースレッドから呼ぶこと。
 */
class NotionApi(private val token: String) {

    companion object {
        private const val BASE = "https://api.notion.com/v1"

        /**
         * 日付でのAPI版指定。**2025-09-03以上でないとデータソースが使えない。**
         * 上げる時は壊れる変更が入っていないか確かめること（Notionは日付版で、
         * 後方互換が壊れる時だけ新しい日付が出る）。
         */
        private const val VERSION = "2025-09-03"

        /** 1回の問い合わせで取る件数。Notionの上限は100 */
        private const val PAGE_SIZE = 100
    }

    // ---- 接続と作成 ----

    /** トークンが生きているか。設定画面の「確認」用 */
    fun whoAmI(): String {
        val o = request("GET", "$BASE/users/me", null)
        return o.optString("name", "").ifEmpty { o.optString("id", "") }
    }

    /**
     * タスク用のデータベースを作る。返すのは (databaseId, dataSourceId)。
     *
     * **2段階でしか作れない。**「親タスク」は自分自身を指すリレーションなので、
     * データソースが出来るまで相手のIDが決まらない。作ってから足す。
     */
    fun createTaskDatabase(parentPageId: String, title: String): Pair<String, String> {
        val body = JSONObject()
            .put("parent", JSONObject().put("type", "page_id").put("page_id", parentPageId))
            .put("title", JSONArray().put(
                JSONObject().put("text", JSONObject().put("content", title))
            ))
            .put("initial_data_source", JSONObject().put("properties", NotionMap.initialSchema()))

        val created = request("POST", "$BASE/databases", body)
        val dbId = created.optString("id", "")
        if (dbId.isEmpty()) throw ApiException(0, "データベースを作れませんでした")

        val dsId = firstDataSourceId(created).ifEmpty { dataSourceOf(dbId) }
        if (dsId.isEmpty()) throw ApiException(0, "データソースが見つかりませんでした")

        // ここで初めて相手のIDが分かるので、親タスクの列を足す
        request("PATCH", "$BASE/data_sources/$dsId", NotionMap.parentRelationPatch(dsId))
        return dbId to dsId
    }

    /** データベースIDからデータソースIDを引く */
    fun dataSourceOf(databaseId: String): String =
        firstDataSourceId(request("GET", "$BASE/databases/$databaseId", null))

    private fun firstDataSourceId(db: JSONObject): String =
        db.optJSONArray("data_sources")?.optJSONObject(0)?.optString("id", "").orEmpty()

    // ---- 読む ----

    /**
     * データソースの中身を取る。[since] を渡すとその時刻以降に編集された分だけ。
     *
     * **絞って取った結果を「全件」として扱ってはいけない。**「Notionから消えた」の
     * 判断が効いてしまい、更新が無かっただけのタスクを端末から全部消す。
     */
    fun query(dataSourceId: String, since: String? = null): List<NotionTask> {
        val out = ArrayList<NotionTask>()
        var cursor: String? = null
        do {
            val body = JSONObject().put("page_size", PAGE_SIZE)
            if (cursor != null) body.put("start_cursor", cursor)
            if (!since.isNullOrEmpty()) {
                body.put(
                    "filter", JSONObject()
                        .put("timestamp", "last_edited_time")
                        .put("last_edited_time", JSONObject().put("after", since))
                )
            }
            val res = request("PATCH", "$BASE/data_sources/$dataSourceId/query", body)
            val results = res.optJSONArray("results") ?: JSONArray()
            for (i in 0 until results.length()) {
                val page = results.optJSONObject(i) ?: continue
                // 1件が壊れていても同期全体は止めない
                NotionMap.readPage(page)?.let { out.add(it) }
            }
            cursor = if (res.optBoolean("has_more", false)) res.optString("next_cursor", "") else null
            if (cursor.isNullOrEmpty()) cursor = null
        } while (cursor != null)
        return out
    }

    // ---- 書く ----

    /** 新しいページを作って、そのページIDを返す */
    fun createPage(dataSourceId: String, task: LocalTask, parentPageId: String?): String {
        val body = JSONObject()
            .put("parent", JSONObject()
                .put("type", "data_source_id").put("data_source_id", dataSourceId))
            .put("properties", NotionMap.writeProperties(task, parentPageId))
        val o = request("POST", "$BASE/pages", body)
        return o.optString("id", "")
    }

    /** 端末が勝つ回。中身をまるごと押し返す */
    fun updatePage(pageId: String, task: LocalTask, parentPageId: String?) {
        request("PATCH", "$BASE/pages/$pageId",
            JSONObject().put("properties", NotionMap.writeProperties(task, parentPageId)))
    }

    /** 実行の記録だけ書き戻す。Notionで整理した中身は踏まない */
    fun updateStatus(pageId: String, task: LocalTask) {
        request("PATCH", "$BASE/pages/$pageId",
            JSONObject().put("properties", NotionMap.writeStatus(task)))
    }

    /** 親を繋ぎ直す。作った直後の紐付けに使う */
    fun updateParent(pageId: String, parentPageId: String?) {
        request("PATCH", "$BASE/pages/$pageId",
            JSONObject().put("properties", NotionMap.writeParent(parentPageId)))
    }

    /** Notion側を畳む。完全な削除はしない（本人がゴミ箱から戻せるように） */
    fun archivePage(pageId: String) {
        request("PATCH", "$BASE/pages/$pageId", JSONObject().put("in_trash", true))
    }

    // ---- 低レベル ----

    private fun request(method: String, url: String, body: JSONObject?): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Notion-Version", VERSION)
        conn.setRequestProperty("Accept", "application/json")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            }.orEmpty()
            if (code !in 200..299) throw ApiException(code, describe(code, text))
            return if (text.isEmpty()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Notionのエラーを日本語にする。生のJSONを出しても直しようがないため。
     * **トークンは絶対に混ぜない**（本文に載ることは無いが、念のため本文も要約に留める）。
     */
    private fun describe(code: Int, body: String): String {
        val message = try {
            JSONObject(body).optString("message", "")
        } catch (e: Exception) {
            ""
        }
        return when (code) {
            401 -> "トークンが違うか、失効しています"
            403 -> "このインテグレーションに権限がありません。Notionでページを接続してください"
            404 -> "見つかりません。ページがインテグレーションに接続されているか確認してください"
            429 -> "Notionの制限に掛かりました。少し待ってからもう一度"
            in 500..599 -> "Notion側で問題が起きています ($code)"
            else -> message.ifEmpty { "Notionへの要求が失敗しました ($code)" }
        }
    }
}
