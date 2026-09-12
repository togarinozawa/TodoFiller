package dev.togar.dynasched.api

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.togar.dynasched.BuildConfig
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * [friendly] は「このメッセージはもう日本語で、そのまま見せてよい」印。
 * 付いていない物は本文が生のJSONやHTMLなので、[Api.friendlyMessage] が言い換える。
 */
class ApiException(
    val code: Int,
    message: String,
    val friendly: Boolean = false
) : Exception(message)

/**
 * バックグラウンド実行と、更新の確認。
 *
 * **アプリは端末内で完結している。** 予定・教材・タスクはすべて端末のSQLiteと
 * カレンダーにあり、サーバーは無い（畳んだ）。ここに残っている通信は
 * **新しい版が出ていないかを見に行く1本だけ**で、圏外でも本体は普通に動く。
 *
 * `async` はHTTP専用ではなく、DBやカレンダーの読み書きを画面から追い出すための
 * 共通のワーカーとして全画面で使っている。名前がApiなのは呼び出し側の都合。
 *
 * 外部ライブラリ不使用。Android標準の HttpURLConnection と org.json だけ。
 */
object Api {

    /**
     * 単一スレッドだと、スケジューラ実行の間に他の画面の処理が全部詰まる。
     * 数本のプールにして、遅い呼び出しが他をブロックしないようにする。
     */
    private val io = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "skimas-worker").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** バックグラウンドで処理し、結果/失敗をメインスレッドへ返す簡易ヘルパー */
    fun <T> async(
        work: () -> T,
        onSuccess: (T) -> Unit,
        onError: (Exception) -> Unit
    ) {
        io.execute {
            try {
                val result = work()
                mainHandler.post { onSuccess(result) }
            } catch (e: Exception) {
                mainHandler.post { onError(e) }
            }
        }
    }

    /**
     * 例外を利用者に見せられる日本語にする。
     * 生の `failed to connect to ... (port 443)` を出さないため。
     */
    fun friendlyMessage(e: Exception): String = when {
        // 呼び出し側が既に日本語にしている物は、そのまま見せる。
        // ここで言い換えると**何が起きたのか分からなくなる**（実際にそれで一度詰まった）
        e is ApiException && e.friendly && !e.message.isNullOrBlank() -> e.message!!
        e is ApiException && e.code == 404 -> "対象が見つかりませんでした"
        e is ApiException -> "取得できませんでした (${e.code})"
        e is java.net.SocketTimeoutException -> "通信がタイムアウトしました"
        e is java.net.UnknownHostException -> "ネットワークに接続されていません"
        e is java.io.IOException -> "通信に失敗しました"
        else -> e.message ?: "不明なエラー"
    }

    /** 置き場所を移した時のために、Application Context を覚えておく（App.onCreateでセット） */
    @Volatile var appContext: Context? = null

    // ---- 更新の確認 ----

    /**
     * 最新版の情報を取得する。
     *
     * 置き場所は**GitHubの `dist` ブランチ**。版を重ねるたびに中身を差し替えるので、
     * リポジトリに残るAPKは常に1つだけになる（普通にコミットすると、古いAPKが
     * 履歴に永久に積み上がる）。
     */
    fun getAppVersion(ctx: Context): AppVersionInfo {
        val body = get(BuildConfig.UPDATE_MANIFEST_URL)
        val o = JSONObject(body)
        return AppVersionInfo(
            versionCode = o.optInt("version_code", 0),
            versionName = o.optString("version_name", ""),
            url = o.optString("url", ""),
            notes = o.optString("notes", "")
        )
    }

    // ---- 低レベルHTTP ----

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/json")
        // GitHubの生ファイルはキャッシュが効くので、古い版を見続けないようにする
        conn.setRequestProperty("Cache-Control", "no-cache")
        try {
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""
            if (conn.responseCode !in 200..299) {
                throw ApiException(conn.responseCode, body.ifEmpty { "HTTP ${conn.responseCode}" })
            }
            return body
        } finally {
            conn.disconnect()
        }
    }
}

/** 最新版アプリの情報 */
data class AppVersionInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val notes: String
)
