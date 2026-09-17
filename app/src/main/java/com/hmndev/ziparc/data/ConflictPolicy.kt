package com.hmndev.ziparc.data

import java.io.File

enum class ConflictPolicy { REPLACE, SKIP, KEEP_BOTH }

data class CopyItem(val source: File, val destName: String)

fun conflictsAmong(existingNames: Set<String>, candidates: List<String>): List<String> =
    candidates.filter { it.isNotEmpty() && existingNames.contains(it) }.distinct()

fun uniqueName(name: String, taken: Set<String>): String {
    if (name !in taken) return name
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var counter = 1
    while (counter < 9999) {
        val candidate = "$base ($counter)$ext"
        if (candidate !in taken) return candidate
        counter++
    }
    return name
}

fun buildCopyPlan(
    sources: List<File>,
    existingNames: Set<String>,
    policy: ConflictPolicy
): List<CopyItem> {
    val taken = existingNames.toMutableSet()
    return sources.mapNotNull { source ->
        when {
            source.name !in taken -> {
                taken.add(source.name)
                CopyItem(source, source.name)
            }
            policy == ConflictPolicy.REPLACE -> CopyItem(source, source.name)
            policy == ConflictPolicy.SKIP -> null
            else -> {
                val renamed = uniqueName(source.name, taken)
                taken.add(renamed)
                CopyItem(source, renamed)
            }
        }
    }
}

fun topLevelNames(entryNames: List<String>): List<String> = entryNames
    .mapNotNull { raw ->
        raw.trim().trimStart('/').substringBefore('/').takeIf { it.isNotEmpty() }
    }
    .distinct()
