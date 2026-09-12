package dev.togar.dynasched.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * NotionのURLからページIDを取る。
 *
 * ここで弾くと**設定画面の入口で詰む**（「URLが読めません」から先へ進めない）。
 * 貼られ方は何通りもあるので、実際に出てくる形を並べて固定する。
 */
class NotionLinkTest {

    private val id = "1a2b3c4d5e6f78909876543210fedcba"
    private val dashed = "1a2b3c4d-5e6f-7890-9876-543210fedcba"

    @Test
    fun 題名付きのURL() {
        assertEquals(dashed, NotionLink.pageIdFrom("https://www.notion.so/My-Tasks-$id"))
    }

    @Test
    fun ワークスペース名が入っていても末尾を取る() {
        assertEquals(dashed, NotionLink.pageIdFrom("https://www.notion.so/togar/Tasks-$id"))
    }

    @Test
    fun ビューIDは本体と間違えない() {
        // ?v= の後ろにも32桁の16進が来る。手前から取ると別物を掴む
        val view = "ffffffffffffffffffffffffffffffff"
        assertEquals(dashed, NotionLink.pageIdFrom("https://www.notion.so/Tasks-$id?v=$view"))
    }

    @Test
    fun 共有リンクの余計な問い合わせを落とす() {
        assertEquals(dashed, NotionLink.pageIdFrom("https://www.notion.so/Tasks-$id?pvs=4"))
    }

    @Test
    fun 区切り付きで貼られてもそのまま読む() {
        assertEquals(dashed, NotionLink.pageIdFrom("https://www.notion.so/$dashed"))
    }

    @Test
    fun アプリのURLでも読む() {
        assertEquals(dashed, NotionLink.pageIdFrom("notion://www.notion.so/Tasks-$id"))
    }

    @Test
    fun IDだけ貼られても読む() {
        assertEquals(dashed, NotionLink.pageIdFrom("  $id  "))
    }

    @Test
    fun 大文字でも小文字に揃える() {
        assertEquals(dashed, NotionLink.pageIdFrom("https://www.notion.so/T-" + id.uppercase()))
    }

    @Test
    fun 読めない物はnull() {
        assertNull(NotionLink.pageIdFrom(""))
        assertNull(NotionLink.pageIdFrom("https://www.notion.so/"))
        assertNull(NotionLink.pageIdFrom("これはURLではない"))
        // 32桁に足りない
        assertNull(NotionLink.pageIdFrom("https://www.notion.so/Tasks-1a2b3c4d"))
    }
}
