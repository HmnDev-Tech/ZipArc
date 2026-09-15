package com.kerneldroid.karchiver.data.search

import com.kerneldroid.karchiver.data.FileItem
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class SearchQuery(
    val nameParts: List<String> = emptyList(),
    val extensions: Set<String> = emptySet(),
    val modifiedAfter: Long? = null,
    val modifiedBefore: Long? = null,
    val sizeMin: Long? = null,
    val sizeMax: Long? = null,
    val dirsOnly: Boolean = false,
    val filesOnly: Boolean = false
) {
    val isEmpty: Boolean get() =
        nameParts.isEmpty() && extensions.isEmpty() && modifiedAfter == null &&
            modifiedBefore == null && sizeMin == null && sizeMax == null &&
            !dirsOnly && !filesOnly

    fun matches(
        name: String,
        extension: String,
        isDirectory: Boolean,
        size: Long,
        lastModified: Long
    ): Boolean {
        if (dirsOnly && !isDirectory) return false
        if (filesOnly && isDirectory) return false
        if (extensions.isNotEmpty() && (isDirectory || extension.lowercase() !in extensions)) return false
        for (part in nameParts) {
            if (!name.contains(part, ignoreCase = true)) return false
        }
        modifiedAfter?.let { if (lastModified < it) return false }
        modifiedBefore?.let { if (lastModified >= it) return false }
        if (!isDirectory) {
            sizeMin?.let { if (size < it) return false }
            sizeMax?.let { if (size > it) return false }
        }
        return true
    }
}

fun parseSearchQuery(raw: String, now: Long = System.currentTimeMillis()): SearchQuery {
    var q = SearchQuery()
    if (raw.isBlank()) return q
    val names = mutableListOf<String>()
    val exts = mutableSetOf<String>()
    for (token in splitTokens(raw)) {
        if (token.isBlank()) continue
        val colon = token.indexOf(':')
        if (colon > 0) {
            val key = token.substring(0, colon).lowercase()
            val value = unquote(token.substring(colon + 1))
            when (key) {
                "name", "n" -> if (value.isNotEmpty()) names.add(value)
                "ext", "format", "f" -> value.split(',', ';', '|', ' ')
                    .map { it.trim().trimStart('.').lowercase() }
                    .filter { it.isNotEmpty() }
                    .forEach { exts.add(it) }
                "date", "d" -> {
                    val applied = applyDate(q, value, now)
                    if (applied != null) q = applied else names.add(token)
                }
                "size", "s" -> {
                    val applied = applySize(q, value)
                    if (applied != null) q = applied else names.add(token)
                }
                "type", "is" -> when (value.lowercase()) {
                    "dir", "directory", "folder" -> q = q.copy(dirsOnly = true)
                    "file" -> q = q.copy(filesOnly = true)
                    else -> names.add(token)
                }
                else -> names.add(token)
            }
        } else {
            names.add(unquote(token))
        }
    }
    return q.copy(nameParts = names.filter { it.isNotEmpty() }, extensions = exts)
}

fun FileItem.matchesSearch(query: SearchQuery): Boolean =
    query.matches(name, extension, isDirectory, size, lastModified)

fun splitTokens(raw: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var inQuotes = false
    for (c in raw.trim()) {
        when {
            c == '"' -> inQuotes = !inQuotes
            c.isWhitespace() && !inQuotes -> {
                if (cur.isNotEmpty()) {
                    out.add(cur.toString())
                    cur.clear()
                }
            }
            else -> cur.append(c)
        }
    }
    if (cur.isNotEmpty()) out.add(cur.toString())
    return out
}

fun unquote(s: String): String {
    val t = s.trim()
    return if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) t.substring(1, t.length - 1) else t
}

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

fun applyDate(q: SearchQuery, value: String, now: Long): SearchQuery? {
    val zone = ZoneId.systemDefault()
    val v = value.trim().lowercase()
    if (v.isEmpty()) return null
    if (v == "today") {
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        return q.copy(modifiedAfter = start, modifiedBefore = minOf(now + 1, start + 86_400_000))
    }
    if (v == "yesterday") {
        val start = LocalDate.now(zone).minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return q.copy(modifiedAfter = start, modifiedBefore = start + 86_400_000)
    }
    val range = v.split("..")
    if (range.size == 2) {
        val from = dayStart(range[0].trim(), zone) ?: return null
        val to = dayEnd(range[1].trim(), zone) ?: return null
        if (to <= from) return null
        return q.copy(modifiedAfter = from, modifiedBefore = to)
    }
    val op = when {
        v.startsWith(">=") -> ">=" to v.drop(2).trim()
        v.startsWith("<=") -> "<=" to v.drop(2).trim()
        v.startsWith(">") -> ">" to v.drop(1).trim()
        v.startsWith("<") -> "<" to v.drop(1).trim()
        else -> null
    }
    if (op != null) {
        val (symbol, operand) = op
        val start = dayStart(operand, zone) ?: return null
        val end = dayEnd(operand, zone) ?: return null
        return when (symbol) {
            ">" -> q.copy(modifiedAfter = end)
            ">=" -> q.copy(modifiedAfter = start)
            "<" -> q.copy(modifiedBefore = start)
            else -> q.copy(modifiedBefore = end)
        }
    }
    val start = dayStart(v, zone) ?: return null
    val end = dayEnd(v, zone) ?: return null
    return q.copy(modifiedAfter = start, modifiedBefore = end)
}

fun dayStart(value: String, zone: ZoneId): Long? {
    try {
        return LocalDate.parse(value, dateFormatter).atStartOfDay(zone).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
    }
    try {
        return LocalDate.parse(value + "-01", dateFormatter).atStartOfDay(zone).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
    }
    try {
        val month = java.time.YearMonth.parse(value, monthFormatter)
        return month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
    }
    return null
}

fun dayEnd(value: String, zone: ZoneId): Long? {
    val start = dayStart(value, zone) ?: return null
    return try {
        LocalDate.parse(value, dateFormatter).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        try {
            val month = java.time.YearMonth.parse(value, monthFormatter)
            month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            start + 86_400_000L
        }
    }
}

private val sizeRegex = Regex("""^([<>]=?)?\s*([\d.]+)\s*([kmgt]?i?b?)?$""", RegexOption.IGNORE_CASE)

fun parseSizeBytes(value: String): Long? {
    val m = sizeRegex.matchEntire(value.trim()) ?: return null
    val amount = m.groupValues[2].toDoubleOrNull() ?: return null
    if (amount < 0) return null
    val mult = when (m.groupValues[3].lowercase()) {
        "", "b" -> 1L
        "k", "kb", "kib" -> 1024L
        "m", "mb", "mib" -> 1024L * 1024L
        "g", "gb", "gib" -> 1024L * 1024L * 1024L
        "t", "tb", "tib" -> 1024L * 1024L * 1024L * 1024L
        else -> return null
    }
    return (amount * mult).toLong()
}

fun applySize(q: SearchQuery, value: String): SearchQuery? {
    val v = value.trim()
    if (v.isEmpty()) return null
    val range = v.split("..")
    if (range.size == 2) {
        val lo = parseSizeBytes(range[0].trim()) ?: return null
        val hi = parseSizeBytes(range[1].trim()) ?: return null
        if (hi < lo) return null
        return q.copy(sizeMin = lo, sizeMax = hi)
    }
    val op = when {
        v.startsWith(">=") -> ">=" to v.drop(2).trim()
        v.startsWith("<=") -> "<=" to v.drop(2).trim()
        v.startsWith(">") -> ">" to v.drop(1).trim()
        v.startsWith("<") -> "<" to v.drop(1).trim()
        else -> null
    }
    if (op != null) {
        val amount = parseSizeBytes(op.second) ?: return null
        return when (op.first) {
            ">" -> q.copy(sizeMin = amount + 1)
            ">=" -> q.copy(sizeMin = amount)
            "<" -> q.copy(sizeMax = amount - 1)
            else -> q.copy(sizeMax = amount)
        }
    }
    val exact = parseSizeBytes(v) ?: return null
    return q.copy(sizeMin = exact, sizeMax = exact)
}
