package dev.togar.dynasched.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.togar.dynasched.BuildConfig
import dev.togar.dynasched.Places
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.R

/**
 * 設定の目次。Androidの設定アプリと同じく、項目を押すと詳細ページへ進む。
 *
 * 各行には**いまの設定を一言で出す**（「06:00 〜 23:00」「未接続」など）。
 * 開かなくても状態が分かれば、開く回数そのものが減る。
 */
class SettingsFragment : Fragment() {

    private class Entry(val title: String, val summary: String, val open: () -> Unit)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_settings, container, false)
        val list = root.findViewById<LinearLayout>(R.id.settingsList)
        for (e in entries()) {
            val row = inflater.inflate(R.layout.item_settings_entry, list, false)
            row.findViewById<TextView>(R.id.entryTitle).text = e.title
            row.findViewById<TextView>(R.id.entrySummary).text = e.summary
            row.setOnClickListener { e.open() }
            list.addView(row)
        }
        return root
    }

    private fun entries(): List<Entry> {
        val ctx = requireContext()
        fun hhmm(m: Int) = String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60)
        val wake = "${hhmm(Prefs.wakeMinutes(ctx))} 〜 ${hhmm(Prefs.bedtimeMinutes(ctx))}"
        return listOf(
            Entry("場所", Places.all(ctx).joinToString("・") { it.name }) {
                open(PlacesSettingsPage())
            },
            Entry("時間帯と通知",
                wake + if (Prefs.bedtimeNotice(ctx)) "・就寝時に通知" else "") {
                open(TimeSettingsPage())
            },
            Entry("予定の自動配置", "${Prefs.fillDays(ctx)}日先まで埋める・カレンダーの確認") {
                open(ScheduleSettingsPage())
            },
            Entry("Notionと同期", if (Prefs.notionReady(ctx)) "接続済み" else "未接続") {
                open(NotionSettingsPage())
            },
            Entry("バックアップ", "書き出し・復元") { open(BackupSettingsPage()) },
            Entry("使い方", "カレンダーの印の付け方・各画面の説明") {
                startActivity(Intent(ctx, HelpActivity::class.java))
            },
            Entry("アプリについて", "v${BuildConfig.VERSION_NAME}・更新の確認") {
                open(AboutSettingsPage())
            }
        )
    }

    /**
     * 詳細ページへ進む。**下のタブと同じ入れ物に積む**ので、戻るボタンで目次に戻れる。
     * 別のタブへ移る時は MainActivity が積んだ分を払う。
     */
    private fun open(page: Fragment) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, page)
            .addToBackStack(null)
            .commit()
    }
}
