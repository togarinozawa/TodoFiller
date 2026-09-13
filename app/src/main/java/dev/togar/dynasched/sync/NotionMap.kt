package dev.togar.dynasched.sync

import dev.togar.dynasched.api.CalColor
import org.json.JSONArray
import org.json.JSONObject

/**
 * NotionのJSONと [NotionTask] の間の変換。**通信はしない。**
 *
 * Notionのプロパティは種類ごとに入れ物の形が違う（`title` は配列、`number` は素の値、
 * `select` は名前を持つ物、`relation` はページIDの配列）。しかも**利用者が
 * Notion上で列を消したり型を変えたりできる**ので、欠けていても落ちない読み方に
 * してある。落ちる代わりに既定値へ寄せる。
 *
 * 列の名前を日本語にしてあるのは、Notion側で見るのが本人だから。
 */
object NotionMap {

    // 列の名前。**変えるとNotion側の列と対応が切れる**ので、変えるなら移行が要る
    const val NAME = "名前"
    const val PARENT = "親タスク"
    const val MINUTES = "所要時間"
    const val PRIORITY = "優先度"
    const val PLACE = "場所"
    const val TAGS = "タグ"
    const val NOTE = "メモ"
    const val DONE = "完了"
    const val DONE_AT = "完了日時"
    const val NEXT = "次の予定"
    const val ORDER = "並び順"
    const val COLOR = "色"

    /** 場所の内部値 ↔ Notionの選択肢 */
    private val PLACES = listOf(
        "anywhere" to "どこでも",
        "home" to "家のみ",
        "out" to "外のみ"
    )

    fun placeLabel(value: String): String =
        PLACES.firstOrNull { it.first == value }?.second ?: "どこでも"

    fun placeValue(label: String): String =
        PLACES.firstOrNull { it.second == label }?.first ?: "anywhere"

    /**
     * カレンダー色。端末は colorId（"" は既定）で持ち、Notionには見て分かる名前で置く。
     * **空は「選択なし」にする。**「既定」という選択肢を作ると、
     * 未設定と既定色の区別が付いていないことが見た目に出てしまう。
     */
    fun colorLabel(id: String): String =
        if (id.isEmpty()) "" else CalColor.items.firstOrNull { it.id == id }?.name.orEmpty()

    fun colorValue(label: String): String =
        CalColor.items.firstOrNull { it.name == label && it.id.isNotEmpty() }?.id.orEmpty()

    /** CalColor の近似HEXに一番近いNotionの色名。選択肢に色を付けるためだけのもの */
    private val NOTION_COLOR = mapOf(
        "1" to "purple", "2" to "green", "3" to "purple", "4" to "pink",
        "5" to "yellow", "6" to "orange", "7" to "blue", "8" to "gray",
        "9" to "blue", "10" to "green", "11" to "red"
    )

    // ---- 読む ----

    /** 検索結果の1ページ分。読めない形なら null（1件の崩れで同期全体を止めない） */
    fun readPage(page: JSONObject): NotionTask? {
        val id = page.optString("id", "")
        if (id.isEmpty()) return null
        val p = page.optJSONObject("properties") ?: JSONObject()
        return NotionTask(
            pageId = id,
            parentPageId = firstRelation(p.optJSONObject(PARENT)),
            name = plainText(p.optJSONObject(NAME)),
            durationMinutes = number(p.optJSONObject(MINUTES), 30),
            priority = number(p.optJSONObject(PRIORITY), 5),
            location = placeValue(selectName(p.optJSONObject(PLACE))),
            note = plainText(p.optJSONObject(NOTE)),
            tags = multiSelect(p.optJSONObject(TAGS)).joinToString(","),
            sortOrder = number(p.optJSONObject(ORDER), 0),
            color = colorValue(selectName(p.optJSONObject(COLOR))),
            completed = p.optJSONObject(DONE)?.optBoolean("checkbox", false) ?: false,
            scheduledAt = dateStart(p.optJSONObject(NEXT)),
            // in_trash は2025-09-03版の呼び方。古い archived も見る
            archived = page.optBoolean("in_trash", false) || page.optBoolean("archived", false)
        )
    }

    /** `title` も `rich_text` も「plain_text を繋いだもの」として読む */
    internal fun plainText(prop: JSONObject?): String {
        val arr = prop?.optJSONArray("title") ?: prop?.optJSONArray("rich_text") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            sb.append(arr.optJSONObject(i)?.optString("plain_text", "") ?: "")
        }
        return sb.toString()
    }

    internal fun number(prop: JSONObject?, dflt: Int): Int {
        if (prop == null || prop.isNull("number")) return dflt
        return prop.optDouble("number", dflt.toDouble()).toInt()
    }

    internal fun selectName(prop: JSONObject?): String =
        prop?.optJSONObject("select")?.optString("name", "") ?: ""

    internal fun multiSelect(prop: JSONObject?): List<String> {
        val arr = prop?.optJSONArray("multi_select") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i)?.optString("name", "").orEmpty()
            if (n.isNotEmpty()) out.add(n)
        }
        return out
    }

    internal fun firstRelation(prop: JSONObject?): String? {
        val arr = prop?.optJSONArray("relation") ?: return null
        // 親は1つだけ。2つ以上付いていたら先頭を使う（Notionでは複数付けられてしまう）
        val id = arr.optJSONObject(0)?.optString("id", "").orEmpty()
        return id.ifEmpty { null }
    }

    internal fun dateStart(prop: JSONObject?): String? {
        val d = prop?.optJSONObject("date") ?: return null
        val s = d.optString("start", "")
        return s.ifEmpty { null }
    }

    // ---- 書く ----

    /**
     * 端末の行からNotionのプロパティを作る。
     *
     * [parentPageId] を別に取るのは、**新規作成の時点では親のページがまだ無い**
     * ことがあるため（作る順で決まる）。親は後から繋ぎ直す。
     */
    fun writeProperties(
        t: LocalTask,
        parentPageId: String?,
        includeParent: Boolean = true
    ): JSONObject {
        val p = JSONObject()
        p.put(NAME, JSONObject().put("title", richText(t.name)))
        p.put(MINUTES, JSONObject().put("number", t.durationMinutes))
        p.put(PRIORITY, JSONObject().put("number", t.priority))
        p.put(ORDER, JSONObject().put("number", t.sortOrder))
        p.put(PLACE, JSONObject().put("select", JSONObject().put("name", placeLabel(t.location))))
        p.put(COLOR, selectOrNull(colorLabel(t.color)))
        p.put(NOTE, JSONObject().put("rich_text", richText(t.note)))
        p.put(DONE, JSONObject().put("checkbox", t.completed))
        p.put(DONE_AT, dateOrNull(t.completedAt))
        p.put(NEXT, dateOrNull(t.scheduledAt))
        p.put(TAGS, JSONObject().put("multi_select", tagObjects(t.tags)))
        if (includeParent) p.put(PARENT, JSONObject().put("relation", relationArray(parentPageId)))
        return p
    }

    /** 実行の記録だけを書き戻す。Notionで整理した内容を踏まないため */
    fun writeStatus(t: LocalTask): JSONObject = JSONObject()
        .put(DONE, JSONObject().put("checkbox", t.completed))
        .put(DONE_AT, dateOrNull(t.completedAt))
        .put(NEXT, dateOrNull(t.scheduledAt))

    /** 親の繋ぎ直しだけ */
    fun writeParent(parentPageId: String?): JSONObject =
        JSONObject().put(PARENT, JSONObject().put("relation", relationArray(parentPageId)))

    internal fun richText(text: String): JSONArray {
        val arr = JSONArray()
        if (text.isEmpty()) return arr   // 空は空配列。空文字の要素を送ると弾かれる
        // Notionの1要素は2000文字まで。長いメモは分けて送る
        var i = 0
        while (i < text.length) {
            val end = minOf(i + 2000, text.length)
            arr.put(JSONObject().put("text", JSONObject().put("content", text.substring(i, end))))
            i = end
        }
        return arr
    }

    internal fun tagObjects(tags: String): JSONArray {
        val arr = JSONArray()
        for (raw in tags.split(",")) {
            val t = raw.trim()
            // Notionの選択肢にカンマは入れられない。入っていたら落とす
            if (t.isEmpty() || t.contains(",")) continue
            arr.put(JSONObject().put("name", t))
        }
        return arr
    }

    internal fun relationArray(parentPageId: String?): JSONArray {
        val arr = JSONArray()
        if (!parentPageId.isNullOrEmpty()) arr.put(JSONObject().put("id", parentPageId))
        return arr
    }

    /** 空なら選択なし。日付と同じで、キーごと省くと「変えない」になり消せなくなる */
    internal fun selectOrNull(name: String): JSONObject =
        if (name.isEmpty()) JSONObject().put("select", JSONObject.NULL)
        else JSONObject().put("select", JSONObject().put("name", name))

    /** 空なら `{"date": null}`。キーごと省くと「変えない」になり、消せなくなる */
    internal fun dateOrNull(value: String?): JSONObject {
        val iso = toIso(value) ?: return JSONObject().put("date", JSONObject.NULL)
        return JSONObject().put("date", JSONObject().put("start", iso))
    }

    /**
     * 端末の `yyyy-MM-dd HH:mm:ss` をNotionが受け取れる形にする。
     * 既にTが入っていればそのまま（オフセット付きを壊さない）。
     */
    internal fun toIso(value: String?): String? {
        val s = value?.trim().orEmpty()
        if (s.length < 10) return null
        if (s.contains('T')) return s
        return s.replace(' ', 'T')
    }

    // ---- データベースを作る ----

    /**
     * 列の定義。**親タスクのリレーションだけはここに入れられない。**
     * 自分自身を指すので、データソースが出来るまで相手のIDが決まらない。
     * 作った後に [parentRelationPatch] で足す。
     */
    fun initialSchema(): JSONObject = JSONObject()
        .put(NAME, JSONObject().put("title", JSONObject()))
        .put(MINUTES, JSONObject().put("number", JSONObject().put("format", "number")))
        .put(PRIORITY, JSONObject().put("number", JSONObject().put("format", "number")))
        .put(ORDER, JSONObject().put("number", JSONObject().put("format", "number")))
        .put(
            PLACE, JSONObject().put(
                "select", JSONObject().put(
                    "options", JSONArray().apply {
                        put(JSONObject().put("name", "どこでも").put("color", "default"))
                        put(JSONObject().put("name", "家のみ").put("color", "blue"))
                        put(JSONObject().put("name", "外のみ").put("color", "green"))
                    }
                )
            )
        )
        .put(TAGS, JSONObject().put("multi_select", JSONObject().put("options", JSONArray())))
        .put(
            COLOR, JSONObject().put(
                "select", JSONObject().put(
                    "options", JSONArray().apply {
                        for (c in CalColor.items) {
                            if (c.id.isEmpty()) continue
                            put(JSONObject().put("name", c.name)
                                .put("color", NOTION_COLOR[c.id] ?: "default"))
                        }
                    }
                )
            )
        )
        .put(NOTE, JSONObject().put("rich_text", JSONObject()))
        .put(DONE, JSONObject().put("checkbox", JSONObject()))
        .put(DONE_AT, JSONObject().put("date", JSONObject()))
        .put(NEXT, JSONObject().put("date", JSONObject()))

    /** 出来たデータソースへ、自分自身を指す「親タスク」を足す */
    fun parentRelationPatch(dataSourceId: String): JSONObject = JSONObject().put(
        "properties", JSONObject().put(
            PARENT, JSONObject().put(
                "relation", JSONObject()
                    .put("data_source_id", dataSourceId)
                    .put("type", "single_property")
                    .put("single_property", JSONObject())
            )
        )
    )
}
