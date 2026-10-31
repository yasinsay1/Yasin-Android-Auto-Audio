package com.yasincarplay.audio.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayQueueTest {

    @Test
    fun singleItemHasNoSkip() {
        val q = PlayQueue()
        q.select("a")
        assertFalse(q.hasMultiple)
        assertNull(q.next())
        assertNull(q.previous())
        assertEquals("a", q.currentId)
    }

    @Test
    fun emptyQueueHasNoSkip() {
        val q = PlayQueue()
        assertFalse(q.hasMultiple)
        assertNull(q.next())
        assertNull(q.previous())
        assertNull(q.currentId)
    }

    @Test
    fun nextAndPreviousFollowListOrderAndWrap() {
        val q = PlayQueue()
        q.remember("top", listOf("a", "b", "c"))
        q.select("b", "top")
        assertTrue(q.hasMultiple)
        assertEquals("c", q.next())
        assertEquals("a", q.next()) // sonda başa sarar
        assertEquals("c", q.previous()) // başta sona sarar
        assertEquals("b", q.previous())
        assertEquals("b", q.currentId)
    }

    @Test
    fun preferredListWinsOverNewerList() {
        val q = PlayQueue()
        q.remember("favorites", listOf("a", "x"))
        q.remember("top", listOf("a", "b", "c"))
        q.select("a", "favorites")
        assertEquals("x", q.next())
    }

    @Test
    fun withoutKeyNewestListContainingItemWins() {
        val q = PlayQueue()
        q.remember("favorites", listOf("a", "x"))
        q.remember("top", listOf("a", "b", "c"))
        q.select("a")
        assertEquals("b", q.next())
    }

    @Test
    fun rememberingAgainMakesListNewest() {
        val q = PlayQueue()
        q.remember("top", listOf("a", "b"))
        q.remember("favorites", listOf("a", "x"))
        q.remember("top", listOf("a", "b")) // yeniden gösterildi
        q.select("a")
        assertEquals("b", q.next())
    }

    @Test
    fun keyNotContainingItemFallsBackToNewestList() {
        val q = PlayQueue()
        q.remember("top", listOf("a", "b"))
        q.remember("favorites", listOf("x", "y"))
        q.select("b", "favorites") // favorilerde yok
        assertEquals("a", q.next())
    }

    @Test
    fun itemInNoListBecomesSingleItemQueue() {
        val q = PlayQueue()
        q.remember("top", listOf("a", "b"))
        q.select("zzz")
        assertFalse(q.hasMultiple)
        assertNull(q.next())
    }

    @Test
    fun emptyListIsNotRemembered() {
        val q = PlayQueue()
        q.remember("top", emptyList())
        q.select("a", "top")
        assertFalse(q.hasMultiple)
    }

    @Test
    fun oldestListIsEvictedBeyondLimit() {
        val q = PlayQueue(maxLists = 2)
        q.remember("one", listOf("a", "b"))
        q.remember("two", listOf("c", "d"))
        q.remember("three", listOf("e", "f"))
        q.select("a", "one") // "one" atıldı, tek öğeli sıra olur
        assertFalse(q.hasMultiple)
        q.select("c", "two")
        assertTrue(q.hasMultiple)
    }

    @Test
    fun clearEmptiesQueueButKeepsRememberedLists() {
        val q = PlayQueue()
        q.remember("top", listOf("a", "b"))
        q.select("a", "top")
        q.clear()
        assertFalse(q.hasMultiple)
        assertNull(q.currentId)
        q.select("b", "top")
        assertEquals("a", q.next())
    }
}
