package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** #62, #64: a lyric with no translation or romanisation, given one out of another copy of the song. */
class BorrowTranslationsTest {

    private fun line(text: String, start: Int, tr: String? = null, words: Boolean = false,
                     roma: String? = null) =
        if (words) LyricLine(text, tr, roma, start, start + 2000, false,
            intArrayOf(start), intArrayOf(start + 2000), intArrayOf(text.length))
        else LyricLine(text, tr, roma, start, start + 2000, false, null, null, null)

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
        val out = LyricParse.borrow(apple, ncm, true, false).lines
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
        val out = LyricParse.borrow(own, shifted, true, false).lines
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
        val lent = LyricParse.borrow(apple, other, true, true)
        assertEquals(0, lent.translations + lent.romas)
        assertSame(apple, lent.lines)
    }

    @Test
    fun aLyricThatHasTranslationsKeepsThem() {
        val translated = listOf(line("ねぇ", 11850, "呐"))
        val lent = LyricParse.borrow(translated, listOf(line("ねぇ", 11850, "喂")), true, false)
        assertEquals(0, lent.translations)
        assertEquals("呐", lent.lines[0].translation)
    }

    @Test
    fun aRomanisationIsBorrowedBesideTheTranslationAlreadyThere() {
        // The database's TTML with its own translation and no romanisation; NetEase's copy has one.
        val amll = listOf(line("ねぇ", 11850, "呐", words = true),
            line("今日も君を想う", 14200, "今天也在想你", words = true))
        val ncm = listOf(line("ねぇ", 12100, "喂", roma = "nee"),
            line("今日も君を想う", 14600, "今天也想你", roma = "kyou mo kimi wo omou"))
        val lent = LyricParse.borrow(amll, ncm, false, true)
        assertEquals(2, lent.romas)
        assertEquals(0, lent.translations)
        assertEquals("nee", lent.lines[0].roma)
        // The database's own translation stays.
        assertEquals("今天也在想你", lent.lines[1].translation)
    }

    @Test
    fun theSwitchesChooseWhatIsDrawnUnder() {
        val l = line("ねぇ", 0, "呐", roma = "nee")
        val trans = LockLyrics.BELOW_TRANS
        val roma = LockLyrics.BELOW_ROMA
        assertEquals("呐", l.under(trans))
        assertEquals("nee", l.under(roma))
        assertEquals("nee" + 10.toChar() + "呐", l.under(trans or roma))
        assertNull(l.under(0))
        assertEquals("nee", line("ねぇ", 0, roma = "nee").under(trans or roma))
    }

    @Test
    fun aCatalogueRomanisationIsKeptApartFromItsTranslation() {
        val lines = LyricParse.parse("[00:01.00]君はひとり", "[00:01.00]你孤身一人",
            "[00:01.00]kimi wa hitori")
        assertEquals("你孤身一人", lines[0].translation)
        assertEquals("kimi wa hitori", lines[0].roma)
        // An English song "romanised" into itself has none.
        assertNull(LyricParse.parse("[00:01.00]Hello", null, "[00:01.00]Hello")[0].roma)
    }

    @Test
    fun aRomanisationIsWorthHavingForAnythingButLatinLetters() {
        assertTrue(LyricParse.romanisable(apple))
        assertTrue(LyricParse.romanisable(listOf(line("我哋一齊走過", 0))))
        assertFalse(LyricParse.romanisable(listOf(line("Welcome to my blue shining", 0))))
    }

    @Test
    fun streamingPlayersAreTold() {
        assertTrue(LyricSource.streaming("com.tencent.qqmusic"))
        assertTrue(LyricSource.streaming("com.kugou.android.lite"))
        assertTrue(LyricSource.streaming("com.miui.player"))
        assertFalse(LyricSource.streaming("com.salt.music"))
        assertFalse(LyricSource.streaming("com.maxmpz.audioplayer"))
        assertEquals(null, LyricSource.dirForPackage("com.example.pineapple"))
        assertEquals("am-lyrics", LyricSource.dirForPackage("com.apple.android.music"))
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

    @Test
    fun aLyricTheParserCannotReadIsNoLyricNotACrash() {
        // NetEase 2057709543 (邓紫棋 Pasión): lyrics-core's EnhancedLrcParser trips a require()
        // on it, and uncaught on the borrowing thread that took SystemUI down (2026-10-06).
        val body = javaClass.classLoader!!.getResource("ncm-2057709543.lrc")!!.readText()
        // And since its one row out of place is put back (bracketWords), it reads.
        assertTrue(LyricParse.parse(body, null, null).size > 40)
    }
}
