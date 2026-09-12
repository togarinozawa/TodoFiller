package dev.togar.dynasched.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import dev.togar.dynasched.data.Repo
import dev.togar.dynasched.BuildConfig
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.R
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.notify.AlarmScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 設定画面：起きている時間帯・カレンダーの確認・バックアップ・スケジューラ実行・更新 */
class SettingsFragment : Fragment() {

    /**
     * 保存先はファイル選択に任せる（SAF）。アプリが勝手に書ける場所へ置くと、
     * **アプリを消した時に一緒に消えて控えの意味が無くなる**。
     * 登録は画面が動き出す前に済ませる必要があるのでフィールドで持つ。
     */
    private val exportPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) writeBackup(uri) }

    private val restorePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) confirmRestore(uri) }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_settings, container, false)

        val userText = root.findViewById<TextView>(R.id.userText)
        val resyncBtn = root.findViewById<Button>(R.id.resyncButton)
        val runBtn = root.findViewById<Button>(R.id.runSchedulerButton)
        val checkUpdateBtn = root.findViewById<Button>(R.id.checkUpdateButton)
        val versionText = root.findViewById<TextView>(R.id.versionText)
        val fillDaysInput = root.findViewById<android.widget.EditText>(R.id.fillDaysInput)

        fillDaysInput.setText(Prefs.fillDays(requireContext()).toString())

        userText.text = "端末内で動いています。ログインもサーバーも使いません。"
        versionText.text = "現在のバージョン: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        root.findViewById<Button>(R.id.helpButton).setOnClickListener {
            startActivity(android.content.Intent(requireContext(), HelpActivity::class.java))
        }
        resyncBtn.setOnClickListener { resyncNotifications() }
        root.findViewById<Button>(R.id.calendarCheckButton).setOnClickListener {
            CalendarCheckDialog.show(requireActivity())
        }
        runBtn.setOnClickListener {
            // 入力された日数を保存してからスケジューラを実行
            val n = fillDaysInput.text.toString().toIntOrNull() ?: Prefs.DEFAULT_FILL_DAYS
            Prefs.setFillDays(requireContext(), n)
            fillDaysInput.setText(Prefs.fillDays(requireContext()).toString())
            runScheduler()
        }
        setupWakeWindow(root)
        root.findViewById<Button>(R.id.backupExportButton).setOnClickListener {
            exportPicker.launch(dev.togar.dynasched.data.Backup.suggestedFileName())
        }
        root.findViewById<Button>(R.id.backupRestoreButton).setOnClickListener {
            restorePicker.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        checkUpdateBtn.setOnClickListener {
            Toast.makeText(requireContext(), "更新を確認中…", Toast.LENGTH_SHORT).show()
            (requireActivity() as? androidx.appcompat.app.AppCompatActivity)?.let {
                dev.togar.dynasched.update.UpdateChecker.check(it, force = true)
            }
        }

        setupNotion(root)

        return root
    }

    // ---- Notion同期 ----

    /**
     * 繋ぐのは2段階。**トークンだけでは足りない。**Notion側でページを
     * インテグレーションに接続しておかないと、権限が無くて何も見えない
     * （その場合APIは404を返すので、迷わないようにそう案内している）。
     *
     * データベースは自分で作る。列を8つ手で作らせるより確実で、
     * 列名の食い違いという一番だるい事故も起きない。
     */
    private fun setupNotion(root: View) {
        val ctx = requireContext()
        val tokenInput = root.findViewById<android.widget.EditText>(R.id.notionTokenInput)
        val pageInput = root.findViewById<android.widget.EditText>(R.id.notionPageInput)
        val connectBtn = root.findViewById<Button>(R.id.notionConnectButton)
        val syncBtn = root.findViewById<Button>(R.id.notionSyncButton)
        val disconnectBtn = root.findViewById<Button>(R.id.notionDisconnectButton)
        val status = root.findViewById<TextView>(R.id.notionStatusText)

        fun refresh() {
            val ready = Prefs.notionReady(ctx)
            connectBtn.text = if (ready) "作り直す（今のデータベースは残ります）"
            else "接続してデータベースを作る"
            syncBtn.isEnabled = ready
            disconnectBtn.visibility = if (ready) View.VISIBLE else View.GONE
            pageInput.visibility = if (ready) View.GONE else View.VISIBLE
            val last = Prefs.notionLastResult(ctx)
            status.text = when {
                !ready -> "未接続。トークンと、置き場所にするページのURLを入れてください"
                last.isEmpty() -> "接続済み。まだ同期していません"
                else -> last
            }
        }

        // トークンは伏せ字で入るので、入っていることだけ分かるようにする
        if (Prefs.notionToken(ctx).isNotEmpty()) tokenInput.setText(Prefs.notionToken(ctx))
        refresh()

        connectBtn.setOnClickListener {
            val token = tokenInput.text.toString().trim()
            if (token.isEmpty()) {
                Toast.makeText(ctx, "トークンを入れてください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val pageId = dev.togar.dynasched.sync.NotionLink.pageIdFrom(pageInput.text.toString())
            if (pageId == null) {
                Toast.makeText(ctx, "ページのURLが読めません。Notionで「リンクをコピー」した物を貼ってください",
                    Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Prefs.setNotionToken(ctx, token)
            status.text = "接続しています…"
            connectBtn.isEnabled = false
            Api.async({
                val api = dev.togar.dynasched.sync.NotionApi(token)
                api.whoAmI()   // 先にトークンだけ試す。作りかけの物を残さないため
                api.createTaskDatabase(pageId, "スキマスのタスク")
            }, { (dbId, dsId) ->
                Prefs.setNotionTarget(ctx, dbId, dsId)
                connectBtn.isEnabled = true
                Toast.makeText(ctx, "繋がりました。最初の同期をします", Toast.LENGTH_SHORT).show()
                refresh()
                syncNow(status)
            }, { e ->
                connectBtn.isEnabled = true
                status.text = "失敗: " + Api.friendlyMessage(e)
            })
        }

        syncBtn.setOnClickListener { syncNow(status) }

        disconnectBtn.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("同期をやめる")
                .setMessage("トークンを消して同期を止めます。Notion側のデータベースも、" +
                    "端末のタスクもそのまま残ります。")
                .setPositiveButton("やめる") { _, _ ->
                    Prefs.setNotionToken(ctx, "")
                    Prefs.setNotionTarget(ctx, "", "")
                    Prefs.setNotionLastResult(ctx, "")
                    tokenInput.setText("")
                    refresh()
                }
                .setNegativeButton("戻る", null)
                .show()
        }
    }

    private fun syncNow(status: TextView) {
        val ctx = requireContext()
        status.text = "同期しています…"
        Api.async(
            { dev.togar.dynasched.sync.NotionSyncer.sync(ctx.applicationContext) },
            { r -> if (isAdded) status.text = r.message() },
            { e -> if (isAdded) status.text = "失敗: " + Api.friendlyMessage(e) }
        )
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    // ---- 起きている時間帯 ----

    /**
     * 起床と就寝を決める。**アプリ全体で共有する設定**で、
     * 配置の打ち切り・「今日はここまで」の通知・「暇なとき」の上限に効く。
     */
    private fun setupWakeWindow(root: View) {
        val button = root.findViewById<Button>(R.id.wakeWindowButton)
        val check = root.findViewById<android.widget.CheckBox>(R.id.bedtimeNoticeCheck)
        val ctx = requireContext()

        fun hhmm(m: Int) = String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60)
        fun refresh() {
            button.text = "${hhmm(Prefs.wakeMinutes(ctx))} 〜 ${hhmm(Prefs.bedtimeMinutes(ctx))}"
        }
        refresh()
        check.isChecked = Prefs.bedtimeNotice(ctx)

        check.setOnCheckedChangeListener { _, on ->
            Prefs.setBedtimeNotice(ctx, on)
            dev.togar.dynasched.notify.BedtimeReceiver.schedule(ctx)
        }

        button.setOnClickListener {
            val wake = Prefs.wakeMinutes(ctx)
            android.app.TimePickerDialog(ctx, { _, h, m ->
                val newWake = h * 60 + m
                val bed = Prefs.bedtimeMinutes(ctx)
                android.app.TimePickerDialog(ctx, { _, bh, bm ->
                    val newBed = bh * 60 + bm
                    if (newBed <= newWake) {
                        // 日をまたぐ指定は扱っていない。黙って直すより言って止める
                        Toast.makeText(ctx, "就寝は起床より後にしてください", Toast.LENGTH_LONG).show()
                        return@TimePickerDialog
                    }
                    Prefs.setWakeWindow(ctx, newWake, newBed)
                    refresh()
                    dev.togar.dynasched.notify.BedtimeReceiver.schedule(ctx)
                    Toast.makeText(ctx, "次の再生成から反映されます", Toast.LENGTH_SHORT).show()
                }, bed / 60, bed % 60, true).apply { setTitle("就寝時刻") }.show()
            }, wake / 60, wake % 60, true).apply { setTitle("起床時刻") }.show()
        }
    }

    // ---- バックアップ ----

    private fun writeBackup(uri: android.net.Uri) {
        val ctx = requireContext().applicationContext
        Api.async(
            work = {
                val json = dev.togar.dynasched.data.Backup.export(ctx)
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    ?: throw java.io.IOException("書き込み先を開けませんでした")
                dev.togar.dynasched.data.Backup.peek(json)
            },
            onSuccess = { r ->
                if (!isAdded) return@async
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("書き出しました")
                    .setMessage(
                        "教材 ${r.materials}件 / 実績 ${r.attempts}件 / 単発タスク ${r.hobbies}件\n\n" +
                            "**アプリを入れ直すと端末内のデータは消えます。**\n" +
                            "このファイルをクラウドなど別の場所にも置いておいてください。"
                    )
                    .setPositiveButton("OK", null)
                    .show()
            },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "書き出せませんでした: ${e.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

    /** 復元は全部入れ替え。取り返しがつかないので、中身を見せてから確認する */
    private fun confirmRestore(uri: android.net.Uri) {
        val ctx = requireContext().applicationContext
        Api.async(
            work = {
                val json = ctx.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: throw java.io.IOException("ファイルを開けませんでした")
                json to dev.togar.dynasched.data.Backup.peek(json)
            },
            onSuccess = { (json, r) ->
                if (!isAdded) return@async
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("復元しますか？")
                    .setMessage(
                        "控えの中身:\n教材 ${r.materials}件 / 実績 ${r.attempts}件 / 単発タスク ${r.hobbies}件\n\n" +
                            "**いま端末にあるデータは全部消えて、この控えで置き換わります。**\n" +
                            "予定は消えるので、復元後にスケジューラを実行してください。"
                    )
                    .setPositiveButton("復元する") { _, _ -> doRestore(json) }
                    .setNegativeButton("やめる", null)
                    .show()
            },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "読めませんでした: ${e.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun doRestore(json: String) {
        val ctx = requireContext().applicationContext
        Api.async(
            work = { dev.togar.dynasched.data.Backup.restore(ctx, json) },
            onSuccess = { r ->
                if (!isAdded) return@async
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("復元しました")
                    .setMessage(
                        "教材 ${r.materials}件 / 実績 ${r.attempts}件 / 単発タスク ${r.hobbies}件\n\n" +
                            "予定はまだありません。「スケジューラ実行」を押してください。"
                    )
                    .setPositiveButton("OK", null)
                    .show()
            },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "復元できませんでした: ${e.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun resyncNotifications() {
        Toast.makeText(requireContext(), "通知を再設定中…", Toast.LENGTH_SHORT).show()
        val ctx = requireContext().applicationContext
        Api.async(
            work = { Repo.current(ctx).getSchedule(ctx, today()) },
            onSuccess = { all ->
                if (!isAdded) return@async
                AlarmScheduler.scheduleAll(ctx, all)
                dev.togar.dynasched.integration.StudySync.send(ctx, all)
                Toast.makeText(requireContext(), "通知を再設定しました", Toast.LENGTH_SHORT).show()
            },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "失敗: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show()
            }
        )
    }

    /**
     * 再生成。**結果を必ず数字で見せる。**
     *
     * 以前は成功トーストを出すだけだったので、0件しか置けていない時と
     * 正常な時が見分けられなかった。「使い方が悪いのか不具合なのか」を
     * ここで判別できるようにしておく。
     */
    private fun runScheduler() {
        val days = Prefs.fillDays(requireContext())
        Toast.makeText(requireContext(), "スケジューラ実行中…（${days}日先まで）", Toast.LENGTH_SHORT).show()
        val ctx = requireContext().applicationContext
        Api.async(
            work = { Repo.current(ctx).runScheduler(ctx, days) },
            onSuccess = { report ->
                if (!isAdded) return@async
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("再生成: ${report.placed}件")
                    .setMessage(report.describe())
                    .setPositiveButton("閉じる", null)
                    .show()
            },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "失敗: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show()
            }
        )
    }
}
