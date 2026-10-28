package dev.togar.dynasched.integration

import dev.togar.dynasched.api.SuggestItem
import dev.togar.dynasched.data.NowTasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ドパチルへタスクを読ませる口の、相手の見分けと外へ出す形。
 *
 * 見分けを誤ると、**同じ名前を名乗る別のアプリにタスクを読まれる**か、
 * 本物のドパチルが読めずに警告画面へ何も出ない。どちらも画面からは原因が見えない。
 */
class TrustedCallersTest {

    private val oldPc = "827020ecce900217b5523d8a574fe2fb93a0acd1dbc3e04f9e72720509623c83"
    private val thisPc = "cf3470b1af34854262db3b8ce5794797c6936c770e2ecb0f45557f463cd489a6"
    private val ownKey = "d4a015b4a3fb8d39541ad54efdd31ebb3b0376f511a44ccc42c7051859e9ea73"

    @Test
    fun `いまのドパチル（専用の鍵）が読める`() {
        // 0.42 から専用の鍵。これが抜けると、ドパチルが実装しても黙って何も出ない
        assertTrue(TrustedCallers.allowed("com.dopachiru", listOf(ownKey)))
    }

    @Test
    fun `ドパチルの鍵ならどちらのPCで作ったものでも読める`() {
        assertTrue(TrustedCallers.allowed("com.dopachiru", listOf(oldPc)))
        assertTrue(TrustedCallers.allowed("com.dopachiru", listOf(thisPc.uppercase())))
    }

    @Test
    fun `名前だけ同じで鍵が違えば読めない`() {
        assertFalse(TrustedCallers.allowed("com.dopachiru", listOf("00".repeat(32))))
        assertFalse(TrustedCallers.allowed("com.dopachiru", emptyList()))
    }

    @Test
    fun `知らないアプリは鍵が合っていても読めない`() {
        assertFalse(TrustedCallers.allowed("com.example.other", listOf(thisPc)))
        assertFalse(TrustedCallers.allowed(null, listOf(thisPc)))
    }

    @Test
    fun `証明書の指紋は小文字の16進`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            TrustedCallers.sha256(ByteArray(0))
        )
    }

    @Test
    fun `外へは中の呼び名を出さない`() {
        assertEquals("task", NowTasks.externalKind("hobby"))
        assertEquals("study", NowTasks.externalKind("material"))
        // 戻ってきた時は元に戻る。知らない値はタスク扱い
        assertEquals("material", NowTasks.internalKind("study"))
        assertEquals("hobby", NowTasks.internalKind("task"))
        assertEquals("hobby", NowTasks.internalKind(null))
    }

    @Test
    fun `なぜこれかの一言`() {
        assertEquals("優先度 高", NowTasks.reason(SuggestItem("hobby", 1, "x", 30, 8, 0)))
        assertEquals("優先度 低", NowTasks.reason(SuggestItem("hobby", 1, "x", 30, 3, 0)))
        assertEquals("予定（教材）", NowTasks.reason(SuggestItem("material", 1, "x", 30, 5, 10)))
        assertEquals("予定（教材）・締切まで3日", NowTasks.reason(SuggestItem("material", 1, "x", 30, 5, 10, dueDays = 3)))
        assertEquals("予定（教材）・締切は今日", NowTasks.reason(SuggestItem("material", 1, "x", 30, 5, 10, dueDays = 0)))
        assertEquals("今日やる（絶対）・優先度 中",
            NowTasks.reason(SuggestItem("hobby", 1, "x", 30, 5, 0, wantToday = dev.togar.dynasched.data.Want.MUST)))
    }
}
