package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ARTIST fields here are the ones the players published in the logs attached to #47 (QQ 音乐)
 * and #56 (汽水音乐), and Salt Player's from 2026-09-22.
 */
class TrackNameTest {

    private val em = TrackName.EM

    @Test
    fun qishuiSpellsTheSongBeforeAnEmDash() {
        val artist = "霍希进行曲 (秋天的风她不曾见过桃花)${em}AKA时空恋人"
        assertEquals("AKA时空恋人", TrackName.singer(null, artist, null))
        assertTrue(TrackName.songIn("霍希进行曲 (秋天的风她不曾见过桃花)", artist))
        assertTrue(TrackName.pairIn("霍希进行曲 (秋天的风她不曾见过桃花)", "AKA时空恋人", artist))
        // The previous song the bridge was still holding.
        assertFalse(TrackName.pairIn("我要的很简单，一个家有你在（明知故犯）", "耳机里的盲盒", artist))
    }

    @Test
    fun qishuiPlainShapeKeepsItsArtist() {
        assertEquals("AKA时空恋人", TrackName.singer(null, "AKA时空恋人", null))
        assertTrue(TrackName.splits("AKA时空恋人").isEmpty())
    }

    @Test
    fun saltSpellsTheSongAfterASpacedHyphen() {
        val artist = "G.E.M.邓紫棋 - 喜欢你"
        assertEquals(artist, TrackName.singer(null, artist, null))
        assertTrue(TrackName.songIn("喜欢你", artist))
        assertTrue(TrackName.pairIn("喜欢你", "G.E.M.邓紫棋", artist))
    }

    @Test
    fun qqSpellsTheSongBeforeABareHyphen() {
        val artist = "Miss Americana & The Heartbreak Prince-Taylor Swift"
        assertTrue(TrackName.songIn("Miss Americana & The Heartbreak Prince", artist))
        assertTrue(TrackName.pairIn("Miss Americana & The Heartbreak Prince", "Taylor Swift", artist))
    }

    @Test
    fun qqSingleIsReadByItsAlbum() {
        // Read off this phone 2026-10-06: a line as the title, the album the single.
        assertEquals("I'SLAND", TrackName.singer(null, "I'sland-I'SLAND", "I'sland"))
        assertEquals("朱婧汐Akini Jing", TrackName.singer(null, "不怕！bu pa！-朱婧汐Akini Jing", "不怕！bu pa！"))
        // Off a playlist the album says nothing, and nothing has been seen: left as it is.
        assertEquals("爱意侵占计划 (粤语版)-Simyee陈芯怡",
            TrackName.singer("com.example.none", "爱意侵占计划 (粤语版)-Simyee陈芯怡", "最近访客"))
    }

    @Test
    fun aHyphenatedSingerIsLeftAlone() {
        assertEquals("A-Lin", TrackName.singer("com.example.none", "A-Lin", "罪恶感"))
    }

    @Test
    fun aBareHyphenInANameAgreesWithNothingButItsHalves() {
        // A-Lin's own name splits into "A" and "Lin"; a containment test would take any song
        // with an "a" in it.
        assertFalse(TrackName.songIn("给我一个理由忘记", "A-Lin"))
        assertFalse(TrackName.songIn("Lingering", "A-Lin"))
    }
}
