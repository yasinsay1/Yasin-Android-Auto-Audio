package com.yasincarplay.audio.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PodcastRepositoryTest {
    @Test
    fun parsesHoursMinutesSeconds() {
        assertEquals(3_723_000L, PodcastRepository.parseDurationMs("01:02:03"))
    }

    @Test
    fun parsesMinutesSeconds() {
        assertEquals(125_000L, PodcastRepository.parseDurationMs("02:05"))
    }

    @Test
    fun parsesPlainSeconds() {
        assertEquals(90_000L, PodcastRepository.parseDurationMs(" 90 "))
    }

    @Test
    fun returnsZeroForGarbage() {
        assertEquals(0L, PodcastRepository.parseDurationMs("abc"))
        assertEquals(0L, PodcastRepository.parseDurationMs(""))
    }
}
