package dev.togar.dynasched.data

import android.content.Context
import dev.togar.dynasched.Prefs
import org.json.JSONObject

/**
 * タスクの追加画面の初期値。場所・優先度・色・タグ・必要時間。
 *
 * 以前は、子を足す時に**グループ自身の設定**を写していた。グループの場所が「家」のままでも、
 * その下に「実家」で足し続けていると、次も「家」で始まってしまう。
 * いまは**その親に前回足した時の設定**を覚えて、それを初期値にする。
 * 覚えが無ければ親の設定、それも無ければ既定。
 */
data class AddDefaults(
    val location: String,
    val priority: Int,
    val color: String,
    val tags: String,
    val durationMinutes: Int
) {
    /** 決めた項目だけ差し替える（null はそのまま） */
    fun with(location: String?, priority: Int?, color: String?, tags: String?): AddDefaults = copy(
        location = location ?: this.location,
        priority = priority ?: this.priority,
        color = color ?: this.color,
        tags = tags ?: this.tags
    )

    fun toJson(): JSONObject = JSONObject()
        .put("location", location)
        .put("priority", priority)
        .put("color", color)
        .put("tags", tags)
        .put("duration", durationMinutes)

    companion object {
        /** 最上位へ足した時のキー */
        const val TOP = "top"

        fun key(parentId: Long?): String = parentId?.toString() ?: TOP

        fun fromJson(o: JSONObject): AddDefaults = AddDefaults(
            location = o.optString("location", "anywhere"),
            priority = o.optInt("priority", 5),
            color = o.optString("color", ""),
            tags = o.optString("tags", ""),
            durationMinutes = o.optInt("duration", 30)
        )

        /** 前回足した時の設定。その親の分が無ければ null */
        fun remembered(ctx: Context, parentId: Long?): AddDefaults? =
            read(Prefs.lastAddJson(ctx)).optJSONObject(key(parentId))?.let(::fromJson)

        /** 足し終えた時に、その親の前回の設定として覚える */
        fun remember(ctx: Context, parentId: Long?, d: AddDefaults) {
            val all = read(Prefs.lastAddJson(ctx))
            all.put(key(parentId), d.toJson())
            Prefs.setLastAddJson(ctx, all.toString())
        }

        /**
         * グループを直して「配下にも適用」した時。前回の設定にも同じ値を入れる。
         * 入れないと、配下は直したのに、次に足す1件だけ古い値で始まる。
         * 覚えが無い親は触らない（その時は親の設定を写すので、もう新しい値になっている）
         */
        fun overrideFor(
            ctx: Context, parentIds: Collection<Long>,
            location: String?, priority: Int?, color: String?, tags: String?
        ) {
            val all = read(Prefs.lastAddJson(ctx))
            var changed = false
            for (id in parentIds) {
                val cur = all.optJSONObject(key(id))?.let(::fromJson) ?: continue
                all.put(key(id), cur.with(location, priority, color, tags).toJson())
                changed = true
            }
            if (changed) Prefs.setLastAddJson(ctx, all.toString())
        }

        /** 壊れていれば空から始める。初期値が出ないだけで、追加は止めない */
        internal fun read(raw: String): JSONObject = try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }
}
