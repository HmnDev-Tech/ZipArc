package com.kerneldroid.karchiver.data.elevation

import java.io.File

enum class Cap { READ_PROTECTED, WRITE_PROTECTED }

fun capsFor(mode: String): Set<Cap> = when (mode) {
    "root" -> setOf(Cap.READ_PROTECTED, Cap.WRITE_PROTECTED)
    "shizuku" -> setOf(Cap.READ_PROTECTED)
    else -> emptySet()
}

private val SHIZUKU_BLOCKED_PREFIXES = listOf("/data/data/", "/data/user/")

fun isShizukuBlocked(path: String): Boolean {
    val absolute = File(path).absolutePath
    if (absolute == "/data/data" || absolute == "/data/user") return true
    return SHIZUKU_BLOCKED_PREFIXES.any { absolute.startsWith(it) }
}

fun requireCaps(path: File, mode: String) {
    if (mode != "shizuku") return
    if (isShizukuBlocked(path.absolutePath)) {
        error("Shizuku cannot access app-private data (SELinux policy). Use Root.")
    }
}
