package io.github.kamradsmeshnyavy.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetTintTest {
    private val dark = 0xFF102030.toInt()
    private val light = 0xFFE0D0C0.toInt()

    private fun close(a: Int, b: Int, tol: Int = 2) {
        for (shift in intArrayOf(16, 8, 0)) {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            assertTrue("${Integer.toHexString(a)} vs ${Integer.toHexString(b)}", kotlin.math.abs(x - y) <= tol)
        }
    }

    @Test
    fun blackBecomesTheDarkAndWhiteTheLight() {
        val m = WidgetTint.duotone(dark, light)
        close(dark, WidgetTint.apply(m, 0xFF000000.toInt()))
        close(light, WidgetTint.apply(m, 0xFFFFFFFF.toInt()))
    }

    @Test
    fun keepsAlpha() {
        val m = WidgetTint.duotone(dark, light)
        assertEquals(0x40, WidgetTint.apply(m, 0x40FF0000) ushr 24)
    }

    @Test
    fun rampGoesLightToDarkWithTheHue() {
        val r = WidgetTint.ramp(0xFF3366FF.toInt())
        assertEquals(13, r.size)
        assertEquals(0xFFFFFFFF.toInt(), r.first())
        assertEquals(0xFF000000.toInt(), r.last())
        val mid = WidgetTint.hsl(r[7])
        val base = WidgetTint.hsl(0xFF3366FF.toInt())
        assertTrue(kotlin.math.abs(mid.first - base.first) < 0.02f)
        for (i in 1 until r.size) assertTrue(WidgetTint.hsl(r[i]).third <= WidgetTint.hsl(r[i - 1]).third + 1e-3f)
    }

    @Test
    fun hslRoundTrips() {
        for (c in intArrayOf(0xFFD71921.toInt(), 0xFF00FF00.toInt(), 0xFF808080.toInt(), 0xFF1D2A36.toInt())) {
            val (h, s, l) = WidgetTint.hsl(c)
            close(c, WidgetTint.fromHsl(h, s, l))
        }
    }
}
