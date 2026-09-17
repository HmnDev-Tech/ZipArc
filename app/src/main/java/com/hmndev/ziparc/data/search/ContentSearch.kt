package com.hmndev.ziparc.data.search

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader

data class ContentMatch(
    val line: Int,
    val snippet: String
)

class ContentScanner(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val caseSensitive: Boolean = false,
    private val maxSnippet: Int = SNIPPET_LENGTH
) {

    fun scan(input: InputStream, needle: String): ContentMatch? = scan(input, listOf(needle))

    fun scan(input: InputStream, needles: List<String>): ContentMatch? {
        val capped = CapInputStream(input, maxBytes)
        val buffered = BufferedInputStream(capped, READ_CHUNK)
        if (isBinary(buffered)) return null
        return InputStreamReader(buffered, Charsets.UTF_8).use { scan(it, needles) }
    }

    fun scanText(text: String, needle: String): ContentMatch? = scanText(text, listOf(needle))

    fun scanText(text: String, needles: List<String>): ContentMatch? {
        val probe = text.take(BINARY_PROBE)
        if (probe.indexOf('\u0000') >= 0) return null
        return scan(StringReader(text), needles)
    }

    fun scan(reader: Reader, needles: List<String>): ContentMatch? {
        val targets = needles
            .map { if (caseSensitive) it else it.lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (targets.isEmpty()) return null
        val maxNeedle = targets.maxOf { it.length }
        val found = BooleanArray(targets.size)
        var foundCount = 0
        var line = 1
        var first: ContentMatch? = null
        val buffer = StringBuilder()
        val chunk = CharArray(READ_CHUNK)
        while (true) {
            val n = reader.read(chunk)
            if (n < 0) break
            buffer.append(chunk, 0, n)
            while (true) {
                val newline = buffer.indexOf("\n")
                if (newline < 0) break
                val lineText = buffer.substring(0, newline)
                buffer.delete(0, newline + 1)
                val hit = matchLine(lineText, line, targets, found)
                if (hit != null) {
                    if (first == null) first = hit
                    foundCount = found.count { it }
                    if (foundCount == targets.size) return first
                }
                line++
            }
            if (buffer.length > MAX_LINE_CHARS) {
                val lineText = buffer.toString()
                val hit = matchLine(lineText, line, targets, found)
                if (hit != null) {
                    if (first == null) first = hit
                    foundCount = found.count { it }
                    if (foundCount == targets.size) return first
                }
                val keep = (maxNeedle - 1).coerceAtLeast(0)
                if (buffer.length > keep) buffer.delete(0, buffer.length - keep)
            }
        }
        if (buffer.isNotEmpty()) {
            val hit = matchLine(buffer.toString(), line, targets, found)
            if (hit != null && first == null) first = hit
            foundCount = found.count { it }
        }
        return if (foundCount == targets.size) first else null
    }

    private fun matchLine(
        lineText: String,
        lineNumber: Int,
        targets: List<String>,
        found: BooleanArray
    ): ContentMatch? {
        val hay = if (caseSensitive) lineText else lineText.lowercase()
        var snippet: String? = null
        for (i in targets.indices) {
            if (found[i]) continue
            val at = hay.indexOf(targets[i])
            if (at >= 0) {
                found[i] = true
                if (snippet == null) snippet = snippet(lineText, at)
            }
        }
        return snippet?.let { ContentMatch(lineNumber, it) }
    }

    private fun snippet(text: String, at: Int): String {
        var start = (at - maxSnippet / 3).coerceAtLeast(0)
        var end = (start + maxSnippet).coerceAtMost(text.length)
        if (end - start < maxSnippet) {
            start = (end - maxSnippet).coerceAtLeast(0)
            end = (start + maxSnippet).coerceAtMost(text.length)
        }
        val prefix = if (start > 0) "\u2026" else ""
        val suffix = if (end < text.length) "\u2026" else ""
        return prefix + text.substring(start, end).trim() + suffix
    }

    private fun isBinary(input: BufferedInputStream): Boolean {
        input.mark(BINARY_PROBE + 1)
        val probe = ByteArray(BINARY_PROBE)
        var total = 0
        while (total < probe.size) {
            val n = input.read(probe, total, probe.size - total)
            if (n < 0) break
            total += n
        }
        input.reset()
        for (i in 0 until total) if (probe[i] == 0.toByte()) return true
        return false
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 5L * 1024 * 1024
        private const val READ_CHUNK = 8192
        private const val BINARY_PROBE = 8192
        private const val MAX_LINE_CHARS = 256 * 1024
        private const val SNIPPET_LENGTH = 160
    }
}

private class CapInputStream(
    private val source: InputStream,
    private var remaining: Long
) : InputStream() {

    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = source.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val cap = minOf(len.toLong(), remaining).toInt()
        val n = source.read(b, off, cap)
        if (n > 0) remaining -= n
        return n
    }

    override fun close() {
        source.close()
    }
}
