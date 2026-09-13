package com.kerneldroid.karchiver.data

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File

data class VolumeStats(
    val label: String,
    val path: String,
    val freeBytes: Long,
    val totalBytes: Long
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
    val usedFraction: Float get() = if (totalBytes > 0) usedBytes.toFloat() / totalBytes else 0f
}

fun loadVolumeStats(context: Context): List<VolumeStats> {
    val volumes = mutableListOf<VolumeStats>()
    val internalRoot = Environment.getExternalStorageDirectory()
    statOf("Internal storage", internalRoot)?.let { volumes += it }
    context.getExternalFilesDirs(null)
        .filterNotNull()
        .mapNotNull { dir ->
            generateSequence<File>(dir) { it.parentFile }.take(5).lastOrNull()
        }
        .distinctBy { it.absolutePath }
        .filter { it.absolutePath != internalRoot.absolutePath }
        .forEach { root ->
            statOf("SD card (${root.name})", root)?.let { volumes += it }
        }
    return volumes
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}

private fun statOf(label: String, root: File): VolumeStats? {
    return try {
        if (!root.exists()) return null
        val stat = StatFs(root.absolutePath)
        VolumeStats(label, root.absolutePath, stat.availableBytes, stat.totalBytes)
    } catch (_: Exception) {
        null
    }
}
