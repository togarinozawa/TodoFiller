package dev.togar.dynasched.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「作者に送る」の文面。友達に聞き返さずに済むだけの情報が入っているかを固定する */
class FeedbackTest {

    private val env = Feedback.Env("単発", "1.2", 48, "Google Pixel 7a", "Android 17 (API 37)", "2026-10-07 10:00")
    private val crash = Feedback.Crash(1L, "2026-10-06 22:10", "java.lang.IllegalStateException: x\n\tat a.b(C.kt:1)")

    @Test
    fun 種類と本文と自動の情報が入る() {
        val s = Feedback.compose(Feedback.Kind.WANT, "  週の表示がほしい ", env, null)
        assertTrue(s.startsWith("【スキマス】欲しい機能\n週の表示がほしい\n"))
        assertTrue(s.contains("画面: 単発"))
        assertTrue(s.contains("版: v1.2 (48)"))
        assertTrue(s.contains("端末: Google Pixel 7a / Android 17 (API 37)"))
    }

    @Test
    fun 強制終了の記録は不具合の時だけ添える() {
        assertTrue(Feedback.compose(Feedback.Kind.BUG, "落ちた", env, crash).contains("前回の強制終了: 2026-10-06 22:10"))
        assertFalse(Feedback.compose(Feedback.Kind.CHANGE, "色", env, crash).contains("強制終了"))
    }

    @Test
    fun 空では送らない() {
        assertFalse(Feedback.canSend("  \n"))
        assertTrue(Feedback.canSend("あ"))
    }

    @Test
    fun 長いトレースは頭と原因だけ残す() {
        val trace = (1..30).joinToString("\n") { "\tat x.y$it" } + "\nCaused by: java.io.IOException: z\n\tat q"
        val t = Feedback.trimTrace(trace, head = 3)
        assertEquals(listOf("at x.y1", "at x.y2", "at x.y3", "…", "Caused by: java.io.IOException: z", "…（以下略）"),
            t.lines().map { it.trim() })
    }

    @Test
    fun 知らない種類は不具合() {
        assertEquals(Feedback.Kind.BUG, Feedback.Kind.from("???"))
        assertEquals(Feedback.Kind.CHANGE, Feedback.Kind.from("CHANGE"))
    }
}
