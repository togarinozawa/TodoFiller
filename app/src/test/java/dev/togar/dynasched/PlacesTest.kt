package dev.togar.dynasched

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 場所の一覧と、カレンダーの題名からの読み取り。
 * 家と外は昔のデータ（DB・Notion・控え・カレンダーの印）が指しているので、
 * 名前を変えても消しても読めなくならないことを固定する。
 */
class PlacesTest {

    private val school = Place("p1", "学校")
    private val withSchool = Places.DEFAULT + school

    @Test
    fun `保存が無ければ家と外`() {
        assertEquals(Places.DEFAULT, Places.parse(null))
        assertEquals(Places.DEFAULT, Places.parse("壊れた"))
    }

    @Test
    fun `書いて読むと元に戻る`() {
        assertEquals(withSchool, Places.parse(Places.toJson(withSchool).toString()))
    }

    @Test
    fun `家と外が抜けていたら足し戻す`() {
        val only = Places.parse(Places.toJson(listOf(school)).toString())
        assertEquals(listOf("home", "out", "p1"), only.map { it.id })
    }

    @Test
    fun `足した場所の名前が印になり、題名から外れる`() {
        assertEquals("p1" to "放課後", Places.matchSuffix(withSchool, "放課後 学校"))
        assertNull(Places.matchSuffix(Places.DEFAULT, "放課後 学校"))
    }

    @Test
    fun `家と外は名前を変えても昔の印を読む`() {
        val renamed = listOf(Place("home", "自宅"), Place("out", "外出先"))
        assertEquals("home" to "自習", Places.matchSuffix(renamed, "自習 自宅"))
        assertEquals("home" to "自習", Places.matchSuffix(renamed, "自習 家"))
        assertEquals("home" to "自習", Places.matchSuffix(renamed, "自習 &H"))
        assertEquals("out" to "電車", Places.matchSuffix(renamed, "電車 外"))
    }

    @Test
    fun `長い印から先に試すので実家は家にならない`() {
        val list = Places.DEFAULT + Place("p2", "実家")
        assertEquals("p2" to "帰省", Places.matchSuffix(list, "帰省 実家"))
        assertEquals("home" to "自習", Places.matchSuffix(list, "自習 家"))
    }

    @Test
    fun `タスクの場所の言い方`() {
        assertEquals("どこでも", Places.taskLabel(withSchool, "anywhere"))
        assertEquals("家のみ", Places.taskLabel(withSchool, "home"))
        assertEquals("学校のみ", Places.taskLabel(withSchool, "p1"))
        // 消した場所はどこでもとして見せる（実際に移すのは消した時）
        assertEquals("どこでも", Places.taskLabel(Places.DEFAULT, "p1"))
        assertEquals(listOf("anywhere", "home", "out", "p1"), Places.taskChoices(withSchool).map { it.first })
    }

    @Test
    fun `Notionの選択肢から戻す。家のみ外のみは名前を変えた後も読む`() {
        val renamed = listOf(Place("home", "自宅"), Place("out", "外"), school)
        assertEquals("home", Places.idFromTaskLabel(renamed, "自宅のみ"))
        assertEquals("home", Places.idFromTaskLabel(renamed, "家のみ"))
        assertEquals("p1", Places.idFromTaskLabel(renamed, "学校のみ"))
        assertEquals("anywhere", Places.idFromTaskLabel(renamed, "どこでも"))
        assertNull(Places.idFromTaskLabel(renamed, "図書館のみ"))
    }

    @Test
    fun `名前の検査`() {
        assertNull(Places.validateName(withSchool, "図書館", null))
        assertNotNull(Places.validateName(withSchool, "", null))
        assertNotNull(Places.validateName(withSchool, "学校", null))       // 重複
        assertNull(Places.validateName(withSchool, "学校", "p1"))          // 自分自身はよい
        assertNotNull(Places.validateName(withSchool, "家", null))         // 家の印と重なる
        assertNotNull(Places.validateName(withSchool, "&o", null))         // 外の印と重なる
        assertNotNull(Places.validateName(withSchool, "どこでも", null))
        assertNotNull(Places.validateName(withSchool, "作業%", null))      // 自動生成の印
        assertNotNull(Places.validateName(withSchool, "あ".repeat(11), null))
    }

    @Test
    fun `教材の場所は、決めていなければ必要なものから決める`() {
        assertEquals("anywhere", Places.materialPlace("", "none"))
        assertEquals("home", Places.materialPlace("", "desk"))
        assertEquals("p1", Places.materialPlace("p1", "desk"))
        assertEquals("anywhere", Places.materialPlace("anywhere", "pc"))
    }

    @Test
    fun `新しいidは被らない`() {
        val list = Places.DEFAULT + Place("p5", "a")
        assertEquals("p5-1", Places.newId(list, 5))
    }
}
