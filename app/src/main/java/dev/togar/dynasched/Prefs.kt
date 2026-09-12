package dev.togar.dynasched

import android.content.Context

/**
 * 設定と小さなキャッシュを端末に保存する薄いラッパー。
 * 外部ライブラリは使わず標準の SharedPreferences のみ。
 *
 * ログイン情報はもう持たない（サーバーを使わなくなったため）。
 */
object Prefs {
    private const val FILE = "dynasched_prefs"
    private const val KEY_EVENTS = "cached_events" // 再起動時の通知再登録に使う
    private const val KEY_SCHED_CACHE = "schedule_cache"     // 今日の予定のオフライン表示用
    private const val KEY_SCHED_CACHE_DAY = "schedule_cache_day"
    private const val KEY_FILL_DAYS = "fill_days"  // 何日先まで空き時間を埋めるか
    const val DEFAULT_FILL_DAYS = 7

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun saveCachedEvents(ctx: Context, json: String) {
        sp(ctx).edit().putString(KEY_EVENTS, json).apply()
    }

    fun cachedEvents(ctx: Context): String? =
        sp(ctx).getString(KEY_EVENTS, null)

    /**
     * 「今日の予定」画面のオフライン表示用スナップショット。
     * 日付を一緒に持ち、日付が変わったキャッシュは使わない（前日の予定を今日として出さないため）。
     */
    fun saveScheduleCache(ctx: Context, ymd: String, json: String) {
        sp(ctx).edit()
            .putString(KEY_SCHED_CACHE, json)
            .putString(KEY_SCHED_CACHE_DAY, ymd)
            .apply()
    }

    /** 指定日のキャッシュ。日付が違えば null */
    fun scheduleCache(ctx: Context, ymd: String): String? {
        val p = sp(ctx)
        if (p.getString(KEY_SCHED_CACHE_DAY, null) != ymd) return null
        return p.getString(KEY_SCHED_CACHE, null)
    }

    /** 何日先まで空き時間を自動充填するか（1〜30、既定7） */
    fun fillDays(ctx: Context): Int =
        sp(ctx).getInt(KEY_FILL_DAYS, DEFAULT_FILL_DAYS).coerceIn(1, 30)

    fun setFillDays(ctx: Context, days: Int) {
        sp(ctx).edit().putInt(KEY_FILL_DAYS, days.coerceIn(1, 30)).apply()
    }

    /** ウィジェットの現在地設定（"home" | "out"） */
    fun widgetLoc(ctx: Context): String =
        sp(ctx).getString("widget_loc", "home") ?: "home"

    fun setWidgetLoc(ctx: Context, loc: String) {
        sp(ctx).edit().putString("widget_loc", if (loc == "out") "out" else "home").apply()
    }

    /**
     * 読み書きする端末カレンダーのID。未設定(-1)なら主カレンダーを自動で選ぶ。
     * 仕事用など複数アカウントがある端末で、意図しない方を読まないための逃げ道。
     */
    fun calendarId(ctx: Context): Long? =
        sp(ctx).getLong("calendar_id", -1L).let { if (it >= 0) it else null }

    fun setCalendarId(ctx: Context, id: Long) {
        sp(ctx).edit().putLong("calendar_id", id).apply()
    }

    // ---- 起きている時間帯（アプリ全体で共有）----
    //
    // 勉強の配置だけでなく、「今日はここまで」の通知や「暇なとき」の上限にも効く。
    // 分単位で持つ（0〜1439）。就寝が起床より前になる指定は受け付けない。

    const val DEFAULT_WAKE = 6 * 60
    const val DEFAULT_BEDTIME = 23 * 60

    fun wakeMinutes(ctx: Context): Int =
        sp(ctx).getInt("wake_min", DEFAULT_WAKE).coerceIn(0, 1439)

    fun bedtimeMinutes(ctx: Context): Int =
        sp(ctx).getInt("bedtime_min", DEFAULT_BEDTIME).coerceIn(0, 1439)
            .let { if (it <= wakeMinutes(ctx)) DEFAULT_BEDTIME else it }

    fun setWakeWindow(ctx: Context, wake: Int, bedtime: Int) {
        sp(ctx).edit()
            .putInt("wake_min", wake.coerceIn(0, 1439))
            .putInt("bedtime_min", bedtime.coerceIn(0, 1439))
            .apply()
    }

    /** 「今日はここまで」の通知を出すか */
    fun bedtimeNotice(ctx: Context): Boolean = sp(ctx).getBoolean("bedtime_notice", true)

    fun setBedtimeNotice(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("bedtime_notice", on).apply()
    }

    // ---- 単発タスク画面の見せ方 ----

    /** 並び順（TaskSort の名前で持つ） */
    fun taskSort(ctx: Context): String = sp(ctx).getString("task_sort", "MANUAL") ?: "MANUAL"

    fun setTaskSort(ctx: Context, name: String) {
        sp(ctx).edit().putString("task_sort", name).apply()
    }

    /**
     * 完了したタスクの見せ方（DoneMode の名前）。
     *
     * 以前は「下にまとめる／その場に残す」の2択だった。設定が残っている端末が
     * あるので、新しいキーが無ければ古い真偽値から読み替える。
     */
    fun taskDoneMode(ctx: Context): String {
        val p = sp(ctx)
        p.getString("task_done_mode", null)?.let { return it }
        return if (p.getBoolean("done_at_bottom", false)) "BOTTOM" else "INLINE"
    }

    fun setTaskDoneMode(ctx: Context, name: String) {
        sp(ctx).edit().putString("task_done_mode", name).apply()
    }

    /** 初回の使い方案内を見たか。人に渡した時、最初の1回だけ出すため */
    fun helpShown(ctx: Context): Boolean = sp(ctx).getBoolean("help_shown", false)

    fun setHelpShown(ctx: Context, shown: Boolean) {
        sp(ctx).edit().putBoolean("help_shown", shown).apply()
    }

    /** タブの中身（TabSource の名前）。既定はタブを出さない */
    fun taskTabSource(ctx: Context): String = sp(ctx).getString("task_tab_source", "NONE") ?: "NONE"

    fun setTaskTabSource(ctx: Context, name: String) {
        sp(ctx).edit().putString("task_tab_source", name).apply()
    }

    /** 選んでいるタブ。画面を離れて戻っても同じ場所に居られるように覚える */
    fun taskTab(ctx: Context): String = sp(ctx).getString("task_tab", "") ?: ""

    fun setTaskTab(ctx: Context, key: String) {
        sp(ctx).edit().putString("task_tab", key).apply()
    }

    /** 一覧を絞り込んでいるタグ。空なら絞らない */
    fun tagFilter(ctx: Context): Set<String> =
        sp(ctx).getStringSet("task_tag_filter", emptySet()).orEmpty()

    fun setTagFilter(ctx: Context, tags: Set<String>) {
        sp(ctx).edit().putStringSet("task_tag_filter", tags).apply()
    }

    /** 折りたたんでいる親タスクのID */
    fun collapsed(ctx: Context): Set<Long> =
        sp(ctx).getStringSet("task_collapsed", emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }.toSet()

    fun setCollapsed(ctx: Context, ids: Set<Long>) {
        sp(ctx).edit().putStringSet("task_collapsed", ids.map { it.toString() }.toSet()).apply()
    }

    // ---- Notion同期 ----
    //
    // トークンは内部インテグレーションのシークレット。**端末の外へ出さない。**
    // 書き出し（バックアップJSON）にも含めない。持ち出したファイルが漏れると
    // Notionのワークスペースごと触られるため。

    /** インテグレーションのトークン。空なら同期しない */
    fun notionToken(ctx: Context): String = sp(ctx).getString("notion_token", "") ?: ""

    fun setNotionToken(ctx: Context, token: String) {
        sp(ctx).edit().putString("notion_token", token.trim()).apply()
    }

    /** スキマスが作ったデータベース。空ならまだ作っていない */
    fun notionDatabaseId(ctx: Context): String = sp(ctx).getString("notion_db", "") ?: ""

    /**
     * 実際に読み書きする先。2025-09-03版からデータベースは「入れ物」になり、
     * 問い合わせも書き込みも**データソース**に対して行う。
     */
    fun notionDataSourceId(ctx: Context): String = sp(ctx).getString("notion_ds", "") ?: ""

    fun setNotionTarget(ctx: Context, databaseId: String, dataSourceId: String) {
        sp(ctx).edit().putString("notion_db", databaseId)
            .putString("notion_ds", dataSourceId).apply()
    }

    /** 繋がっていて同期できる状態か */
    fun notionReady(ctx: Context): Boolean =
        notionToken(ctx).isNotEmpty() && notionDataSourceId(ctx).isNotEmpty()

    /**
     * 最後に全件を取れた時刻（ISO8601）。空なら次は全件取る。
     * **全件を取った時だけ「Notionから消えた」と判断してよい**（NotionSync を参照）。
     */
    fun notionLastFullSync(ctx: Context): String = sp(ctx).getString("notion_full_at", "") ?: ""

    fun setNotionLastFullSync(ctx: Context, iso: String) {
        sp(ctx).edit().putString("notion_full_at", iso).apply()
    }

    /** 最後の同期結果の一言。設定画面に出す */
    fun notionLastResult(ctx: Context): String = sp(ctx).getString("notion_last", "") ?: ""

    fun setNotionLastResult(ctx: Context, text: String) {
        sp(ctx).edit().putString("notion_last", text).apply()
    }
}
