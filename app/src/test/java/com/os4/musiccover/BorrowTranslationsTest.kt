package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** #62: Apple's own lyric with no translation, translated out of another copy of the song. */
class BorrowTranslationsTest {

    private fun line(text: String, start: Int, tr: String? = null, words: Boolean = false) =
        if (words) LyricLine(text, tr, start, start + 2000, false,
            intArrayOf(start), intArrayOf(start + 2000), intArrayOf(text.length))
        else LyricLine(text, tr, start, start + 2000, false, null, null, null)

    private val apple = listOf(
        line("ねぇ", 11850, words = true),
        line("今日も君を想う", 14200, words = true),
        line("Credits", 30000, words = true),
    )

    @Test
    fun theTranslationsMoveAcrossAndTheTimingStays() {
        val ncm = listOf(
            line("ねぇ", 12100, "呐"),
            line("今日も君を想う", 14600, "今天也在想你"),
        )
        val out = LyricParse.borrowTranslations(apple, ncm)!!
        assertEquals("呐", out[0].translation)
        assertEquals("今天也在想你", out[1].translation)
        assertNull(out[2].translation)
        // Apple's words and their timing are what is drawn.
        assertEquals(11850, out[0].start)
        assertTrue(out[1].sylStart != null)
        assertSame(apple[2], out[2])
    }

    @Test
    fun aCopyShiftedAgainstOursStillLends() {
        // Another master: every line 2.4s later, past the translation window on its own.
        val own = listOf(
            line("Right from the start", 10000, words = true),
            line("You were a thief, you stole my heart", 11100, words = true),
            line("And I your willing victim", 14000, words = true),
        )
        val shifted = listOf(
            line("Right from the start", 12400, "从一开始"),
            line("You were a thief you stole my heart", 13500, "你就是个小偷 偷走我的心"),
            line("And I your willing victim", 16400, "而我甘愿成为你的俘虏"),
        )
        val out = LyricParse.borrowTranslations(own, shifted)!!
        assertEquals("从一开始", out[0].translation)
        assertEquals("你就是个小偷 偷走我的心", out[1].translation)
        assertEquals("而我甘愿成为你的俘虏", out[2].translation)
    }

    @Test
    fun anotherSongLendsNothing() {
        val other = listOf(
            line("全然違う歌", 12000, "完全不同的歌"),
            line("別の言葉", 14500, "别的话"),
        )
        assertNull(LyricParse.borrowTranslations(apple, other))
    }

    @Test
    fun aLyricThatHasTranslationsKeepsThem() {
        val translated = listOf(line("ねぇ", 11850, "呐"))
        assertNull(LyricParse.borrowTranslations(translated, listOf(line("ねぇ", 11850, "喂"))))
    }

    @Test
    fun onlyAForeignLyricIsWorthTranslating() {
        assertTrue(LyricParse.foreign(apple))
        assertTrue(LyricParse.foreign(listOf(line("Welcome to my blue shining", 0))))
        assertFalse(LyricParse.foreign(listOf(line("这一路上走走停停", 0), line("So what", 2000))))
    }

    @Test
    fun aTitleLosesItsCredits() {
        assertEquals("セカイツナガレ", TrackName.undecorated(
            "セカイツナガレ (feat. Liko(CV:Minori Suzuki) & Roy(CV:Yuka Terasaki))"))
        assertEquals("La La La Love Song", TrackName.undecorated("La La La Love Song (with NAOMI CAMPBELL)"))
        assertEquals("Say Yes", TrackName.undecorated("Say Yes"))
        assertEquals("(feat. nobody)", TrackName.undecorated("(feat. nobody)"))
    }
}
