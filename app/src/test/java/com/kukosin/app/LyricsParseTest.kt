package com.kukosin.app

import com.kukosin.app.data.analysis.EmbeddedMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsParseTest {
    @Test
    fun classicRepeatedTimestampsStayWholeLines() {
        val lines = EmbeddedMetadata.parseLrc(
            """
            [00:12.00][00:45.00]Same chorus
            [01:02.50]Next verse
            """.trimIndent(),
        )
        assertEquals(3, lines.size)
        assertEquals("Same chorus", lines[0].text)
        assertEquals(12_000L, lines[0].timeMs)
        assertEquals(45_000L, lines[1].timeMs)
        assertFalse(lines[0].isKaraoke)
        assertEquals("Next verse", lines[2].text)
    }

    @Test
    fun inlineWordTimestampsBecomeKaraoke() {
        val lines = EmbeddedMetadata.parseLrc("[00:01.00]I [00:01.40]love [00:02.10]you")
        assertEquals(1, lines.size)
        assertTrue(lines[0].isKaraoke)
        assertEquals("I love you", lines[0].text)
        assertEquals(listOf(1_000L, 1_400L, 2_100L), lines[0].words.map { it.timeMs })
    }

    @Test
    fun enhancedAngleBracketKaraoke() {
        val lines = EmbeddedMetadata.parseLrc("[00:12.00]Hello <00:12.50>world <00:13.00>friends")
        assertEquals(1, lines.size)
        assertTrue(lines[0].isKaraoke)
        assertEquals(3, lines[0].words.size)
        assertEquals("Hello world friends", lines[0].text)
        assertEquals(12_500L, lines[0].words[1].timeMs)
    }

    @Test
    fun syltSyllablesGroupIntoVerses() {
        val grouped = EmbeddedMetadata.groupSyltEvents(
            listOf(
                1000L to "Hel",
                1200L to "lo ",
                1500L to "world",
                5000L to "Next",
                5300L to " line",
            ),
        )
        assertEquals(2, grouped.size)
        assertTrue(grouped[0].isKaraoke)
        assertEquals("Hello world", grouped[0].text)
        assertEquals("Next line", grouped[1].text)
    }
}
