package dev.togar.dynasched.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NotionのJSONの読み書き。
 *
 * **利用者はNotion側で列を消せるし型も変えられる。**そのたびに同期全体が
 * 落ちると手が付けられなくなるので、欠けていても既定値で通ることを固定する。
 * 書く側は、空の値がちゃんと「消す」になるかを見る（キーを省くと「変えない」に
 * なり、一度入れた予定が二度と消えない）。
 */
class NotionMapTest {

    private fun title(text: String) = JSONObject().put(
        "title", JSONArray().put(JSONObject().put("plain_text", text))
    )

    private fun page(props: JSONObject, id: String = "p1"): JSONObject =
        JSONObject().put("id", id).put("properties", props)

    // ---- 読む ----

    @Test
    fun ひととおり読める() {
        val props = JSONObject()
            .put(NotionMap.NAME, title("英単語"))
            .put(NotionMap.MINUTES, JSONObject().put("number", 45))
            .put(NotionMap.PRIORITY, JSONObject().put("number", 8))
            .put(NotionMap.PLACE, JSONObject().put("select", JSONObject().put("name", "家のみ")))
            .put(NotionMap.DONE, JSONObject().put("checkbox", true))
            .put(NotionMap.ORDER, JSONObject().put("number", 3))
            .put(NotionMap.TAGS, JSONObject().put("multi_select", JSONArray()
                .put(JSONObject().put("name", "語学"))
                .put(JSONObject().put("name", "毎日"))))
            .put(NotionMap.NOTE, JSONObject().put("rich_text",
                JSONArray().put(JSONObject().put("plain_text", "章末から"))))
            .put(NotionMap.PARENT, JSONObject().put("relation",
                JSONArray().put(JSONObject().put("id", "oya"))))

        val t = NotionMap.readPage(page(props))!!
        assertEquals("英単語", t.name)
        assertEquals(45, t.durationMinutes)
        assertEquals(8, t.priority)
        assertEquals("home", t.location)
        assertEquals("語学,毎日", t.tags)
        assertEquals("章末から", t.note)
        assertEquals(3, t.sortOrder)
        assertEquals("oya", t.parentPageId)
        assertTrue(t.completed)
    }

    @Test
    fun 列が無くても既定値で通る() {
        // Notion側で列を消された時。ここで落ちると同期が二度と通らなくなる
        val t = NotionMap.readPage(page(JSONObject()))!!
        assertEquals("", t.name)
        assertEquals(30, t.durationMinutes)
        assertEquals(5, t.priority)
        assertEquals("anywhere", t.location)
        assertEquals("", t.tags)
        assertNull(t.parentPageId)
        assertFalse(t.completed)
    }

    @Test
    fun ページIDが無ければ読まない() {
        assertNull(NotionMap.readPage(JSONObject().put("properties", JSONObject())))
    }

    @Test
    fun 数値が空なら既定値() {
        val props = JSONObject().put(NotionMap.MINUTES, JSONObject().put("number", JSONObject.NULL))
        assertEquals(30, NotionMap.readPage(page(props))!!.durationMinutes)
    }

    @Test
    fun 親が複数付いていたら先頭を使う() {
        // Notionのリレーションは複数付けられてしまう。木にするには1つに決める
        val props = JSONObject().put(NotionMap.PARENT, JSONObject().put("relation", JSONArray()
            .put(JSONObject().put("id", "a")).put(JSONObject().put("id", "b"))))
        assertEquals("a", NotionMap.readPage(page(props))!!.parentPageId)
    }

    @Test
    fun ゴミ箱に入ったページを見分ける() {
        val trashed = JSONObject().put("id", "p1").put("in_trash", true)
            .put("properties", JSONObject())
        assertTrue(NotionMap.readPage(trashed)!!.archived)
        // 古い呼び方でも拾う
        val old = JSONObject().put("id", "p1").put("archived", true)
            .put("properties", JSONObject())
        assertTrue(NotionMap.readPage(old)!!.archived)
    }

    @Test
    fun 長い本文も繋いで読む() {
        val arr = JSONArray()
            .put(JSONObject().put("plain_text", "前半"))
            .put(JSONObject().put("plain_text", "後半"))
        val props = JSONObject().put(NotionMap.NOTE, JSONObject().put("rich_text", arr))
        assertEquals("前半後半", NotionMap.readPage(page(props))!!.note)
    }

    // ---- 書く ----

    @Test
    fun 空の日付はnullとして送る() {
        // キーごと省くと「変えない」になり、一度入れた予定を消せなくなる
        val o = NotionMap.dateOrNull(null)
        assertTrue(o.has("date"))
        assertEquals(JSONObject.NULL, o.get("date"))
    }

    @Test
    fun 端末の日時をNotionの形に直す() {
        assertEquals("2026-09-12T05:00:00", NotionMap.toIso("2026-09-12 05:00:00"))
        // 既にオフセット付きなら壊さない
        assertEquals("2026-09-12T05:00:00+09:00", NotionMap.toIso("2026-09-12T05:00:00+09:00"))
        assertNull(NotionMap.toIso(""))
        assertNull(NotionMap.toIso(null))
    }

    @Test
    fun 空の本文は空配列で送る() {
        // 空文字の要素を入れるとNotionに弾かれる
        assertEquals(0, NotionMap.richText("").length())
        assertEquals(1, NotionMap.richText("ひとこと").length())
    }

    @Test
    fun 長い本文は2000文字ずつに割る() {
        val arr = NotionMap.richText("あ".repeat(4500))
        assertEquals(3, arr.length())
        assertEquals(2000, arr.getJSONObject(0).getJSONObject("text").getString("content").length)
        assertEquals(500, arr.getJSONObject(2).getJSONObject("text").getString("content").length)
    }

    @Test
    fun タグの空白とカンマ入りを落とす() {
        val arr = NotionMap.tagObjects(" 語学 ,, 毎日,")
        assertEquals(2, arr.length())
        assertEquals("語学", arr.getJSONObject(0).getString("name"))
    }

    @Test
    fun 親が無ければリレーションを空にする() {
        assertEquals(0, NotionMap.relationArray(null).length())
        assertEquals(0, NotionMap.relationArray("").length())
        assertEquals(1, NotionMap.relationArray("oya").length())
    }

    @Test
    fun 書き戻しは実行の記録だけ() {
        // ここに名前や親が混ざると、Notionで整理した内容を端末が踏み潰す
        val o = NotionMap.writeStatus(LocalTask(id = 1, name = "端末の名前", completed = true))
        assertTrue(o.has(NotionMap.DONE))
        assertTrue(o.has(NotionMap.NEXT))
        assertFalse(o.has(NotionMap.NAME))
        assertFalse(o.has(NotionMap.PARENT))
    }

    @Test
    fun 色は名前で置いて読み戻す() {
        val props = JSONObject().put(NotionMap.COLOR,
            JSONObject().put("select", JSONObject().put("name", "トマト")))
        assertEquals("11", NotionMap.readPage(page(props))!!.color)
        // Notionで選択肢を増やされた時。知らない名前は既定に寄せる
        val odd = JSONObject().put(NotionMap.COLOR,
            JSONObject().put("select", JSONObject().put("name", "蛍光ピンク")))
        assertEquals("", NotionMap.readPage(page(odd))!!.color)
    }

    @Test
    fun 色が空なら選択なしとして送る() {
        // キーごと省くと「変えない」になり、一度付けた色を外せなくなる
        val o = NotionMap.selectOrNull("")
        assertTrue(o.has("select"))
        assertEquals(JSONObject.NULL, o.get("select"))
        assertEquals("トマト", NotionMap.colorLabel("11"))
        assertEquals("", NotionMap.colorLabel(""))
    }

    @Test
    fun 色の書き出しが送る形に入っている() {
        val props = NotionMap.writeProperties(LocalTask(id = 1, color = "5"), null)
        assertEquals("バナナ",
            props.getJSONObject(NotionMap.COLOR).getJSONObject("select").getString("name"))
    }

    @Test
    fun 場所は日本語の選択肢と行き来する() {
        assertEquals("どこでも", NotionMap.placeLabel("anywhere"))
        assertEquals("out", NotionMap.placeValue("外のみ"))
        // 知らない値は「どこでも」に寄せる（Notionで選択肢を足された時）
        assertEquals("anywhere", NotionMap.placeValue("宇宙"))
    }

    @Test
    fun 作る時の列に親タスクを入れない() {
        // 自分自身を指すので、データソースが出来るまで相手のIDが決まらない
        val schema = NotionMap.initialSchema()
        assertTrue(schema.has(NotionMap.NAME))
        assertTrue(schema.has(NotionMap.DONE))
        assertTrue(schema.has(NotionMap.COLOR))
        assertFalse(schema.has(NotionMap.PARENT))
    }

    @Test
    fun 親タスクは後から自分自身を指して足す() {
        val patch = NotionMap.parentRelationPatch("ds1")
        val rel = patch.getJSONObject("properties")
            .getJSONObject(NotionMap.PARENT).getJSONObject("relation")
        assertEquals("ds1", rel.getString("data_source_id"))
        assertNotNull(rel.getString("type"))
    }

    @Test
    fun 書いた物を読み直すと元に戻る() {
        val t = LocalTask(
            id = 1, name = "買い物", durationMinutes = 20, priority = 3,
            location = "out", note = "牛乳", tags = "家事", sortOrder = 7, completed = false
        )
        val props = NotionMap.writeProperties(t, null)
        // writeProperties は送る形、readPage は返る形。plain_text に読み替えて確かめる
        val readable = JSONObject()
            .put(NotionMap.NAME, title(t.name))
            .put(NotionMap.MINUTES, props.getJSONObject(NotionMap.MINUTES))
            .put(NotionMap.PRIORITY, props.getJSONObject(NotionMap.PRIORITY))
            .put(NotionMap.ORDER, props.getJSONObject(NotionMap.ORDER))
            .put(NotionMap.PLACE, props.getJSONObject(NotionMap.PLACE))
            .put(NotionMap.TAGS, props.getJSONObject(NotionMap.TAGS))
            .put(NotionMap.DONE, props.getJSONObject(NotionMap.DONE))
        val back = NotionMap.readPage(page(readable))!!
        assertEquals(t.name, back.name)
        assertEquals(t.durationMinutes, back.durationMinutes)
        assertEquals(t.priority, back.priority)
        assertEquals(t.location, back.location)
        assertEquals(t.tags, back.tags)
        assertEquals(t.sortOrder, back.sortOrder)
    }
}
