package io.github.kamradsmeshnyavy.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookTest {
    private fun theme(dark: Boolean, vararg colors: Pair<String, String>) =
        Look.Theme("t", "T", dark, colors.associate { it.first to Look.parseColor(it.second)!! }, null)

    @Test
    fun parsesColours() {
        assertEquals(0xFFD71921.toInt(), Look.parseColor("#d71921"))
        assertEquals(0xFFAABBCC.toInt(), Look.parseColor("#abc"))
        assertEquals(0x80112233.toInt(), Look.parseColor("#80112233"))
        assertNull(Look.parseColor("accent"))
        assertNull(Look.parseColor("#12345"))
    }

    @Test
    fun monoIsTheUpstreamPalette() {
        val p = Look.palette(theme(true, "background" to "#102030", "foreground" to "#ffffff"), mono = true, values = emptyMap())
        assertEquals(0xFF000000.toInt(), p.bg)
        assertEquals(0xFFF2F2F2.toInt(), p.fg)
        assertEquals(0xFFD71921.toInt(), p.signal)
    }

    @Test
    fun rolesComeFromTheTheme() {
        val t = theme(false, "background" to "#ffffff", "foreground" to "#000000", "accent" to "#3366ff", "red" to "#ff0000")
        val p = Look.palette(t, mono = false, values = emptyMap())
        assertEquals(0xFFFFFFFF.toInt(), p.bg)
        assertEquals(0xFF000000.toInt(), p.fg)
        assertEquals(0xFF3366FF.toInt(), p.signal)
        assertEquals(0xFFFF0000.toInt(), p.danger)
        assertFalse(p.dark)
        // Линия — между фоном и текстом, ближе к фону.
        assertTrue(Look.luminance(p.line) in Look.luminance(p.dim)..Look.luminance(p.bg))
    }

    @Test
    fun dotmatrixOverridesWin() {
        val t = theme(true, "background" to "#000000", "foreground" to "#eeeeee", "accent" to "#00ff00", "blue" to "#0000ff")
        val p = Look.palette(t, mono = false, values = mapOf("dotmatrixSignal" to "blue", "dotmatrixBg" to "#111111", "dotmatrixFg" to "default"))
        assertEquals(0xFF0000FF.toInt(), p.signal)
        assertEquals(0xFF111111.toInt(), p.bg)
        assertEquals(0xFFEEEEEE.toInt(), p.fg)
    }

    @Test
    fun mixesOpaque() {
        assertEquals(0xFF808080.toInt(), Look.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
        assertEquals(0xFF000000.toInt(), Look.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0f))
    }
}
