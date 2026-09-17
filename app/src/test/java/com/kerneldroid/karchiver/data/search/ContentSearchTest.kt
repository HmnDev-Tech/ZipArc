package com.kerneldroid.karchiver.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.StringReader

class ContentSearchTest {

    private fun bytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    @Test
    fun findsNeedleWithLineAndSnippet() {
        val scanner = ContentScanner()
        val match = scanner.scan(ByteArrayInputStream(bytes("first line\nsecond line has needle here")), "needle")
        assertNotNull(match)
        assertEquals(2, match!!.line)
        assertTrue(match.snippet.contains("needle"))
        assertTrue(match.snippet.startsWith("second line"))
    }

    @Test
    fun caseInsensitiveByDefaultAndCaseSensitiveOption() {
        val insensitive = ContentScanner()
        assertNotNull(insensitive.scan(ByteArrayInputStream(bytes("Hello World")), "hello"))

        val sensitive = ContentScanner(caseSensitive = true)
        assertNull(sensitive.scan(ByteArrayInputStream(bytes("Hello World")), "hello"))
        assertNotNull(sensitive.scan(ByteArrayInputStream(bytes("Hello World")), "Hello"))
    }

    @Test
    fun binaryFilesAreSkipped() {
        val data = byteArrayOf(0, 1, 2, 3, 4, 5) + bytes("needle")
        val scanner = ContentScanner()
        assertNull(scanner.scan(ByteArrayInputStream(data), "needle"))
    }

    @Test
    fun sizeCapExcludesLateMatches() {
        val text = "x".repeat(4096) + "needle"
        val capped = ContentScanner(maxBytes = 64)
        assertNull(capped.scan(ByteArrayInputStream(bytes(text)), "needle"))
        val uncapped = ContentScanner(maxBytes = 8192)
        assertNotNull(uncapped.scan(ByteArrayInputStream(bytes(text)), "needle"))
    }

    @Test
    fun snippetIsTrimmedAndCapped() {
        val line = "   " + "a".repeat(300) + "needle" + "b".repeat(300) + "   "
        val scanner = ContentScanner()
        val match = scanner.scan(ByteArrayInputStream(bytes(line)), "needle")
        assertNotNull(match)
        assertTrue(match!!.snippet.contains("needle"))
        assertTrue(match.snippet.length <= 162)
        assertTrue(!match.snippet.startsWith(" "))
        assertTrue(!match.snippet.endsWith(" "))
    }

    @Test
    fun multipleNeedlesRequireAllToMatch() {
        val scanner = ContentScanner()
        val text = bytes("alpha and beta and gamma")
        assertNotNull(scanner.scan(ByteArrayInputStream(text), listOf("alpha", "gamma")))
        assertNull(scanner.scan(ByteArrayInputStream(text), listOf("alpha", "delta")))
    }

    @Test
    fun matchesAcrossChunkBoundaries() {
        val data = bytes("prefix " + "z".repeat(2000) + " needle suffix")
        val tiny = object : FilterInputStream(ByteArrayInputStream(data)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, if (len > 1) 1 else len)

            override fun read(): Int = super.read()
        }
        val scanner = ContentScanner()
        val match = scanner.scan(tiny, "needle")
        assertNotNull(match)
        assertEquals(1, match!!.line)
    }

    @Test
    fun emptyNeedleReturnsNull() {
        val scanner = ContentScanner()
        assertNull(scanner.scan(ByteArrayInputStream(bytes("anything")), ""))
        assertNull(scanner.scan(ByteArrayInputStream(bytes("anything")), emptyList()))
    }

    @Test
    fun readerVariantWorks() {
        val scanner = ContentScanner()
        val match = scanner.scan(StringReader("reader based scan"), listOf("based"))
        assertNotNull(match)
        assertEquals(1, match!!.line)
    }

    @Test
    fun textVariantMatchesPlainString() {
        val scanner = ContentScanner()
        val match = scanner.scanText("no match\nhere is the token", "token")
        assertNotNull(match)
        assertEquals(2, match!!.line)
    }
}
