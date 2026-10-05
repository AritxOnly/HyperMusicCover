package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricParseLddcTest {
    @Test
    fun lddcDuplicateTimestampTranslationIsAttachedWithoutTailTimestamp() {
        val body = """
            [tool:LDDC v0.9.2]
            [00:01.001]日[00:01.101]本[00:01.201]語
            [00:01.000]日本语翻译[00:01.000]
        """.trimIndent()

        LyricParse.setDisplayRoma(false)
        val line = LyricParse.parse(body).first()

        assertEquals("日本語", line.text)
        assertEquals("日本语翻译", line.translation)
        assertTrue(line.hasWords())
    }

    @Test
    fun romanisationIsOptionalAndOffByDefault() {
        val body = "[00:01.000]日[00:01.100]本"
        val roma = "[00:01.000]Ri[00:01.100]Ben"

        LyricParse.setDisplayRoma(false)
        assertEquals(null, LyricParse.parse(body, null, roma).first().translation)

        LyricParse.setDisplayRoma(true)
        assertEquals("Ri Ben", LyricParse.parse(body, null, roma).first().translation)

        LyricParse.setDisplayRoma(false)
    }

    @Test
    fun localLyricKeepsOriginalAndTakesMissingTranslationFromOnlineCopy() {
        val local = LyricLine("桜の花", null, 1000, 2000, false, null, null, null)
        val online = LyricLine("桜の花", "樱花", 1080, 2000, false, null, null, null)

        LyricParse.setDisplayRoma(false)
        val merged = LyricParse.mergeSupplement(listOf(local), listOf(online)).first()

        assertEquals("桜の花", merged.text)
        assertEquals("樱花", merged.translation)
        LyricParse.setDisplayRoma(false)
    }

    @Test
    fun romanisationSupplementCanUpgradeExistingTranslation() {
        val local = LyricLine("桜の花", "樱花", 1000, 2000, false, null, null, null)
        val online = LyricLine("桜の花", "Sakura no hana\n樱花", 1080, 2000, false, null, null, null)

        LyricParse.setDisplayRoma(true)
        val merged = LyricParse.mergeSupplement(listOf(local), listOf(online)).first()

        assertEquals("Sakura no hana\n樱花", merged.translation)
        LyricParse.setDisplayRoma(false)
    }
    @Test
    fun terminalEndTimestampOnAWordTimedLyricIsNotDisplayed() {
        LyricParse.setDisplayRoma(false)
        val line = LyricParse.parse("[01:12.392]Goodbyes[01:15.342]").first()
        assertEquals("Goodbyes", line.text)
        assertTrue(!line.text.contains("01:15.342"))
    }

    @Test
    fun duplicateTailTimestampOnARealLyricIsRemoved() {
        LyricParse.setDisplayRoma(false)
        val line = LyricParse.parse("[01:15.342]Goodbyes[01:15.342]").first()
        assertEquals("Goodbyes", line.text)
    }

    @Test
    fun duplicateTranslationAtTheEndOfALongWordLineStillMatchesItsOwner() {
        LyricParse.setDisplayRoma(false)
        val body = """
            [00:01.000]日[00:02.000]本[00:03.000]語
            [00:03.001]日本語翻译[00:03.001]
        """.trimIndent()
        val line = LyricParse.parse(body).first()
        assertEquals("日本語", line.text)
        assertEquals("日本語翻译", line.translation)
    }

    @Test
    fun speakerPrefixNeverRemovesTheFirstRealCharacter() {
        LyricParse.setDisplayRoma(false)
        val body = """
            [00:01.000]<00:01.000>女：<00:01.100>镜<00:01.250>中的<00:01.450>自己
            [00:05.000]<00:05.000>女：<00:05.100>偶尔<00:05.300>红妆
            [00:09.000]<00:09.000>男：<00:09.100>只是<00:09.300>路过
            [00:13.000]<00:13.000>男：<00:13.100>安静<00:13.300>离开
        """.trimIndent()

        val lines = LyricParse.parse(body)
        assertTrue(lines.any { it.text == "镜中的自己" })
        assertTrue(lines.any { it.text == "偶尔红妆" })
        assertTrue(lines.any { it.text == "只是路过" })
        assertTrue(lines.any { it.text == "安静离开" })
    }

}
