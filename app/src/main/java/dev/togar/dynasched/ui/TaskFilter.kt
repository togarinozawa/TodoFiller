package dev.togar.dynasched.ui

import dev.togar.dynasched.api.HobbyItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * タスク一覧の絞り込み。検索欄と条件チップの中身。
 *
 * 以前は「横に並べるタブ」で1種類ずつしか切れなかった（場所のタブを開くとタグで切れない）。
 * **種類をまたいで掛け合わせる**のが目的なので、条件どうしは「かつ」、
 * 1つの条件の中で複数選んだら「または」にする（実家のみ × 優先度 高・中 × #英語）。
 *
 * 見るのは**葉だけ**。親は並びを保つために残す（前の「場所ごと」のタブと同じ考え）。
 * 親の場所やタグは配下と食い違っていることがあるので当てにしない。
 *
 * 同じ形を「配置の対象」にも使う（[dev.togar.dynasched.Prefs.placeFilter]）。
 */
data class TaskFilter(
    /** 名前とメモの部分一致。親の名前に当たれば配下ぜんぶ */
    val text: String = "",
    val habit: Habit = Habit.ALL,
    /** 場所のID。どこでも は [dev.togar.dynasched.Places.ANYWHERE] */
    val places: Set<String> = emptySet(),
    /** 3/5/8 */
    val priorities: Set<Int> = emptySet(),
    val tags: Set<String> = emptySet(),
    /** この塊の中だけ。塊自身は出さない（見出しと同じ名前を繰り返さない） */
    val groupId: Long? = null
) {
    enum class Habit(val label: String) {
        ALL("すべて"),
        ONLY("習慣だけ"),
        EXCEPT("習慣以外");

        companion object {
            fun from(name: String?): Habit = entries.firstOrNull { it.name == name } ?: ALL
        }
    }

    val isEmpty: Boolean
        get() = text.isBlank() && habit == Habit.ALL && places.isEmpty() &&
            priorities.isEmpty() && tags.isEmpty() && groupId == null

    /** 葉そのものが条件に合うか。[ancestors] は親から順でなくてよい（名前を見るだけ） */
    fun matchesLeaf(item: HobbyItem, ancestors: List<HobbyItem> = emptyList()): Boolean {
        when (habit) {
            Habit.ONLY -> if (item.routineId <= 0) return false
            Habit.EXCEPT -> if (item.routineId > 0) return false
            Habit.ALL -> Unit
        }
        if (places.isNotEmpty() &&
            item.location.ifEmpty { dev.togar.dynasched.Places.ANYWHERE } !in places) return false
        if (priorities.isNotEmpty() && priorityBand(item.priority) !in priorities) return false
        if (tags.isNotEmpty() && !Tags.hasAny(item, tags)) return false
        val q = text.trim()
        if (q.isNotEmpty()) {
            val hit = contains(item.name, q) || contains(item.note, q) ||
                ancestors.any { contains(it.name, q) }
            if (!hit) return false
        }
        return true
    }

    /** 一覧に出すもの。当てはまる葉と、その祖先 */
    fun apply(all: List<HobbyItem>): List<HobbyItem> {
        if (isEmpty) return all
        val scope = groupId?.let { id ->
            if (all.none { it.id == id }) all else TaskList.descendantsOf(all, id)
        } ?: all
        val byId = all.associateBy { it.id }
        val parents = all.mapNotNullTo(HashSet()) { it.parentId }
        fun ancestorsOf(i: HobbyItem): List<HobbyItem> {
            val out = ArrayList<HobbyItem>()
            val seen = HashSet<Long>()
            var p = i.parentId
            while (p != null && seen.add(p)) {
                val x = byId[p] ?: break
                out.add(x); p = x.parentId
            }
            return out
        }
        val scopeIds = scope.mapTo(HashSet()) { it.id }
        val keep = HashSet<Long>()
        for (i in scope) {
            if (i.id in parents) continue
            val anc = ancestorsOf(i)
            if (!matchesLeaf(i, anc)) continue
            keep.add(i.id)
            for (a in anc) if (a.id in scopeIds) keep.add(a.id)
        }
        return scope.filter { it.id in keep }
    }

    /** 配置に回してよい葉のID。[all] は完了も含んだ一覧でよい */
    fun leafIds(all: List<HobbyItem>): Set<Long> {
        val parents = all.mapNotNullTo(HashSet()) { it.parentId }
        return apply(all).filter { it.id !in parents }.mapTo(HashSet()) { it.id }
    }

    fun encode(): String = JSONObject().apply {
        put("text", text)
        put("habit", habit.name)
        put("places", JSONArray(places.sorted()))
        put("priorities", JSONArray(priorities.sorted()))
        put("tags", JSONArray(tags.sorted()))
        if (groupId != null) put("group", groupId)
    }.toString()

    /** チップに出す短い説明。条件ごとに1つ */
    fun summary(placeName: (String) -> String, groupName: (Long) -> String?): List<String> {
        val out = ArrayList<String>()
        if (text.isNotBlank()) out.add("「${text.trim()}」")
        if (habit != Habit.ALL) out.add(habit.label)
        groupId?.let { id -> groupName(id)?.let { out.add(it) } }
        if (places.isNotEmpty()) out.add(places.joinToString("・") { placeName(it) })
        if (priorities.isNotEmpty()) out.add("優先度 " + priorities.sortedDescending().joinToString("・") { priorityLabel(it) })
        if (tags.isNotEmpty()) out.add(tags.sorted().joinToString(" ") { "#$it" })
        return out
    }

    companion object {
        val PRIORITIES = listOf(8, 5, 3)

        /** 優先度は 3/5/8 の3段。古い行や手で直した値もどこかの段に入れる */
        fun priorityBand(p: Int): Int = when {
            p >= 7 -> 8
            p <= 4 -> 3
            else -> 5
        }

        fun priorityLabel(band: Int): String = when (band) {
            8 -> "高"; 3 -> "低"; else -> "中"
        }

        fun decode(raw: String?): TaskFilter {
            if (raw.isNullOrBlank()) return TaskFilter()
            return try {
                val o = JSONObject(raw)
                fun strs(k: String): Set<String> {
                    val a = o.optJSONArray(k) ?: return emptySet()
                    return (0 until a.length()).mapNotNullTo(HashSet()) { a.optString(it).ifEmpty { null } }
                }
                fun ints(k: String): Set<Int> {
                    val a = o.optJSONArray(k) ?: return emptySet()
                    return (0 until a.length()).mapTo(HashSet()) { a.optInt(it) }
                }
                TaskFilter(
                    text = o.optString("text", ""),
                    habit = Habit.from(o.optString("habit")),
                    places = strs("places"),
                    priorities = ints("priorities"),
                    tags = strs("tags"),
                    groupId = if (o.has("group")) o.optLong("group") else null
                )
            } catch (e: Exception) {
                // 壊れた控えで一覧が出なくなるより、絞り込みを忘れる方がまし
                TaskFilter()
            }
        }

        private fun contains(s: String, q: String) = s.contains(q, ignoreCase = true)
    }
}
