package dev.togar.dynasched.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import dev.togar.dynasched.Places
import dev.togar.dynasched.api.SuggestItem

/** ウィジェットに今日の達成をどう見せるか */
enum class ProgressStyle(val label: String) {
    BAR("棒と数字"),
    RING("円と数字"),
    NUMBER("数字だけ"),
    CHAIN("4週間の升目だけ"),
    NONE("出さない");

    companion object {
        fun from(name: String?): ProgressStyle = entries.firstOrNull { it.name == name } ?: BAR
    }
}

/**
 * ウィジェット1枚ぶんの設定。**枚ごとに別の条件にできる**（勉強用・家事用…）。
 *
 * @param loc 場所のid（家・外・設定で足した場所）か「どこでも」
 * @param minutes 空き時間。0なら次の予定まで
 * @param minMinutes これより短い作業は出さない（0なら下限なし）
 * @param kind `both` / `hobby`（タスクだけ）/ `material`（教材だけ）
 * @param minPriority これより低い優先度は出さない（0ならすべて）
 * @param lines 候補を何行出すか（0なら達成だけ）
 * @param title 見出し。空なら自動
 */
data class WidgetConfig(
    val style: ProgressStyle = ProgressStyle.BAR,
    val loc: String = Places.HOME,
    val minutes: Int = 0,
    val minMinutes: Int = 0,
    val tags: Set<String> = emptySet(),
    val kind: String = "both",
    val minPriority: Int = 0,
    val lines: Int = 3,
    val title: String = ""
) {
    fun summary(places: List<dev.togar.dynasched.Place>): String {
        val where = if (loc == Places.ANYWHERE) Places.ANYWHERE_LABEL else Places.name(places, loc)
        var time = if (minutes <= 0) "次の予定まで" else "${minutes}分"
        if (minMinutes > 0) time += "（${minMinutes}分以上）"
        val what = when (kind) {
            "hobby" -> "タスクだけ"
            "material" -> "教材だけ"
            else -> "タスクと教材"
        }
        val t = if (tags.isEmpty()) "" else " #" + tags.joinToString(" #")
        return "$where・$time・$what・${style.label}$t"
    }

    companion object {
        private const val SEP = ";"
        private const val EQ = "="

        /** 保存の形は `style=BAR;loc=home;...`。v46 と同じなので、上げてもウィジェットの設定が残る */
        fun encode(c: WidgetConfig): String = listOf(
            "style=${c.style.name}", "loc=${c.loc}", "min=${c.minutes}", "minLo=${c.minMinutes}",
            "tags=" + c.tags.joinToString(",") { clean(it) },
            "kind=${c.kind}", "prio=${c.minPriority}", "lines=${c.lines}", "title=${clean(c.title)}"
        ).joinToString(SEP)

        fun decode(raw: String?): WidgetConfig {
            if (raw.isNullOrBlank()) return WidgetConfig()
            val m = HashMap<String, String>()
            for (part in raw.split(SEP)) {
                val i = part.indexOf(EQ)
                if (i > 0) m[part.substring(0, i)] = part.substring(i + 1)
            }
            val d = WidgetConfig()
            return WidgetConfig(
                style = ProgressStyle.from(m["style"]),
                // 場所は設定で増やせるので、空でなければそのまま受ける（消えた場所は表示時に家へ寄せる）
                loc = m["loc"]?.takeIf { it.isNotBlank() } ?: d.loc,
                minutes = m["min"]?.toIntOrNull()?.coerceIn(0, 600) ?: d.minutes,
                minMinutes = m["minLo"]?.toIntOrNull()?.coerceIn(0, 600) ?: d.minMinutes,
                tags = (m["tags"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                kind = m["kind"]?.takeIf { it in setOf("hobby", "material", "both") } ?: d.kind,
                minPriority = m["prio"]?.toIntOrNull()?.coerceIn(0, 10) ?: d.minPriority,
                lines = m["lines"]?.toIntOrNull()?.coerceIn(0, 6) ?: d.lines,
                title = m["title"] ?: ""
            )
        }

        /** 区切りの文字を値に入れない */
        private fun clean(s: String): String = s.replace(SEP, " ").replace(EQ, " ").replace(",", " ").trim()
    }
}

/** ウィジェットごとの設定の置き場 */
object WidgetPrefs {
    private const val FILE = "widget_config"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(ctx: Context, widgetId: Int): WidgetConfig = WidgetConfig.decode(sp(ctx).getString(key(widgetId), null))

    fun save(ctx: Context, widgetId: Int, cfg: WidgetConfig) {
        sp(ctx).edit().putString(key(widgetId), WidgetConfig.encode(cfg)).apply()
    }

    fun delete(ctx: Context, widgetId: Int) {
        sp(ctx).edit().remove(key(widgetId)).apply()
    }

    /** ホーム画面に置いてあるウィジェット */
    fun placedIds(ctx: Context): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, SuggestWidgetProvider::class.java))

    private fun key(widgetId: Int) = "w$widgetId"
}

/** 候補をウィジェットの条件で絞る */
object WidgetFilter {
    fun apply(items: List<SuggestItem>, cfg: WidgetConfig, limit: Int): List<SuggestItem> {
        if (limit <= 0) return emptyList()
        return items.asSequence()
            .filter { cfg.kind == "both" || it.kind == cfg.kind }
            .filter { cfg.minPriority <= 0 || it.priority >= cfg.minPriority }
            .filter { cfg.minMinutes <= 0 || it.minutes >= cfg.minMinutes }
            .take(limit)
            .toList()
    }
}
