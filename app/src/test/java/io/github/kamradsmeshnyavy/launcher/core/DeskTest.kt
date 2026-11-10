package io.github.kamradsmeshnyavy.launcher.core

import io.github.kamradsmeshnyavy.launcher.core.Desk.Grid
import io.github.kamradsmeshnyavy.launcher.core.Desk.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskTest {
    private fun item(id: String, x: Int, y: Int, w: Int, h: Int, order: Int = 0) = Item(id, "widget", "t", x = x, y = y, w = w, h = h, order = order)

    private fun noOverlaps(items: List<Item>) {
        for (a in items) for (b in items) if (a !== b) assertFalse("${a.id} × ${b.id}", Grid.overlaps(a, b.x, b.y, b.w, b.h))
    }

    @Test
    fun firstFreeFillsLeftToRightTopToBottom() {
        val items = listOf(item("a", 0, 0, 2, 2))
        assertEquals(2 to 0, Grid.firstFree(items, 2, 2))
        assertEquals(0 to 2, Grid.firstFree(items, 4, 1))
        assertEquals(2 to 0, Grid.firstFree(items, 1, 1))
    }

    @Test
    fun movingPushesOthersDown() {
        val items = mutableListOf(item("a", 0, 0, 4, 2), item("b", 0, 2, 2, 2), item("c", 2, 2, 2, 1))
        Grid.move(items, items[1], 0, 1)
        noOverlaps(items)
        assertEquals(1, items[1].y)
        assertTrue(items[0].y >= 3)
    }

    @Test
    fun resizeStaysInsideTheColumns() {
        val items = mutableListOf(item("a", 3, 0, 1, 1), item("b", 0, 1, 4, 1))
        Grid.resize(items, items[0], 3, 2)
        assertEquals(1, items[0].x)
        noOverlaps(items)
    }

    @Test
    fun compactPullsUp() {
        val items = mutableListOf(item("a", 0, 3, 4, 1), item("b", 0, 5, 2, 2))
        Grid.compact(items)
        assertEquals(0, items[0].y)
        assertEquals(1, items[1].y)
    }

    @Test
    fun stackRowsPairNarrowOnes() {
        val items = listOf(item("w", 0, 0, 4, 2, 0), item("n1", 0, 0, 2, 2, 1), item("n2", 0, 0, 2, 2, 2), item("n3", 0, 0, 1, 1, 3), item("w2", 0, 0, 3, 1, 4))
        val rows = Desk.stackRows(items).map { r -> r.map { it.id } }
        assertEquals(listOf(listOf("w"), listOf("n1", "n2"), listOf("n3"), listOf("w2")), rows)
    }

    @Test
    fun shiftReorders() {
        val items = mutableListOf(item("a", 0, 0, 4, 1, 0), item("b", 0, 0, 4, 1, 1), item("c", 0, 0, 4, 1, 2))
        Desk.shift(items, items[2], -1)
        assertEquals(listOf("a", "c", "b"), items.sortedBy { it.order }.map { it.id })
    }

    @Test
    fun theFirstDeskIsTheOldHomeScreen() {
        val space = Desk.initial()
        assertEquals(listOf("clock", "media", "todo", ""), space.items.map { it.type })
        assertEquals("favourites", space.items.last().kind)
        noOverlaps(space.items)
    }

    @Test
    fun noRoomIsNull() {
        val items = listOf(item("a", 0, 0, 4, 4))
        assertNull(Grid.firstFree(items, 4, 1, rows = 3))
    }
}
