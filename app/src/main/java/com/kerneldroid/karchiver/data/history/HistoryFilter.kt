package com.kerneldroid.karchiver.data.history

import com.kerneldroid.karchiver.data.search.SearchQuery
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class HistoryTypeFilter { ALL, FOLDERS, FILES }

enum class HistorySection { TODAY, YESTERDAY, EARLIER }

private val dayFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

fun SearchQuery.forHistory(filter: HistoryTypeFilter): SearchQuery = when (filter) {
    HistoryTypeFilter.ALL -> this
    HistoryTypeFilter.FOLDERS -> copy(dirsOnly = true, filesOnly = false)
    HistoryTypeFilter.FILES -> copy(filesOnly = true, dirsOnly = false)
}

fun SearchQuery.matches(entry: HistoryEntry): Boolean =
    matches(entry.name, entry.extension, entry.isDirectory, entry.size, entry.lastVisitedAt)

fun filterHistory(
    entries: List<HistoryEntry>,
    query: SearchQuery,
    typeFilter: HistoryTypeFilter,
    newestFirst: Boolean
): List<HistoryEntry> {
    val effective = query.forHistory(typeFilter)
    val filtered = if (effective.isEmpty) entries else entries.filter { effective.matches(it) }
    return if (newestFirst) {
        filtered.sortedByDescending { it.lastVisitedAt }
    } else {
        filtered.sortedBy { it.lastVisitedAt }
    }
}

fun sectionFor(timestamp: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): HistorySection {
    val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (date) {
        today -> HistorySection.TODAY
        today.minusDays(1) -> HistorySection.YESTERDAY
        else -> HistorySection.EARLIER
    }
}

fun groupHistory(
    entries: List<HistoryEntry>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault()
): List<Pair<HistorySection, List<HistoryEntry>>> {
    val buckets = LinkedHashMap<HistorySection, MutableList<HistoryEntry>>()
    entries.forEach { entry ->
        buckets.getOrPut(sectionFor(entry.lastVisitedAt, now, zone)) { mutableListOf() }.add(entry)
    }
    return enumValues<HistorySection>().mapNotNull { section ->
        buckets[section]?.let { section to it }
    }
}

fun relativeTime(timestamp: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val age = now - timestamp
    return when {
        age < 60_000L -> "Just now"
        age < 3_600_000L -> "${age / 60_000L} min ago"
        age < 86_400_000L -> "${age / 3_600_000L} h ago"
        else -> Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate().format(dayFormatter)
    }
}
