package dev.togar.dynasched

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 作業できる場所。以前は「家」と「外」の2つに決め打ちだった。
 *
 * **タスクと枠が持つのは [id] で、名前ではない。**名前を変えても
 * タスクの「〇〇のみ」が外れないようにするため。家と外の id は昔からの
 * `home` / `out` のまま（既にDB・Notion・控えに入っている値なので変えない）。
 *
 * カレンダーでは、予定の題名の末尾に場所の名前を付けると、その場所の枠になる。
 * 家と外は名前を変えても、昔の印（`家` `&h` / `外` `&o`）を読み続ける。
 * 書いてあるカレンダーの予定を全部直させるわけにはいかないので。
 */
data class Place(val id: String, val name: String) {

    val builtin: Boolean get() = id == Places.HOME || id == Places.OUT

    /** カレンダーの題名の末尾で、この場所の印として読む文字列 */
    fun tags(): List<String> = buildList {
        add(name)
        when (id) {
            Places.HOME -> { add("家"); add("&h") }
            Places.OUT -> { add("外"); add("&o") }
        }
    }.distinct()

    /** タスクの場所として見せる時の言い方 */
    val onlyLabel: String get() = "${name}のみ"
}

object Places {
    const val ANYWHERE = "anywhere"
    const val HOME = "home"
    const val OUT = "out"
    const val ANYWHERE_LABEL = "どこでも"
    const val MAX_NAME = 10

    val DEFAULT = listOf(Place(HOME, "家"), Place(OUT, "外"))

    private const val KEY = "places"

    // ---- 端末への保存 ----

    /**
     * 登録されている場所。並びは画面に出す順。
     * 毎回 SharedPreferences から読む（数件なので、キャッシュの食い違いを抱えるより安い）。
     */
    fun all(ctx: Context): List<Place> =
        parse(ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(ctx: Context, places: List<Place>) {
        ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY, toJson(normalize(places)).toString()).apply()
    }

    /** Prefs と同じファイルに入れる（Prefs.FILE は private なので名前だけ揃える） */
    private const val PREFS_FILE = "dynasched_prefs"

    // ---- 純粋な処理（テストで固定する） ----

    fun parse(json: String?): List<Place> {
        if (json.isNullOrBlank()) return DEFAULT
        val out = ArrayList<Place>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                val name = o.optString("name", "").trim()
                if (id.isEmpty() || name.isEmpty()) continue
                out.add(Place(id, name))
            }
        } catch (e: Exception) {
            return DEFAULT
        }
        return normalize(out)
    }

    fun toJson(places: List<Place>): JSONArray = JSONArray().apply {
        for (p in places) put(JSONObject().put("id", p.id).put("name", p.name))
    }

    /**
     * 家と外は必ずある状態にする。**消えると昔のタスクや枠の行き先が無くなる。**
     * id の重複も落とす（先に出た方を残す）。
     */
    fun normalize(places: List<Place>): List<Place> {
        val seen = HashSet<String>()
        val out = places.filter { it.id != ANYWHERE && seen.add(it.id) }.toMutableList()
        for ((i, d) in DEFAULT.withIndex()) {
            if (out.none { it.id == d.id }) out.add(minOf(i, out.size), d)
        }
        return out
    }

    /** 場所の名前。知らない id（消した場所など）は「どこでも」に寄せて見せる */
    fun name(places: List<Place>, id: String?): String =
        places.firstOrNull { it.id == id }?.name ?: ANYWHERE_LABEL

    /** タスクの場所の言い方。「どこでも」「家のみ」「学校のみ」 */
    fun taskLabel(places: List<Place>, id: String?): String =
        places.firstOrNull { it.id == id }?.onlyLabel ?: ANYWHERE_LABEL

    /**
     * 教材の場所。v47までの教材は場所を持たず「必要なもの」から決めていた
     * （何も要らなければどこでも、机・声・PCが要れば家）。場所が空ならそれに従う。
     */
    fun materialPlace(location: String, needs: String): String =
        location.ifEmpty { if (needs == "none") ANYWHERE else HOME }

    /** タスクに選ばせる候補。先頭は「どこでも」 */
    fun taskChoices(places: List<Place>): List<Pair<String, String>> =
        listOf(ANYWHERE to ANYWHERE_LABEL) + places.map { it.id to it.onlyLabel }

    /**
     * タスクの場所ラベル（Notionの選択肢）から id へ。読めなければ null。
     * 「家のみ」「外のみ」は家と外の名前を変えた後も読む（Notion側に残っているため）。
     */
    fun idFromTaskLabel(places: List<Place>, label: String): String? {
        if (label == ANYWHERE_LABEL) return ANYWHERE
        places.firstOrNull { it.onlyLabel == label }?.let { return it.id }
        return when (label) {
            "家のみ" -> HOME
            "外のみ" -> OUT
            else -> null
        }
    }

    /**
     * 予定の題名の末尾から場所を読む。返すのは (場所のid, 印を外した題名)。
     *
     * **長い印から先に試す。**「実家」という場所を作った人の「帰省 実家」が
     * 「家」の枠として読まれないように。
     */
    fun matchSuffix(places: List<Place>, summary: String): Pair<String, String>? {
        val s = summary.trimEnd()
        val tags = places.flatMap { p -> p.tags().map { it to p.id } }
            .sortedByDescending { it.first.length }
        for ((tag, id) in tags) {
            if (s.endsWith(tag, ignoreCase = true)) {
                return id to s.dropLast(tag.length).trim()
            }
        }
        return null
    }

    /**
     * 名前として使えるか。駄目なら理由を返す。
     * @param editingId 名前を変えている場所の id（自分自身との重複は咎めない）
     */
    fun validateName(places: List<Place>, name: String, editingId: String?): String? {
        val n = name.trim()
        if (n.isEmpty()) return "名前を入れてください"
        if (n.length > MAX_NAME) return "名前は${MAX_NAME}文字までにしてください"
        if (n.endsWith("%")) return "末尾が「%」の名前は使えません（スキマスが作った予定の印です）"
        if (n == ANYWHERE_LABEL || n.endsWith("のみ")) return "「$n」は使えません"
        if (n.startsWith("テスト期間")) return "「テスト期間」で始まる名前は使えません"
        for (p in places) {
            if (p.id == editingId) continue
            if (p.tags().any { it.equals(n, ignoreCase = true) }) {
                return "「$n」は「${p.name}」の印として使われています"
            }
        }
        return null
    }

    /** 新しい場所の id。名前を変えても変わらないよう、名前からは作らない */
    fun newId(places: List<Place>, now: Long = System.currentTimeMillis()): String {
        var id = "p$now"
        var n = 0
        while (places.any { it.id == id }) id = "p$now-${++n}"
        return id
    }
}
