package dev.togar.dynasched.api

import org.json.JSONObject

/**
 * 単発タスク（hobby_tasks）。parent_id で階層（グループ化）を表す。
 * level は画面表示用のインデント段数（サーバーには無い値）。
 */
data class HobbyItem(
    val id: Long,
    val name: String,
    val parentId: Long?,      // null なら最上位（グループ/親）
    val isCompleted: Boolean,
    val durationMinutes: Int = 30,   // 必要時間（分）
    val location: String = "anywhere", // 場所: anywhere / home / out
    val note: String = "",           // 詳細メモ（自由記述）
    val priority: Int = 5,           // 優先度（3/5/8）
    val color: String = "",          // カレンダー色 colorId（"" は既定）
    /** 手動で並び替えたときの順番。同じ親を持つもの同士でしか比べない */
    val sortOrder: Int = 0,
    /** タグ。カンマ区切りで1列に持つ（[dev.togar.dynasched.ui.Tags] が読み書きする） */
    val tags: String = "",
    var level: Int = 0,
    var hasChildren: Boolean = false  // 子を持つタスクはチェックボックス非表示（葉のみ完了可能）
) {
    /** 場所の日本語ラベル。場所は設定で増やせるので一覧を渡す */
    fun locationLabel(places: List<dev.togar.dynasched.Place>): String =
        dev.togar.dynasched.Places.taskLabel(places, location)

    companion object {
        fun from(o: JSONObject): HobbyItem {
            val parent = if (o.isNull("parent_id")) null else o.optLong("parent_id")
            return HobbyItem(
                id = o.optLong("id"),
                name = o.optString("name", ""),
                parentId = parent,
                isCompleted = o.optInt("is_completed", 0) == 1,
                durationMinutes = o.optInt("duration_minutes", 30),
                location = if (o.isNull("location")) "anywhere" else o.optString("location", "anywhere"),
                note = if (o.isNull("note")) "" else o.optString("note", ""),
                priority = o.optInt("priority", 5),
                color = if (o.isNull("color")) "" else o.optString("color", "")
            )
        }
    }
}
