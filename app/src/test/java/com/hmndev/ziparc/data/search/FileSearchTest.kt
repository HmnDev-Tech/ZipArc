package com.hmndev.ziparc.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class FileSearchTest {

    private fun dayStartMs(date: String): Long =
        LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun plainTextMatchesByName() {
        val q = parseSearchQuery("backup")
        assertTrue(q.matches("my backup.zip", "zip", false, 100, 0))
        assertFalse(q.matches("photos.png", "png", false, 100, 0))
    }

    @Test
    fun multiplePlainTokensCombineWithAnd() {
        val q = parseSearchQuery("backup 2026")
        assertTrue(q.matches("backup 2026.zip", "zip", false, 100, 0))
        assertFalse(q.matches("backup final.zip", "zip", false, 100, 0))
    }

    @Test
    fun explicitNameKey() {
        val q = parseSearchQuery("n:report")
        assertTrue(q.matches("Annual Report.pdf", "pdf", false, 100, 0))
        assertFalse(q.matches("photo.png", "png", false, 100, 0))
    }

    @Test
    fun quotedNameWithSpaces() {
        val q = parseSearchQuery("name:\"my backup\"")
        assertEquals(listOf("my backup"), q.nameParts)
        assertTrue(q.matches("my backup.zip", "zip", false, 100, 0))
        assertFalse(q.matches("my other.zip", "zip", false, 100, 0))
    }

    @Test
    fun extensionFilter() {
        val q = parseSearchQuery("ext:zip,7z")
        assertTrue(q.matches("a.zip", "zip", false, 100, 0))
        assertTrue(q.matches("b.7Z", "7z", false, 100, 0))
        assertFalse(q.matches("c.rar", "rar", false, 100, 0))
        assertFalse(q.matches("docs", "", true, 0, 0))
    }

    @Test
    fun formatAliasMatchesExtension() {
        val q = parseSearchQuery("backup format:zip")
        assertTrue(q.matches("backup.zip", "zip", false, 100, 0))
        assertFalse(q.matches("backup.rar", "rar", false, 100, 0))
    }

    @Test
    fun singleDateMatchesThatDay() {
        val q = parseSearchQuery("date:2026-09-15")
        assertTrue(q.matches("a.zip", "zip", false, 100, dayStartMs("2026-09-15") + 3600_000))
        assertFalse(q.matches("a.zip", "zip", false, 100, dayStartMs("2026-09-14")))
        assertFalse(q.matches("a.zip", "zip", false, 100, dayStartMs("2026-09-16")))
    }

    @Test
    fun dateRangeAndOperators() {
        val range = parseSearchQuery("date:2026-09-01..2026-10-01")
        assertTrue(range.matches("a", "", false, 0, dayStartMs("2026-09-15")))
        assertFalse(range.matches("a", "", false, 0, dayStartMs("2026-10-02")))
        val after = parseSearchQuery("date:>2026-09-01")
        assertTrue(after.matches("a", "", false, 0, dayStartMs("2026-09-02")))
        assertFalse(after.matches("a", "", false, 0, dayStartMs("2026-09-01")))
    }

    @Test
    fun todayAndYesterday() {
        val now = System.currentTimeMillis()
        val today = parseSearchQuery("date:today", now)
        assertTrue(today.matches("a", "", false, 0, now))
        assertFalse(today.matches("a", "", false, 0, now - 2 * 86_400_000L))
        val yesterday = parseSearchQuery("date:yesterday", now)
        assertTrue(yesterday.matches("a", "", false, 0, now - 86_400_000L))
        assertFalse(yesterday.matches("a", "", false, 0, now))
    }

    @Test
    fun invalidDateFallsBackToPlainText() {
        val q = parseSearchQuery("date:notadate")
        assertEquals(listOf("date:notadate"), q.nameParts)
        assertTrue(q.matches("date:notadate", "", false, 0, 0))
    }

    @Test
    fun sizeOperatorsAndUnits() {
        val mb = 1024L * 1024L
        val q = parseSearchQuery("size:>10MB")
        assertTrue(q.matches("a.zip", "zip", false, 11 * mb, 0))
        assertFalse(q.matches("a.zip", "zip", false, 10 * mb, 0))
        assertFalse(q.matches("a.zip", "zip", false, 5 * mb, 0))
        val range = parseSearchQuery("size:1KB..1MB")
        assertTrue(range.matches("a", "", false, 512 * 1024L, 0))
        assertFalse(range.matches("a", "", false, 2 * mb, 0))
    }

    @Test
    fun invalidSizeFallsBackToPlainText() {
        val q = parseSearchQuery("size:huge")
        assertEquals(listOf("size:huge"), q.nameParts)
    }

    @Test
    fun typeFilter() {
        val dirs = parseSearchQuery("type:dir")
        assertTrue(dirs.matches("docs", "", true, 0, 0))
        assertFalse(dirs.matches("a.zip", "zip", false, 100, 0))
        val files = parseSearchQuery("type:file backup")
        assertTrue(files.matches("backup.zip", "zip", false, 100, 0))
        assertFalse(files.matches("backup", "", true, 0, 0))
    }

    @Test
    fun unknownKeyTreatedAsPlainText() {
        val q = parseSearchQuery("color:red")
        assertEquals(listOf("color:red"), q.nameParts)
        assertTrue(q.matches("color:red", "", false, 0, 0))
    }

    @Test
    fun sizeSkippedForDirectories() {
        val q = parseSearchQuery("size:>10MB")
        assertTrue(q.matches("big folder", "", true, 0, 0))
    }

    @Test
    fun blankQueryIsEmpty() {
        assertTrue(parseSearchQuery("").isEmpty)
        assertTrue(parseSearchQuery("   ").isEmpty)
        assertFalse(parseSearchQuery("zip").isEmpty)
    }

    @Test
    fun contentKeyAndAliases() {
        assertEquals(listOf("hello"), parseSearchQuery("content:hello").contentParts)
        assertEquals(listOf("hello"), parseSearchQuery("text:hello").contentParts)
        assertEquals(listOf("hello"), parseSearchQuery("c:hello").contentParts)
        assertTrue(parseSearchQuery("content:hello").nameParts.isEmpty())
        assertFalse(parseSearchQuery("content:hello").isEmpty)
    }

    @Test
    fun archiveKeyAndAlias() {
        assertEquals(listOf("readme"), parseSearchQuery("archive:readme").archiveParts)
        assertEquals(listOf("readme"), parseSearchQuery("a:readme").archiveParts)
        assertFalse(parseSearchQuery("archive:readme").isEmpty)
    }

    @Test
    fun quotedContentAndArchiveValues() {
        val q = parseSearchQuery("content:\"hello world\" archive:\"my notes.txt\"")
        assertEquals(listOf("hello world"), q.contentParts)
        assertEquals(listOf("my notes.txt"), q.archiveParts)
    }

    @Test
    fun mixedTokensKeepAndSemantics() {
        val q = parseSearchQuery("report ext:pdf content:\"quarterly\" archive:data")
        assertEquals(listOf("report"), q.nameParts)
        assertEquals(setOf("pdf"), q.extensions)
        assertEquals(listOf("quarterly"), q.contentParts)
        assertEquals(listOf("data"), q.archiveParts)
        assertFalse(q.isEmpty)
    }

    @Test
    fun emptyContentAndArchiveValuesFallBackToPlainText() {
        assertEquals(listOf("content:"), parseSearchQuery("content:").nameParts)
        assertEquals(listOf("archive:"), parseSearchQuery("archive:").nameParts)
    }

    @Test
    fun contentPartsStillNameFilterFastPath() {
        val q = parseSearchQuery("content:hello")
        assertTrue(q.matches("anything.txt", "txt", false, 1, 0))
    }
}
