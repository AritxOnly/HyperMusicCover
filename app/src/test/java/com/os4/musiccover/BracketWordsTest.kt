package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Word timings in the line's own brackets (#64): LyricParse.bracketWords. */
class BracketWordsTest {
    private fun parse(vararg rows: String) = LyricParse.parse(rows.joinToString("\n"))

    @Test
    fun theFirstWordIsKept() {
        val lines = parse("[00:01.000]日[00:01.100]本[00:01.200]語[00:01.500]",
            "[00:02.000]Let's [00:02.300]just [00:02.600]forget[00:03.000]")
        assertEquals("日本語", lines[0].text)
        assertEquals("Let's just forget", lines[1].text)
        assertTrue(lines[0].hasWords())
        assertEquals(1000, lines[0].start)
    }

    @Test
    fun aClosingTimeIsNotShown() {
        assertEquals("Goodbyes", parse("[01:12.392]Goodbyes[01:15.342]").single().text)
        assertEquals("Goodbyes", parse("[01:15.342]Goodbyes[01:15.342]").single().text)
    }

    @Test
    fun aTranslationAtItsLinesStartIsItsTranslation() {
        val lines = parse("[tool:LDDC v0.9.2]",
            "[00:01.001]日[00:01.101]本[00:01.201]語",
            "[00:01.000]日本语翻译[00:01.000]",
            "[00:02.000]次[00:02.100]の[00:02.500]",
            "[00:02.000]下一个[00:02.500]")
        assertEquals(2, lines.size)
        assertEquals("日本語", lines[0].text)
        assertEquals("日本语翻译", lines[0].translation)
        assertEquals("次の", lines[1].text)
        assertEquals("下一个", lines[1].translation)
    }

    @Test
    fun aTranslationJustBeforeTheNextLineIsTheLastLines() {
        val lines = parse("[00:52.232]Let's [00:52.464]just [00:53.176]forget[00:56.352]",
            "[00:57.263]让我们就此遗忘[00:57.263]",
            "[00:57.264]Everything [00:58.440]said[01:00.024]")
        assertEquals(2, lines.size)
        assertEquals("Let's just forget", lines[0].text)
        assertEquals("让我们就此遗忘", lines[0].translation)
        assertEquals("Everything said", lines[1].text)
        assertNull(lines[1].translation)
    }

    @Test
    fun aDuetPrefixHoldsUntilTheNextOne() {
        val lines = parse("[00:01.000]女：[00:01.100]镜[00:01.250]中[00:01.400]",
            "[00:05.000]偶尔[00:05.300]红妆[00:05.600]",
            "[00:09.000]男：[00:09.100]只是[00:09.300]路过[00:09.500]",
            "[00:13.000]安静[00:13.300]离开[00:13.600]",
            "[00:17.000]女：[00:17.100]再见[00:17.400]",
            "[00:21.000]男：[00:21.100]好的[00:21.400]")
        assertEquals(listOf("镜中", "偶尔红妆", "只是路过", "安静离开", "再见", "好的"),
            lines.map { it.text })
        assertEquals(listOf(false, false, true, true, false, true), lines.map { it.opposite })
    }

    @Test
    fun aLineSungTwiceIsOneRowPerTime() {
        val lines = parse("[ti:歌]", "[00:01.00][00:05.00]副歌", "[00:03.00]主歌",
            "[00:07.00] [00:07.00]歌词")
        assertEquals(listOf("副歌", "主歌", "副歌", "歌词"), lines.map { it.text })
        assertEquals(listOf(1000, 3000, 5000, 7000), lines.map { it.start })
    }

    /** NetEase 2057709543 (Pasión): one row out of place, and the whole lyric was lost. */
    @Test
    fun aRowOutOfPlaceIsPutBack() {
        val lines = parse("[00:00.00] 作词 : G.E.M.", "[02:10.00]before",
            "[02:20.02]Cuz Father, my heart aches", "[02:14.44]¿Alguien escucha mi llanto?",
            "[02:30.00]after")
        assertEquals(listOf(0, 130000, 134440, 140020, 150000), lines.map { it.start })
    }

    @Test
    fun aLineSungTwiceKeepsItsTranslation() {
        val lines = parse("[00:01.00][00:05.00]副歌", "[00:01.00][00:05.00]chorus",
            "[00:03.00]主歌", "[00:03.00]verse")
        assertEquals(listOf("副歌", "主歌", "副歌"), lines.map { it.text })
        assertEquals(listOf("chorus", "verse", "chorus"), lines.map { it.translation })
    }

    @Test
    fun otherFormatsAreUntouched() {
        val enhanced = "[00:01.000]<00:01.000>日<00:01.100>本<00:01.200>語<00:01.500>"
        assertEquals(enhanced, LyricParse.bracketWords(enhanced).body)
        val plain = "[ti:歌]\n[00:01.00]日本語\n[00:01.00]日本语翻译"
        assertEquals(plain, LyricParse.bracketWords(plain).body)
        assertFalse(LyricParse.bracketWords("[00:01.000]x[00:01.500]").body.contains("01.500"))
    }
}
