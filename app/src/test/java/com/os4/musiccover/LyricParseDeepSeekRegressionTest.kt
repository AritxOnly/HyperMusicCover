package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricParseDeepSeekRegressionTest {
    @Test
    fun terminalBracketEndTimestampIsConvertedBeforeParser() {
        LyricParse.setDisplayRoma(false)
        val line = LyricParse.parse("[01:12.392]Goodbyes[01:15.342]").first()
        assertEquals("Goodbyes", line.text)
        assertTrue(!line.text.contains("01:15.342"))
    }

    @Test
    fun duplicateTranslationRowIsAttachedWithoutBecomingPrimaryLyric() {
        LyricParse.setDisplayRoma(false)
        val body = """
            [00:52.232]Let's [00:52.464]just [00:53.176]forget[00:56.352]
            [00:57.263]让我们就此遗忘[00:57.263]
            [00:57.264]Everything [00:58.440]said[01:00.024]
        """.trimIndent()
        val line = LyricParse.parse(body).first()
        assertEquals("Let's just forget", line.text)
        assertEquals("让我们就此遗忘", line.translation)
    }

    @Test
    fun speakerPrefixIsRemovedBeforeWordRangesAreBuilt() {
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
