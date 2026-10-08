package dev.togar.dynasched.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import dev.togar.dynasched.AddTaskActivity
import dev.togar.dynasched.MainActivity
import dev.togar.dynasched.Places
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.R
import dev.togar.dynasched.api.ScheduledEvent
import dev.togar.dynasched.data.Repo
import dev.togar.dynasched.ui.ProgressPanel
import dev.togar.dynasched.ui.Stats
import java.time.LocalDate

/**
 * ホーム画面ウィジェット。今日の達成（棒・円・数字・升目）と、いまできることの候補を出す。
 *
 * **1枚ごとに条件を持つ**（[WidgetConfig]）。置いた時と右上の⚙で決める。
 * 候補の行を押すとそのタスクを片付けたことにする（教材はアプリを開く）。
 */
class SuggestWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE_LOC = "dev.togar.dynasched.WIDGET_TOGGLE_LOC"
        const val ACTION_REFRESH = "dev.togar.dynasched.WIDGET_REFRESH"
        const val ACTION_COMPLETE = "dev.togar.dynasched.WIDGET_COMPLETE"
        const val EXTRA_TASK_ID = "task_id"

        private val LINES = listOf(R.id.widgetLine1, R.id.widgetLine2, R.id.widgetLine3)

        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        /** キャッシュ済み予定から「次の未完了予定開始までの分数」を出す（無ければ60分） */
        private fun freeMinutes(ctx: Context): Int {
            val json = Prefs.cachedEvents(ctx) ?: return 60
            return ScheduledEvent.freeMinutesUntilNext(ScheduledEvent.fromJsonArray(json))
        }

        /** 置いてある全部を描き直す。DBを読むので別スレッドで */
        fun updateAll(ctx: Context) {
            val ids = WidgetPrefs.placedIds(ctx)
            if (ids.isEmpty()) return
            Thread {
                val mgr = AppWidgetManager.getInstance(ctx)
                for (id in ids) runCatching { mgr.updateAppWidget(id, build(ctx, id)) }
            }.start()
        }

        fun updateOne(ctx: Context, widgetId: Int) {
            Thread {
                runCatching { AppWidgetManager.getInstance(ctx).updateAppWidget(widgetId, build(ctx, widgetId)) }
            }.start()
        }

        private fun build(ctx: Context, widgetId: Int): RemoteViews {
            val cfg = WidgetPrefs.load(ctx, widgetId)
            val views = RemoteViews(ctx.packageName, R.layout.widget_suggest)
            val min = if (cfg.minutes > 0) cfg.minutes else freeMinutes(ctx)
            views.setTextViewText(R.id.widgetTitle, titleOf(ctx, cfg, min))
            renderProgress(ctx, views, cfg)
            renderSuggestions(ctx, views, cfg, min, widgetId)
            attachIntents(ctx, views, widgetId)
            return views
        }

        private fun locLabel(ctx: Context, loc: String): String =
            if (loc == Places.ANYWHERE) Places.ANYWHERE_LABEL else Places.name(Places.all(ctx), loc)

        private fun titleOf(ctx: Context, cfg: WidgetConfig, min: Int): String {
            if (cfg.title.isNotBlank()) return cfg.title
            val tags = if (cfg.tags.isEmpty()) "" else "・#" + cfg.tags.joinToString(" #")
            return "いまできること（${locLabel(ctx, cfg.loc)}・${min}分$tags）"
        }

        private fun renderSuggestions(ctx: Context, views: RemoteViews, cfg: WidgetConfig, min: Int, widgetId: Int) {
            val n = minOf(cfg.lines, LINES.size)
            for ((i, id) in LINES.withIndex()) {
                views.setViewVisibility(id, if (i < n) View.VISIBLE else View.GONE)
                views.setTextViewText(id, "")
            }
            if (n <= 0) {
                views.setTextViewText(R.id.widgetStatus, statsLine(ctx))
                return
            }
            try {
                val raw = Repo.current(ctx).getSuggestions(ctx, cfg.loc, min, n * 4, cfg.tags)
                val items = WidgetFilter.apply(raw, cfg, n)
                for ((i, item) in items.withIndex()) {
                    val id = LINES.getOrNull(i) ?: break
                    val isMaterial = item.kind == "material"
                    val mark = if (isMaterial) "🎯" else "☐"
                    val prio = when {
                        item.priority >= 7 -> "❗"
                        item.priority <= 3 -> "▽"
                        else -> ""
                    }
                    views.setTextViewText(id, "$mark$prio ${item.title}（${item.minutes}分）")
                    // タスクは押せば片付く。教材は何問やったかを聞くのでアプリで
                    views.setOnClickPendingIntent(id,
                        if (isMaterial) openAppIntent(ctx, widgetId, i) else completeIntent(ctx, widgetId, item.id, i))
                }
                if (items.isEmpty()) views.setTextViewText(LINES[0], "この条件に合う候補はありません")
                views.setTextViewText(R.id.widgetStatus, statsLine(ctx))
            } catch (e: Exception) {
                views.setTextViewText(LINES[0], "取得できませんでした")
                views.setTextViewText(R.id.widgetStatus, "タップで再読み込み")
            }
        }

        private fun renderProgress(ctx: Context, views: RemoteViews, cfg: WidgetConfig) {
            if (cfg.style == ProgressStyle.NONE || !Prefs.taskShowStats(ctx)) {
                views.setViewVisibility(R.id.widgetProgress, View.GONE)
                return
            }
            try {
                val today = LocalDate.now()
                val rows = Repo.current(ctx).statRows(ctx)
                val p = Stats.today(rows, today, ProgressPanel.goalOf(ctx, rows, today))
                val streak = Stats.streak(rows, today)
                val accent = ContextCompat.getColor(ctx, R.color.accent_goal)
                val primary = ContextCompat.getColor(ctx, R.color.primary)
                views.setViewVisibility(R.id.widgetProgress, View.VISIBLE)
                views.setTextViewText(R.id.widgetBigNumber, p.big.toString())
                views.setTextViewText(R.id.widgetBigLabel, p.bigLabel)
                views.setTextColor(R.id.widgetBigNumber, if (p.reached) accent else primary)
                views.setProgressBar(R.id.widgetGoalBar, 100, (p.done * 100 / p.goal).coerceIn(0, 100), false)
                views.setTextViewText(R.id.widgetStreak, when {
                    p.reached && streak >= 2 -> "済・${streak}日"
                    p.reached -> "今日の分は済み"
                    streak >= 2 -> "${streak}日つづけて"
                    else -> ""
                })
                val ring = cfg.style == ProgressStyle.RING
                val chainOnly = cfg.style == ProgressStyle.CHAIN
                val showChain = chainOnly || cfg.style == ProgressStyle.BAR || ring
                views.setViewVisibility(R.id.widgetRing, if (ring) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.widgetBigNumber, if (ring || chainOnly) View.GONE else View.VISIBLE)
                views.setViewVisibility(R.id.widgetBigLabel, if (chainOnly) View.GONE else View.VISIBLE)
                views.setViewVisibility(R.id.widgetGoalBar, if (cfg.style == ProgressStyle.BAR) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.widgetChain, if (showChain) View.VISIBLE else View.GONE)
                if (ring) views.setImageViewBitmap(R.id.widgetRing, RingImage.render(ctx, p.done, p.goal, p.big, p.reached))
                if (showChain) views.setImageViewBitmap(R.id.widgetChain, ChainImage.render(ctx, Stats.daily(rows, today, 28), today))
            } catch (e: Exception) {
                views.setViewVisibility(R.id.widgetProgress, View.GONE)
            }
        }

        /** 一番下の一行。オヤスギと、今日増やした数・残り */
        private fun statsLine(ctx: Context): String {
            if (!Prefs.taskShowStats(ctx)) return ""
            return try {
                val repo = Repo.current(ctx)
                val p = Stats.today(repo.statRows(ctx), LocalDate.now(), 1)
                val pt = repo.points(ctx)
                val points = if (pt.total > 0)
                    "オヤスギ ${pt.total}" + (if (pt.today > 0) "（今日 +${pt.today}）" else "") + " ・ " else ""
                "${points}増やした ${p.added} ・ 残り ${p.undone}"
            } catch (e: Exception) {
                ""   // 数えられなくてもウィジェット本体は出す
            }
        }

        // ---- 押した時 ----
        //
        // PendingIntent は「同じ中身なら同じ物」とみなされる。ウィジェットが何枚もあると
        // 別の枚のボタンが混ざるので、枚ごと・行ごとに data と requestCode を変えてある。

        private fun completeIntent(ctx: Context, widgetId: Int, taskId: Long, line: Int): PendingIntent {
            val i = Intent(ctx, SuggestWidgetProvider::class.java).apply {
                action = ACTION_COMPLETE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                putExtra(EXTRA_TASK_ID, taskId)
                data = Uri.parse("skimas://widget/$widgetId/done/$taskId")
            }
            return PendingIntent.getBroadcast(ctx, widgetId * 100 + 20 + line, i, FLAGS)
        }

        private fun openAppIntent(ctx: Context, widgetId: Int, line: Int): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(ctx, widgetId * 100 + 30 + line, i, FLAGS)
        }

        private fun code(widgetId: Int, n: Int) = widgetId * 10 + n

        private fun attachIntents(ctx: Context, views: RemoteViews, widgetId: Int) {
            fun broadcast(action: String, n: Int): PendingIntent {
                val i = Intent(ctx, SuggestWidgetProvider::class.java).apply {
                    this.action = action
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    data = Uri.parse("skimas://widget/$widgetId/$action")
                }
                return PendingIntent.getBroadcast(ctx, code(widgetId, n), i, FLAGS)
            }
            views.setOnClickPendingIntent(R.id.widgetLocToggle, broadcast(ACTION_TOGGLE_LOC, 1))
            views.setOnClickPendingIntent(R.id.widgetRefresh, broadcast(ACTION_REFRESH, 2))
            // 本体タップでアプリを開く
            val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            views.setOnClickPendingIntent(R.id.widgetRoot, PendingIntent.getActivity(ctx, code(widgetId, 3), open, FLAGS))
            // 「暇」→ アプリを開いて条件入力ダイアログを出す
            val free = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_SHOW_FREE_TIME, true)
            views.setOnClickPendingIntent(R.id.widgetFree, PendingIntent.getActivity(ctx, code(widgetId, 4), free, FLAGS))
            // 「＋」→ タスク追加画面を直接開く。思いついた時にアプリを辿らずに済ませる
            val add = Intent(ctx, AddTaskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            views.setOnClickPendingIntent(R.id.widgetAdd, PendingIntent.getActivity(ctx, code(widgetId, 5), add, FLAGS))
            // ⚙ → この1枚の設定
            val config = WidgetConfigActivity.intent(ctx, widgetId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            views.setOnClickPendingIntent(R.id.widgetConfig, PendingIntent.getActivity(ctx, code(widgetId, 6), config, FLAGS))
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        for (id in appWidgetIds) WidgetPrefs.delete(context, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0)
        when (intent.action) {
            ACTION_TOGGLE_LOC -> {
                if (widgetId == 0) return
                // 場所 → 次の場所 → … → どこでも → 最初の場所 と回す
                val cfg = WidgetPrefs.load(context, widgetId)
                val order = Places.all(context).map { it.id } + Places.ANYWHERE
                val next = order[(order.indexOf(cfg.loc) + 1).mod(order.size)]
                WidgetPrefs.save(context, widgetId, cfg.copy(loc = next))
                // 「暇なとき」の既定もそろえる（どこでもは場所ではないので入れない）
                if (next != Places.ANYWHERE) Prefs.setWidgetLoc(context, next)
                updateOne(context, widgetId)
            }
            ACTION_REFRESH -> if (widgetId == 0) updateAll(context) else updateOne(context, widgetId)
            ACTION_COMPLETE -> {
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                if (taskId <= 0) return
                val app = context.applicationContext
                Thread {
                    runCatching { Repo.current(app).setHobbyCompleted(app, taskId, true) }
                    updateAll(app)
                }.start()
            }
        }
    }
}
