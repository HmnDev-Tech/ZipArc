package com.kerneldroid.karchiver.data.history

import com.kerneldroid.karchiver.data.search.parseSearchQuery
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryFilterTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val now = 1_750_000_000_000L
    private val hour = 3_600_000L
    private val day = 86_400_000L

    private fun entry(
        path: String,
        name: String = path.substringAfterLast('/'),
        isDirectory: Boolean = true,
        extension: String = "",
        size: Long = 0L,
        age: Long = 0L,
        visits: Int = 1
    ) = HistoryEntry(
        path = path,
        name = name,
        isDirectory = isDirectory,
        extension = extension,
        size = size,
        lastVisitedAt = now - age,
        visitCount = visits
    )

    private fun filter(
        entries: List<HistoryEntry>,
        query: String = "",
        type: HistoryTypeFilter = HistoryTypeFilter.ALL,
        newestFirst: Boolean = true
    ) = filterHistory(entries, parseSearchQuery(query, now), type, newestFirst)

    @Test
    fun filtersByType() {
        val folder = entry("/sdcard/Download")
        val file = entry("/sdcard/Download/report.pdf", isDirectory = false, extension = "pdf", size = 2048)

        assertEquals(listOf(folder, file), filter(listOf(folder, file), type = HistoryTypeFilter.ALL))
        assertEquals(listOf(folder), filter(listOf(folder, file), type = HistoryTypeFilter.FOLDERS))
        assertEquals(listOf(file), filter(listOf(folder, file), type = HistoryTypeFilter.FILES))
    }

    @Test
    fun filtersByExtension() {
        val pdf = entry("/sdcard/a.pdf", isDirectory = false, extension = "pdf", size = 10)
        val zip = entry("/sdcard/a.zip", isDirectory = false, extension = "zip", size = 10)

        assertEquals(listOf(pdf), filter(listOf(pdf, zip), query = "ext:pdf"))
        assertEquals(listOf(zip), filter(listOf(pdf, zip), query = "format:ZIP"))
    }

    @Test
    fun filtersBySizeAndName() {
        val small = entry("/sdcard/small.txt", isDirectory = false, extension = "txt", size = 1_000)
        val big = entry("/sdcard/big.bin", isDirectory = false, extension = "bin", size = 5_000_000)

        assertEquals(listOf(big), filter(listOf(small, big), query = "size:>1MB"))
        assertEquals(listOf(small), filter(listOf(small, big), query = "n:small"))
        assertTrue(filter(listOf(small, big), query = "n:missing").isEmpty())
    }

    @Test
    fun filtersByVisitDate() {
        val recent = entry("/sdcard/recent", age = hour)
        val old = entry("/sdcard/old", age = 30 * day)

        assertEquals(listOf(recent, old), filter(listOf(recent, old), query = "date:>=2020-01-01"))
        assertTrue(filter(listOf(recent, old), query = "date:<2019-01-01").isEmpty())
    }

    @Test
    fun sortsByRecency() {
        val older = entry("/sdcard/older", age = 10 * day)
        val newer = entry("/sdcard/newer", age = day)

        assertEquals(listOf(newer, older), filter(listOf(older, newer)))
        assertEquals(listOf(older, newer), filter(listOf(older, newer), newestFirst = false))
    }

    @Test
    fun groupsIntoSections() {
        val today = entry("/sdcard/today", age = hour)
        val yesterday = entry("/sdcard/yesterday", age = day + hour)
        val earlier = entry("/sdcard/earlier", age = 20 * day)

        val sections = groupHistory(listOf(earlier, today, yesterday), now, zone)

        assertEquals(3, sections.size)
        assertEquals(HistorySection.TODAY, sections[0].first)
        assertEquals(listOf(today), sections[0].second)
        assertEquals(HistorySection.YESTERDAY, sections[1].first)
        assertEquals(listOf(yesterday), sections[1].second)
        assertEquals(HistorySection.EARLIER, sections[2].first)
        assertEquals(listOf(earlier), sections[2].second)
    }

    @Test
    fun omitsEmptySections() {
        val sections = groupHistory(listOf(entry("/sdcard/today", age = hour)), now, zone)

        assertEquals(1, sections.size)
        assertEquals(HistorySection.TODAY, sections[0].first)
    }

    @Test
    fun formatsRelativeTime() {
        assertEquals("Just now", relativeTime(now - 5_000L, now, zone))
        assertEquals("3 min ago", relativeTime(now - 3 * 60_000L, now, zone))
        assertEquals("2 h ago", relativeTime(now - 2 * hour, now, zone))
        assertFalse(relativeTime(now - 20 * day, now, zone).isBlank())
    }
}
