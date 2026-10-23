package dev.togar.dynasched.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 追加画面の前回の設定。グループごとに覚える。
 *
 * 形が変わると、覚えた設定が読めなくなって初期値が既定に戻る（黙って）。
 */
class AddDefaultsTest {

    private val jikka = AddDefaults("jikka", 8, "5", "英語 朝", 45)

    @Test
    fun `書いて読み戻すと同じ`() {
        assertEquals(jikka, AddDefaults.fromJson(jikka.toJson()))
    }

    @Test
    fun `親ごとに別の所に覚える。最上位は top`() {
        val map = JSONObject()
        map.put(AddDefaults.key(7L), jikka.toJson())
        map.put(AddDefaults.key(null), AddDefaults("home", 5, "", "", 30).toJson())
        assertEquals("7", AddDefaults.key(7L))
        assertEquals("top", AddDefaults.key(null))
        assertEquals(jikka, AddDefaults.fromJson(map.getJSONObject("7")))
        assertEquals("home", AddDefaults.fromJson(map.getJSONObject(AddDefaults.TOP)).location)
    }

    @Test
    fun `配下にも適用した項目だけ差し替え、ほかと時間はそのまま`() {
        // 場所と優先度だけ配った。色・タグ・必要時間は前回のまま
        val d = jikka.with(location = "home", priority = 3, color = null, tags = null)
        assertEquals(AddDefaults("home", 3, "5", "英語 朝", 45), d)
        assertEquals(jikka, jikka.with(null, null, null, null))
    }

    @Test
    fun `壊れた控えは空から始める`() {
        assertEquals(0, AddDefaults.read("{壊れ").length())
        assertEquals(0, AddDefaults.read("").length())
    }

    @Test
    fun `欠けた項目は既定で埋める`() {
        val d = AddDefaults.fromJson(JSONObject())
        assertEquals(AddDefaults("anywhere", 5, "", "", 30), d)
    }
}
