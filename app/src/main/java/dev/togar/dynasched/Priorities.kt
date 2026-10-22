package dev.togar.dynasched

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 優先度の1段。[value] がタスクに入る数字（大きいほど先）。[memo] は目安（「すぐやれ」など） */
data class PriorityLevel(val value: Int, val name: String, val memo: String = "") {
    /** 選ぶ所に出す形。目安があれば添える */
    val label: String get() = if (memo.isBlank()) name else "$name（$memo）"
}

/**
 * タスクの優先度の段。以前は 低3/中5/高8 の決め打ちだった。
 *
 * **タスクが持つのは数字のまま。**段の名前と目安はここで付ける。Notion・控え・配置が
 * どれも数字で持っているので、名前を変えてもタスク側は書き換えずに済む。
 *
 * 段を足したり消したりして数字が足りなくなった時だけ、全部を振り直す
 * （[plan] が古い数字 → 新しい数字の対応を返し、LocalRepo が書き換える）。
 * 数字は 1〜9。ウィジェットの設定と Notion が 0〜10 を前提にしている。
 *
 * 教材の優先度は別物（低中高のまま）。締切との兼ね合いの計算に使っているので混ぜない。
 */
object Priorities {

    const val MIN = 1
    const val MAX = 9
    const val MAX_LEVELS = 9

    val DEFAULT = listOf(PriorityLevel(8, "高"), PriorityLevel(5, "中"), PriorityLevel(3, "低"))

    private const val KEY = "priority_levels"
    private const val PREFS_FILE = "dynasched_prefs"

    // ---- 端末への保存 ----

    /** 上（先にやる）から順 */
    fun all(ctx: Context): List<PriorityLevel> =
        parse(ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(ctx: Context, levels: List<PriorityLevel>) {
        ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY, toJson(normalize(levels)).toString()).apply()
    }

    // ---- 純粋な処理（テストで固定する） ----

    fun parse(json: String?): List<PriorityLevel> {
        if (json.isNullOrBlank()) return DEFAULT
        return try {
            val arr = JSONArray(json)
            val out = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name", "").trim()
                if (name.isEmpty()) null
                else PriorityLevel(o.optInt("value", 5), name, o.optString("memo", "").trim())
            }
            normalize(out).ifEmpty { DEFAULT }
        } catch (e: Exception) {
            DEFAULT
        }
    }

    fun toJson(levels: List<PriorityLevel>): JSONArray = JSONArray().apply {
        for (l in levels) put(JSONObject().put("value", l.value).put("name", l.name).put("memo", l.memo))
    }

    /** 上から順に並べ、数字の重複と範囲外を落とす */
    fun normalize(levels: List<PriorityLevel>): List<PriorityLevel> {
        val seen = HashSet<Int>()
        return levels.filter { it.value in MIN..MAX && seen.add(it.value) }.sortedByDescending { it.value }
    }

    /**
     * その数字がどの段か。段の数字ちょうどでなくても（古い行・Notionで手で入れた値）
     * 一番近い段に寄せる。同じ近さなら上の段（取りこぼして後回しにするより安全）
     */
    fun levelOf(levels: List<PriorityLevel>, p: Int): PriorityLevel =
        levels.minWithOrNull(compareBy<PriorityLevel> { Math.abs(it.value - p) }.thenByDescending { it.value })
            ?: DEFAULT[1]

    /** 上から何段目か（0 が一番上） */
    fun rankOf(levels: List<PriorityLevel>, p: Int): Int = levels.indexOf(levelOf(levels, p)).coerceAtLeast(0)

    fun name(levels: List<PriorityLevel>, p: Int): String = levelOf(levels, p).name

    /**
     * 段を足す・消した後の数字の決め方。
     *
     * [edited] は上から順の新しい段（数字は仮で、0 なら新しく足した段）。
     * 数字は**できるだけ今のまま**にする。入る隙間が無い時だけ 1〜9 に均等に振り直す。
     * 返すのは (確定した段, 古い数字 → 新しい数字)。消した段は [movedTo] に入れた段へ移す。
     */
    fun plan(
        old: List<PriorityLevel>, edited: List<PriorityLevel>, movedTo: Map<Int, Int> = emptyMap()
    ): Pair<List<PriorityLevel>, Map<Int, Int>> {
        val keepsOrder = edited.zipWithNext().all { (a, b) -> a.value == 0 || b.value == 0 || a.value > b.value }
        var levels = if (keepsOrder) fillGaps(edited) else null
        if (levels == null) {
            // 振り直す。上から順に 9 → 1 を均等に
            val n = edited.size
            levels = edited.mapIndexed { i, l ->
                val v = if (n == 1) 5 else Math.round(MAX - i * (MAX - MIN).toDouble() / (n - 1)).toInt()
                l.copy(value = v)
            }
        }
        // 古い数字 → 新しい数字。残った段は位置で、消した段は移し先で決まる
        val map = HashMap<Int, Int>()
        for ((i, e) in edited.withIndex()) if (e.value != 0) map[e.value] = levels[i].value
        for ((gone, to) in movedTo) {
            val idx = edited.indexOfFirst { it.value == to }
            if (idx >= 0) map[gone] = levels[idx].value
        }
        // 対応の無い古い段（呼び出し側が移し先を渡さなかった）は、新しい段の一番近いものへ
        for (o in old) if (o.value !in map) map[o.value] = levelOf(levels, o.value).value
        return levels to map
    }

    /** 0（新しい段）に、上下の間の数字を入れる。入らなければ null */
    private fun fillGaps(edited: List<PriorityLevel>): List<PriorityLevel>? {
        val out = edited.toMutableList()
        for (i in out.indices) {
            if (out[i].value != 0) continue
            val above = out.subList(0, i).lastOrNull { it.value != 0 }?.value ?: (MAX + 1)
            val below = out.subList(i + 1, out.size).firstOrNull { it.value != 0 }?.value ?: (MIN - 1)
            // 続けて足した段がまだ0のまま下にある時は、その分の隙間も残す
            val pending = out.subList(i + 1, out.size).takeWhile { it.value == 0 }.size
            val room = above - below - 1
            if (room < pending + 1) return null
            out[i] = out[i].copy(value = above - Math.max(1, room / (pending + 2)))
        }
        return out
    }

    /** 古い数字 → 新しい数字の対応で、任意の数字を新しくする（段の間の値は近い段へ寄せてから） */
    fun remap(old: List<PriorityLevel>, map: Map<Int, Int>, p: Int): Int =
        map[levelOf(old, p).value] ?: p
}
