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

    /** 指定したキーのうち、いま入っている値（真偽・整数・文字列だけ）。控えの書き出し用 */
    fun values(ctx: Context, keys: Set<String>): Map<String, Any> =
        sp(ctx).all.filter { (k, v) -> k in keys && (v is Boolean || v is Int || v is String) }
            .mapValues { it.value!! }

    /** 控えから戻す。型は値に合わせる */
    fun putValues(ctx: Context, values: Map<String, Any>) {
        val e = sp(ctx).edit()
        for ((k, v) in values) when (v) {
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is String -> e.putString(k, v)
        }
        e.apply()
    }

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

    /**
     * いまいる場所（場所のid）。「いまやること」「暇なとき」が使う。
     *
     * その日に手で選んでいればそれ。選んでいなければ曜日ごとの「いつもの場所」
     * （[weekdayPlaces]）。どちらも無ければ最後に手で選んだ場所。
     * 平日なのに前の週末に選んだ「実家」のまま、にならないように。
     */
    fun widgetLoc(ctx: Context): String {
        val p = sp(ctx)
        return currentPlace(
            Places.all(ctx).map { it.id }, weekdayPlaces(ctx),
            p.getString("widget_loc", Places.HOME), p.getString("widget_loc_day", ""),
            java.time.LocalDate.now()
        )
    }

    /** 手で選んだ。今日いっぱいは曜日の場所より優先する */
    fun setWidgetLoc(ctx: Context, loc: String) {
        sp(ctx).edit().putString("widget_loc", loc)
            .putString("widget_loc_day", java.time.LocalDate.now().toString()).apply()
    }

    /**
     * [widgetLoc] の中身（テストで固定する）。
     * **消した場所を指したままにしない。**候補が永遠に0件になるので家に戻す。
     */
    fun currentPlace(
        placeIds: List<String>, weekdays: List<String>, manual: String?, manualDay: String?,
        today: java.time.LocalDate
    ): String {
        fun ok(id: String?) = !id.isNullOrEmpty() && id in placeIds
        if (manualDay == today.toString() && ok(manual)) return manual!!
        val base = weekdays.getOrNull(today.dayOfWeek.value - 1)
        if (ok(base)) return base!!
        return if (ok(manual)) manual!! else Places.HOME
    }

    /** その日のいつもの場所。曜日で決めていなければ家（カレンダーで枠を作る時の初期値） */
    fun basePlaceOn(ctx: Context, date: java.time.LocalDate): String =
        basePlaceOn(Places.all(ctx).map { it.id }, weekdayPlaces(ctx), date)

    /** [basePlaceOn] の中身（テストで固定する）。消した場所を指していれば家 */
    fun basePlaceOn(placeIds: List<String>, weekdays: List<String>, date: java.time.LocalDate): String =
        weekdays.getOrNull(date.dayOfWeek.value - 1)?.takeIf { it.isNotEmpty() && it in placeIds } ?: Places.HOME

    /** 曜日ごとのいつもの場所。月曜から7つ。空は「決めない」 */
    fun weekdayPlaces(ctx: Context): List<String> {
        val raw = (sp(ctx).getString("weekday_places", "") ?: "").split(",")
        return List(7) { raw.getOrNull(it)?.trim().orEmpty() }
    }

    fun setWeekdayPlaces(ctx: Context, ids: List<String>) {
        sp(ctx).edit().putString("weekday_places", List(7) { ids.getOrNull(it).orEmpty() }.joinToString(",")).apply()
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

    /** 文字の大きさ（端末の設定に掛ける倍率を百分率で）。[FontScale] が読む。控えに入れるので整数で持つ */
    fun fontPercent(ctx: Context): Int = sp(ctx).getInt("font_scale", 100)

    fun setFontPercent(ctx: Context, percent: Int) {
        sp(ctx).edit().putInt("font_scale", percent).apply()
    }

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

    // ---- 本人用モード・友達への配布 ----

    /**
     * 本人用モード。Notion同期を出し、テスト版（version-beta.json）を受け取る。
     * **友達の端末には出さない。**設定 → アプリについて → 版表記を7回たたくと切り替わる。
     * 一度も切り替えていなければ、Notionのトークンが入っている端末を本人とみなす
     * （友達はNotionの欄を見ないので、トークンが入るのは本人の端末だけ）。
     * キー名は v46 と同じ。v46から上書きした端末でも設定が引き継がれる。
     */
    fun ownerMode(ctx: Context): Boolean {
        val p = sp(ctx)
        return if (p.contains("owner_mode")) p.getBoolean("owner_mode", false)
            else notionToken(ctx).isNotEmpty()
    }

    fun setOwnerMode(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("owner_mode", on).apply()
    }

    /** 「はじめる前の準備」を一度通したか。初回は使い方の次に出す */
    fun setupShown(ctx: Context): Boolean = sp(ctx).getBoolean("setup_shown", false)

    fun setSetupShown(ctx: Context, shown: Boolean) {
        sp(ctx).edit().putBoolean("setup_shown", shown).apply()
    }

    /** 強制終了の記録を作者に送った（または送らないと決めた）時刻。それより古い記録では帯を赤くしない */
    fun crashReportedAt(ctx: Context): Long = sp(ctx).getLong("crash_reported_at", 0L)

    fun setCrashReportedAt(ctx: Context, at: Long) {
        sp(ctx).edit().putLong("crash_reported_at", at).apply()
    }

    /** 書きかけの「作者に送る」。閉じても消さない（長文を書いた後に押し間違えると心が折れる） */
    fun feedbackDraft(ctx: Context): Pair<String, String> {
        val p = sp(ctx)
        return (p.getString("feedback_kind", "") ?: "") to (p.getString("feedback_text", "") ?: "")
    }

    fun setFeedbackDraft(ctx: Context, kind: String, text: String) {
        sp(ctx).edit().putString("feedback_kind", kind).putString("feedback_text", text).apply()
    }

    // ---- カレンダー画面・配置の好み（v41〜v46で足したもの）----

    /** タスク一覧で、塊（子を持つタスク）を単独のタスクより後ろに並べるか */
    fun taskGroupsLast(ctx: Context): Boolean = sp(ctx).getBoolean("task_groups_last", true)

    fun setTaskGroupsLast(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("task_groups_last", on).apply()
    }

    /** 片付けた時などの動き。酔う人・電池を気にする人のために切れるようにしてある */
    fun animations(ctx: Context): Boolean = sp(ctx).getBoolean("animations", true)

    fun setAnimations(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("animations", on).apply()
    }

    /** アプリの枠を使う時も、カレンダーの予定（埋まっている時間）を避けるか */
    fun avoidBusy(ctx: Context): Boolean = sp(ctx).getBoolean("avoid_busy", true)

    fun setAvoidBusy(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("avoid_busy", on).apply()
    }

    /** カレンダー画面を横向き（横に日付・縦に時間）で見せるか */
    fun calendarHorizontal(ctx: Context): Boolean = sp(ctx).getBoolean("calendar_horizontal", true)

    fun setCalendarHorizontal(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("calendar_horizontal", on).apply()
    }

    /** 単発タスクを置く順（engine.TaskOrder の名前） */
    fun taskOrder(ctx: Context): String = sp(ctx).getString("task_order", "OLDEST") ?: "OLDEST"

    fun setTaskOrder(ctx: Context, name: String) {
        sp(ctx).edit().putString("task_order", name).apply()
    }

    /** 同じ塊のタスクをまとめて置くか散らすか（engine.Grouping の名前） */
    fun grouping(ctx: Context): String = sp(ctx).getString("grouping", "FREE") ?: "FREE"

    fun setGrouping(ctx: Context, name: String) {
        sp(ctx).edit().putString("grouping", name).apply()
    }

    /** 1日に片付けたい件数。0なら未設定（これまでの実績から決める） */
    fun dailyGoal(ctx: Context): Int = sp(ctx).getInt("daily_goal", 0)

    fun setDailyGoal(ctx: Context, n: Int) {
        sp(ctx).edit().putInt("daily_goal", n).apply()
    }

    /** 目標に届いた祝いを出した日。同じ日に何度も出さない */
    fun goalCelebratedOn(ctx: Context): String = sp(ctx).getString("goal_celebrated_on", "") ?: ""

    fun setGoalCelebratedOn(ctx: Context, day: String) {
        sp(ctx).edit().putString("goal_celebrated_on", day).apply()
    }

    /** 初回の使い方案内を見たか。人に渡した時、最初の1回だけ出すため */
    fun helpShown(ctx: Context): Boolean = sp(ctx).getBoolean("help_shown", false)

    fun setHelpShown(ctx: Context, shown: Boolean) {
        sp(ctx).edit().putBoolean("help_shown", shown).apply()
    }

    /**
     * タスク一覧の絞り込み（[dev.togar.dynasched.ui.TaskFilter] の JSON）。
     * 画面を離れて戻っても同じ絞り込みのままにする。
     * 前の版の「タグで絞り込む」が残っていれば、それを引き継ぐ
     */
    fun taskFilter(ctx: Context): dev.togar.dynasched.ui.TaskFilter {
        val p = sp(ctx)
        if (!p.contains("task_filter")) {
            val old = p.getStringSet("task_tag_filter", emptySet()).orEmpty()
            return dev.togar.dynasched.ui.TaskFilter(tags = old)
        }
        return dev.togar.dynasched.ui.TaskFilter.decode(p.getString("task_filter", ""))
    }

    fun setTaskFilter(ctx: Context, filter: dev.togar.dynasched.ui.TaskFilter) {
        sp(ctx).edit().putString("task_filter", filter.encode()).apply()
    }

    /**
     * いまやるタブの絞り込み。タスク画面の絞り込みとは別に持つ（一覧を眺める時と、やる物を選ぶ時は別の気分）。
     * ドパチルの警告画面に渡す候補も、これで絞る（いまやるタブと同じ物を出す）
     */
    fun nowFilter(ctx: Context): dev.togar.dynasched.ui.TaskFilter =
        dev.togar.dynasched.ui.TaskFilter.decode(sp(ctx).getString("now_filter", ""))

    fun setNowFilter(ctx: Context, filter: dev.togar.dynasched.ui.TaskFilter) {
        sp(ctx).edit().putString("now_filter", filter.encode()).apply()
    }

    /**
     * 配置の対象。null なら全部のタスクを置く。
     * 毎朝の自動配置にも効かせるので覚えておく（その場限りだと翌朝に黙って元へ戻る）
     */
    fun placeFilter(ctx: Context): dev.togar.dynasched.ui.TaskFilter? {
        val raw = sp(ctx).getString("place_filter", "") ?: ""
        if (raw.isEmpty()) return null
        return dev.togar.dynasched.ui.TaskFilter.decode(raw).takeUnless { it.isEmpty }
    }

    fun setPlaceFilter(ctx: Context, filter: dev.togar.dynasched.ui.TaskFilter?) {
        sp(ctx).edit().putString("place_filter", filter?.takeUnless { it.isEmpty }?.encode() ?: "").apply()
    }

    /** 選んでいるグループのタブ（[dev.togar.dynasched.ui.GroupTabs]）。画面を離れて戻っても同じ場所に居られるように */
    fun taskTab(ctx: Context): String = sp(ctx).getString("task_tab", "") ?: ""

    fun setTaskTab(ctx: Context, key: String) {
        sp(ctx).edit().putString("task_tab", key).apply()
    }

    /** タブの左右の並び（タブのキーを並べたもの）。決めていなければ空 */
    fun taskTabOrder(ctx: Context): List<String> =
        (sp(ctx).getString("task_tab_order", "") ?: "").split('\n').filter { it.isNotEmpty() }

    fun setTaskTabOrder(ctx: Context, keys: List<String>) {
        sp(ctx).edit().putString("task_tab_order", keys.joinToString("\n")).apply()
    }

    /** グループの見せ方（[dev.togar.dynasched.ui.GroupView] の名前） */
    fun groupView(ctx: Context): String = sp(ctx).getString("group_view", "TABS") ?: "TABS"

    fun setGroupView(ctx: Context, name: String) {
        sp(ctx).edit().putString("group_view", name).apply()
    }

    /**
     * 追加画面の前回の設定。親（グループ）のIDごとに1つずつ、JSONのオブジェクトにまとめて持つ。
     * 最上位へ足した時は "top" のキー。[dev.togar.dynasched.data.AddDefaults] が読み書きする。
     */
    fun lastAddJson(ctx: Context): String = sp(ctx).getString("last_add", "{}") ?: "{}"

    fun setLastAddJson(ctx: Context, json: String) {
        sp(ctx).edit().putString("last_add", json).apply()
    }

    /** 中が全部済んだグループのタブの見せ方（DoneTabs の名前） */
    fun doneTabs(ctx: Context): String = sp(ctx).getString("done_tabs", "END") ?: "END"

    fun setDoneTabs(ctx: Context, name: String) {
        sp(ctx).edit().putString("done_tabs", name).apply()
    }

    /** 折りたたんでいる親タスクのID */
    fun collapsed(ctx: Context): Set<Long> =
        sp(ctx).getStringSet("task_collapsed", emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }.toSet()

    fun setCollapsed(ctx: Context, ids: Set<Long>) {
        sp(ctx).edit().putStringSet("task_collapsed", ids.map { it.toString() }.toSet()).apply()
    }

    /** 片付いた数・増えた数を一覧の上に出すか */
    fun taskShowStats(ctx: Context): Boolean = sp(ctx).getBoolean("task_show_stats", true)

    fun setTaskShowStats(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("task_show_stats", on).apply()
    }

    /** 数える期間（Stats.Span の名前） */
    fun taskStatsSpan(ctx: Context): String = sp(ctx).getString("task_stats_span", "WEEK") ?: "WEEK"

    fun setTaskStatsSpan(ctx: Context, name: String) {
        sp(ctx).edit().putString("task_stats_span", name).apply()
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

    /**
     * 「まとめて消える計画」を一度見送った時の中身（端末IDを並べた文字列）。
     *
     * **次の同期でまったく同じ結果になったら実行する。**そうしないと、
     * Notionで本当に大量に消した時に二度と追随できなくなる。
     */
    fun notionPendingDelete(ctx: Context): String =
        sp(ctx).getString("notion_pending_delete", "") ?: ""

    fun setNotionPendingDelete(ctx: Context, key: String) {
        sp(ctx).edit().putString("notion_pending_delete", key).apply()
    }

    /**
     * Notion側の列がどこまで揃っているか。上げると次の同期で不足分を足す。
     * **2: 色の列**を足した版。
     */
    fun notionSchemaVersion(ctx: Context): Int = sp(ctx).getInt("notion_schema", 1)

    fun setNotionSchemaVersion(ctx: Context, v: Int) {
        sp(ctx).edit().putInt("notion_schema", v).apply()
    }

    /** 最後の同期結果の一言。設定画面に出す */
    fun notionLastResult(ctx: Context): String = sp(ctx).getString("notion_last", "") ?: ""

    fun setNotionLastResult(ctx: Context, text: String) {
        sp(ctx).edit().putString("notion_last", text).apply()
    }
}
