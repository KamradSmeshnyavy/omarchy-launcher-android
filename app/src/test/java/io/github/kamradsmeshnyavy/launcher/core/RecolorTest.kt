package io.github.kamradsmeshnyavy.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecolorTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFFF0000.toInt()

    private fun channel(c: Int, shift: Int) = (c shr shift) and 0xFF

    @Test
    fun palettePixelsStayNearTheirColour() {
        val out = Recolor.recolor(intArrayOf(black, white, red), intArrayOf(black, white, red), shape = 12f)
        assertTrue(channel(out[0], 16) < 20)
        assertTrue(channel(out[1], 8) > 235)
        assertTrue(channel(out[2], 16) > 235 && channel(out[2], 8) < 20)
    }

    @Test
    fun keepsAlpha() {
        val out = Recolor.recolor(intArrayOf(0x80FF0000.toInt()), intArrayOf(black, red))
        assertEquals(0x80, out[0] ushr 24)
    }

    @Test
    fun grayGoesBetweenBlackAndWhite() {
        val out = Recolor.recolor(intArrayOf(0xFF808080.toInt()), intArrayOf(black, white), shape = 200f)
        val g = channel(out[0], 8)
        assertTrue("got $g", g in 60..200)
    }

    @Test
    fun singleColourPaletteFillsEverything() {
        val out = Recolor.recolor(intArrayOf(white, black, 0xFF123456.toInt()), intArrayOf(red), shape = 1f)
        out.forEach { assertEquals(red, it) }
    }
}
