package dev.togar.dynasched.update

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import dev.togar.dynasched.BuildConfig
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.api.AppVersionInfo
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * オンライン(OTA)アップデート。
 * 起動時にサーバーの GET /app/version を見に行き、
 * version_code が今インストールされている物より新しければ、
 * ユーザーに確認してから新しいAPKをダウンロードしてインストール画面を開く。
 *
 * Playストア非配布のアプリを自分のサーバー経由で更新する仕組み。
 * サーバー側で新しいAPKを配置し version_code を上げれば、全端末が次回起動時に更新に気づく。
 */
object UpdateChecker {

    private val main = Handler(Looper.getMainLooper())
    // 二重起動防止（1セッションに1回だけ確認する）
    @Volatile private var alreadyChecked = false

    /** 起動時に呼ぶ。通信できなければ静かに何もしない。 */
    fun check(activity: AppCompatActivity, force: Boolean = false) {
        if (alreadyChecked && !force) return
        alreadyChecked = true
        val app = activity.applicationContext
        Api.async(
            work = { Api.getAppVersion(app) },
            onSuccess = { info ->
                if (activity.isFinishing || activity.isDestroyed) return@async
                if (info.versionCode > BuildConfig.VERSION_CODE && info.url.isNotBlank()) {
                    promptUpdate(activity, info)
                } else if (force) {
                    Toast(activity, "最新版を使っています（v${BuildConfig.VERSION_NAME}）")
                }
            },
            onError = {
                if (force && !activity.isFinishing) {
                    Toast(activity, "更新確認に失敗しました")
                }
            }
        )
    }

    private fun promptUpdate(activity: AppCompatActivity, info: AppVersionInfo) {
        val msg = buildString {
            append("新しいバージョン ")
            append(info.versionName.ifBlank { "v${info.versionCode}" })
            append(" が利用できます。\n")
            append("（現在: v${BuildConfig.VERSION_NAME}）")
            if (info.notes.isNotBlank()) {
                append("\n\n").append(info.notes)
            }
        }
        AlertDialog.Builder(activity)
            .setTitle("アップデート")
            .setMessage(msg)
            .setPositiveButton("更新") { _, _ -> ensurePermissionThenDownload(activity, info) }
            .setNegativeButton("後で", null)
            .show()
    }

    /** 「提供元不明のアプリ」インストール許可を確認。無ければ設定画面に誘導。 */
    private fun ensurePermissionThenDownload(activity: AppCompatActivity, info: AppVersionInfo) {
        val canInstall = activity.packageManager.canRequestPackageInstalls()
        if (!canInstall) {
            AlertDialog.Builder(activity)
                .setTitle("インストールの許可が必要です")
                .setMessage("アプリを更新するには「このアプリからのインストールを許可」をオンにしてください。設定画面を開きます。")
                .setPositiveButton("設定を開く") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                    try {
                        activity.startActivity(intent)
                    } catch (e: Exception) {
                        Toast(activity, "設定画面を開けませんでした")
                    }
                }
                .setNegativeButton("キャンセル", null)
                .show()
            return
        }
        downloadAndInstall(activity, info)
    }

    private fun downloadAndInstall(activity: AppCompatActivity, info: AppVersionInfo) {
        // 進捗ダイアログ
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val label = TextView(activity).apply { text = "ダウンロード中… 0%" }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
        }
        container.addView(label)
        container.addView(bar)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("更新をダウンロード")
            .setView(container)
            .setCancelable(false)
            .create()
        dialog.show()

        Thread {
            try {
                val apk = File(activity.getExternalFilesDir(null), "dynasched-update.apk")
                if (apk.exists()) apk.delete()
                // 回線断（connection abort等）で切れても、続きから最大5回まで再開する
                var attempt = 0
                var lastError: Exception? = null
                var done = false
                while (!done && attempt < 5) {
                    attempt++
                    try {
                        done = downloadOnce(info.url, apk) { pct ->
                            main.post {
                                if (pct >= 0) {
                                    bar.isIndeterminate = false
                                    bar.progress = pct
                                    label.text = "ダウンロード中… $pct%" +
                                        (if (attempt > 1) "（再開 $attempt 回目）" else "")
                                } else {
                                    bar.isIndeterminate = true
                                }
                            }
                        }
                    } catch (e: Exception) {
                        lastError = e
                        Thread.sleep(1500L * attempt) // 少し待って再開
                    }
                }
                if (!done) throw lastError ?: Exception("ダウンロードできませんでした")

                main.post {
                    if (!activity.isFinishing) dialog.dismiss()
                    launchInstall(activity, apk, info.url)
                }
            } catch (e: Exception) {
                main.post {
                    if (!activity.isFinishing) dialog.dismiss()
                    Toast(activity, "ダウンロード失敗: ${e.message}")
                }
            }
        }.start()
    }

    /**
     * 1回分のダウンロード試行。apk に既に部分データがあれば Range で続きから取得する。
     * 完走したら true。onProgress には 0-100（不明なら -1）を渡す。
     */
    private fun downloadOnce(url: String, apk: File, onProgress: (Int) -> Unit): Boolean {
        var urlStr = url
        var redirects = 0
        while (redirects < 5) {
            val already = if (apk.exists()) apk.length() else 0L
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20000
                conn.readTimeout = 60000
                conn.requestMethod = "GET"
                if (already > 0) conn.setRequestProperty("Range", "bytes=$already-")
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    if (loc.isNullOrEmpty()) throw Exception("リダイレクト先不明")
                    urlStr = loc
                    redirects++
                    continue
                }
                // Rangeが効かずに200が返ったら最初から取り直す
                val append = code == 206 && already > 0
                if (!append && apk.exists()) apk.delete()
                if (code !in 200..299) throw Exception("HTTP $code")

                val base = if (append) already else 0L
                val total = base + conn.contentLength.toLong().coerceAtLeast(0L)
                conn.inputStream.use { input ->
                    FileOutputStream(apk, append).use { out ->
                        val buf = ByteArray(16 * 1024)
                        var readSum = base
                        var n: Int
                        var lastPct = -1
                        while (input.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                            readSum += n
                            if (total > base) {
                                val pct = (readSum * 100 / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                            } else {
                                onProgress(-1)
                            }
                        }
                        out.flush()
                    }
                }
                return true
            } finally {
                conn.disconnect()
            }
        }
        throw Exception("リダイレクトが多すぎます")
    }

    /**
     * 署名鍵が同じかを、インストーラへ渡す前に確かめる。
     *
     * Android は署名の違うAPKの上書きを拒むが、画面には
     * **「アプリがインストールされていません」としか出ない。**
     * 落としたAPKが壊れているのか、容量が足りないのか、鍵が違うのかが
     * 区別できず、原因に辿り着くまで時間を溶かす。実際2度やった。
     * ここで先に見て、違うなら何をすればいいかを言葉で出す。
     *
     * @return 同じ鍵なら true。判定できない時も true（余計な邪魔をしない）
     */
    private fun sameSigningKey(activity: AppCompatActivity, apk: File): Boolean {
        return try {
            val pm = activity.packageManager
            @Suppress("DEPRECATION")
            val flag = android.content.pm.PackageManager.GET_SIGNATURES
            @Suppress("DEPRECATION")
            val downloaded = pm.getPackageArchiveInfo(apk.absolutePath, flag)?.signatures
            @Suppress("DEPRECATION")
            val installed = pm.getPackageInfo(activity.packageName, flag)?.signatures
            if (downloaded.isNullOrEmpty() || installed.isNullOrEmpty()) return true
            val a = downloaded.map { it.toCharsString() }.toSet()
            val b = installed.map { it.toCharsString() }.toSet()
            a == b
        } catch (e: Exception) {
            true   // 判定できないなら止めない
        }
    }

    /** 落とし先のURLを見せてコピーできるようにする。ブラウザで開いて入れ直す時に要る */
    private fun warnDifferentKey(activity: AppCompatActivity, apk: File, url: String) {
        AlertDialog.Builder(activity)
            .setTitle("このまま上書きはできません")
            .setMessage(dev.togar.dynasched.ui.Emphasis.render(
                "新しいAPKは**別の鍵で署名**されています。\n" +
                    "Androidは署名の違うアプリの上書きを拒むので、このまま進めても" +
                    "「アプリがインストールされていません」と出るだけです。\n\n" +
                    "入れ替えるには:\n" +
                    "1. 設定 → バックアップを書き出す\n" +
                    "2. このアプリをアンインストール\n" +
                    "3. 下のURLをブラウザで開いて落とし、入れる\n" +
                    "4. 起動 → 設定 → バックアップから復元\n\n" +
                    "**先にバックアップを取ってください。アンインストールで端末内のデータは消えます。**\n\n" +
                    url
            ))
            .setPositiveButton("わかった", null)
            .setNeutralButton("URLをコピー") { _, _ ->
                activity.getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("スキマスのAPK", url))
                Toast(activity, "URLをコピーしました")
            }
            .setNegativeButton("それでも進む") { _, _ -> startInstaller(activity, apk) }
            .show()
    }

    private fun launchInstall(activity: AppCompatActivity, apk: File, url: String) {
        if (!sameSigningKey(activity, apk)) {
            warnDifferentKey(activity, apk, url)
            return
        }
        startInstaller(activity, apk)
    }

    private fun startInstaller(activity: AppCompatActivity, apk: File) {
        try {
            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast(activity, "インストール画面を開けませんでした: ${e.message}")
        }
    }

    private fun Toast(activity: AppCompatActivity, text: String) {
        android.widget.Toast.makeText(activity, text, android.widget.Toast.LENGTH_LONG).show()
    }
}
