package com.os4.musiccover

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The legacy `xposedscope` array and META-INF/xposed/scope.list name the same packages in the
 * same order. The array once fell behind by two (高德, 小爱建议), and 重启全部作用域, which read
 * it, left those processes on the old module.
 */
class ScopeListTest {
    @Test
    fun legacyArrayMatchesScopeList() {
        val scopeList = File("src/main/resources/META-INF/xposed/scope.list").readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val item = Regex("<item>([^<]+)</item>")
        val array = item.findAll(File("src/main/res/values/arrays.xml").readText())
            .map { it.groupValues[1].trim() }.toList()
        assertEquals(scopeList, array)
    }
}
