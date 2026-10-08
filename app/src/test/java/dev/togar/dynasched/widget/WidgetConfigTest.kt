package dev.togar.dynasched.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetConfigTest {

    @Test
    fun `書いて読むと元に戻る`() {
        val c = WidgetConfig(ProgressStyle.RING, "p1", 30, 15, setOf("家事", "買い物"), "hobby", 5, 2, "家事用")
        assertEquals(c, WidgetConfig.decode(WidgetConfig.encode(c)))
    }

    @Test
    fun `v46の形も読める・壊れていれば既定`() {
        val c = WidgetConfig.decode("style=CHAIN;loc=out;min=0;minLo=0;tags=;kind=both;prio=0;lines=3;title=")
        assertEquals(ProgressStyle.CHAIN, c.style)
        assertEquals("out", c.loc)
        assertEquals(WidgetConfig(), WidgetConfig.decode("???"))
        assertEquals(WidgetConfig(), WidgetConfig.decode(null))
    }
}
