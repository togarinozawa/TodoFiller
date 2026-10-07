package dev.togar.dynasched

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.api.ScheduleRepo
import dev.togar.dynasched.ui.FreeTimeDialog
import dev.togar.dynasched.ui.MaterialFragment
import dev.togar.dynasched.ui.HobbyFragment
import dev.togar.dynasched.ui.SettingsFragment
import dev.togar.dynasched.ui.TodayFragment
import dev.togar.dynasched.update.UpdateChecker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), dev.togar.dynasched.feedback.FeedbackBar.Named {

    /** 「作者に送る」に添える画面名。タブで中身が変わるので、いま出しているタブを名乗る */
    override fun feedbackScreenName(): String =
        when (findViewById<BottomNavigationView>(R.id.bottomNav)?.selectedItemId) {
            R.id.nav_today -> "今日"
            R.id.nav_single -> "単発"
            R.id.nav_material -> "教材"
            R.id.nav_settings -> "設定" + (supportFragmentManager.findFragmentById(R.id.container)
                ?.let { if (it is dev.togar.dynasched.ui.SettingsPage) "・" + it.title else "" } ?: "")
            else -> "MainActivity"
        }

    companion object {
        /** ウィジェットの「暇」から開かれたとき、条件入力ダイアログを出す */
        const val EXTRA_SHOW_FREE_TIME = "show_free_time"
    }

    private val requestNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 結果は問わない */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        // 初回は準備画面でまとめて聞く。使い方の上に許可のダイアログを重ねない
        if (Prefs.setupShown(this)) askNotificationPermission()
        syncNotifications()  // 予定タブを廃止したので、起動時に通知予約を更新する
        dev.togar.dynasched.notify.DailyRunReceiver.schedule(this)  // 毎日の自動実行を仕掛け直す
        dev.togar.dynasched.notify.BedtimeReceiver.schedule(this)   // 「今日はここまで」
        UpdateChecker.check(this)  // オンライン更新の確認（新しい版があれば案内）

        // 初回だけ使い方を出す。カレンダーにタグを付けるという大前提を知らないと、
        // 何を触っても予定が1件も出ないまま終わる
        if (!Prefs.helpShown(this)) {
            startActivity(Intent(this, dev.togar.dynasched.ui.HelpActivity::class.java))
        }

        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_today -> TodayFragment()
                R.id.nav_single -> HobbyFragment()
                R.id.nav_material -> MaterialFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> TodayFragment()
            }
            showFragment(fragment)
            true
        }

        // 設定の詳細ページにいる時に「設定」をもう一度押したら目次へ戻す
        nav.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.nav_settings) {
                supportFragmentManager.popBackStack(
                    null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
                )
            }
        }

        if (savedInstanceState == null) {
            nav.selectedItemId = R.id.nav_today
            if (intent?.getBooleanExtra(EXTRA_SHOW_FREE_TIME, false) == true) {
                FreeTimeDialog.show(this)
            }
        }
    }

    /**
     * 初回は 使い方 → はじめる前の準備 の順に出す。
     * 使い方を閉じて戻ってきた時に準備を出すので onResume で見る。
     */
    override fun onResume() {
        super.onResume()
        if (Prefs.helpShown(this) && !Prefs.setupShown(this) && !setupOpened) {
            setupOpened = true
            startActivity(Intent(this, dev.togar.dynasched.ui.SetupActivity::class.java))
        }
    }

    /** 1回の起動で準備画面を何度も出さない（「あとで」と閉じた人を追い回さない） */
    private var setupOpened = false

    /** すでに起動している状態でウィジェットの「暇」が押されたとき */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_FREE_TIME, false)) {
            FreeTimeDialog.show(this)
        }
    }

    /**
     * 今日の予定を取得してローカル通知を予約し直す（バックグラウンド）。
     * 予定タブを廃止したので起動時に必ず1度通す。TodayFragment も同じものを見るが、
     * ScheduleRepo が重複要求をまとめるので通信は1回で済む。
     */
    private fun syncNotifications() {
        val ctx = applicationContext
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        Api.async(
            work = { ScheduleRepo.refresh(ctx, today) },
            onSuccess = { /* 予約はrefreshの中で済んでいる */ },
            onError = { /* 通信できない時は何もしない */ }
        )
    }

    private fun showFragment(fragment: Fragment) {
        // 設定の詳細ページは積んで開いている。別のタブへ移る時に払っておかないと、
        // 戻るボタンで別タブの中に設定のページが出てくる
        supportFragmentManager.popBackStackImmediate(
            null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
        )
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .commit()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
