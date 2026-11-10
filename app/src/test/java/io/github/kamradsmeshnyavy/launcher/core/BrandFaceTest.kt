package io.github.kamradsmeshnyavy.launcher.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BrandFaceTest {
    @Test
    fun nihilIsTwentyOneColumnsAsTheLogo() {
        // N 5, I 1, H 5, I 1, L 5, and a dark column between each.
        assertEquals(21, BrandFace.columns("NIHIL").size)
    }

    @Test
    fun dotMatrixHasEveryLetter() {
        // D O T M A T R X 5 columns each, - 3, I 1, and a dark column between each of the 10.
        assertEquals(8 * 5 + 3 + 1 + 9, BrandFace.columns().size)
    }

    @Test
    fun theIIsOneColumnLitTopToBottom() {
        val columns = BrandFace.columns("NIHIL")
        assertArrayEquals(BooleanArray(7) { false }, columns[5])
        assertArrayEquals(BooleanArray(7) { true }, columns[6])
        assertArrayEquals(BooleanArray(7) { false }, columns[7])
    }
}
