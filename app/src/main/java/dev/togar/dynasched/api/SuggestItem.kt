package dev.togar.dynasched.api

import org.json.JSONObject

/** GET /suggest が返すタスク候補1件分（ウィジェット用） */
data class SuggestItem(
    val kind: String,      // "hobby" | "material"
    val id: Long,
    val title: String,
    val minutes: Int,
    val priority: Int,
    val problems: Int,     // material のとき、その枠でやる問数
    /** 締切まで何日か（教材だけ）。「なぜこれか」に出す */
    val dueDays: Int? = null,
    /** やる日を今日の分として決めた強さ（2 絶対 / 1 なるべく / 0） */
    val wantToday: Int = 0
) {
    companion object {
        fun from(o: JSONObject): SuggestItem = SuggestItem(
            kind = o.optString("kind", "hobby"),
            id = o.optLong("id"),
            title = o.optString("title", "(無題)"),
            minutes = o.optInt("minutes", 30),
            priority = o.optInt("priority", 5),
            problems = o.optInt("problems", 0)
        )
    }
}
