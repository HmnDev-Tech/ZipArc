package com.kerneldroid.karchiver.data.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

data class AppVolume(
    val id: String,
    val label: String,
    val root: File,
    val isRemovable: Boolean,
    val isPrimary: Boolean
)

fun loadAppVolumes(context: Context): List<AppVolume> {
    try {
        val primaryRoot = Environment.getExternalStorageDirectory()
        val primary = AppVolume(
            id = "primary",
            label = "Internal storage",
            root = primaryRoot,
            isRemovable = false,
            isPrimary = true
        )
        val found = ArrayList<AppVolume>()
        found.add(primary)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val manager = context.getSystemService(StorageManager::class.java)
                val primaryPath = canonicalOf(primaryRoot)
                val volumes = try { manager?.storageVolumes } catch (_: Exception) { null }
                for (volume in volumes ?: emptyList()) {
                    try {
                        val state = volume.state
                        if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
                        @Suppress("DEPRECATION")
                        val dir = volume.directory ?: continue
                        val path = canonicalOf(dir)
                        if (path == primaryPath) continue
                        val uuid = try { volume.uuid } catch (_: Exception) { null }
                        val description = try { volume.getDescription(context) } catch (_: Exception) { null }
                        val removable = try { volume.isRemovable } catch (_: Exception) { true }
                        found.add(
                            AppVolume(
                                id = "vol-" + (uuid ?: dir.name),
                                label = description ?: dir.name,
                                root = dir,
                                isRemovable = removable,
                                isPrimary = false
                            )
                        )
                    } catch (_: Exception) {
                    }
                }
            } else {
                val primaryPath = canonicalOf(primaryRoot)
                val appDirs = try {
                    context.getExternalFilesDirs(null).filterNotNull()
                } catch (_: Exception) {
                    emptyList()
                }
                for (appDir in appDirs) {
                    try {
                        if (!appDir.absolutePath.contains("/Android/data/")) continue
                        val root = generateSequence<File>(appDir) { it.parentFile }.take(5).lastOrNull() ?: continue
                        val path = canonicalOf(root)
                        if (path == primaryPath) continue
                        if (!root.exists()) continue
                        found.add(
                            AppVolume(
                                id = "vol-" + root.name,
                                label = "SD card (" + root.name + ")",
                                root = root,
                                isRemovable = true,
                                isPrimary = false
                            )
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        val seen = HashSet<String>()
        val deduped = ArrayList<AppVolume>()
        for (volume in found.sortedByDescending { it.isPrimary }) {
            if (seen.add(canonicalOf(volume.root))) deduped.add(volume)
        }
        if (deduped.isEmpty()) return listOf(primary)
        return deduped
    } catch (_: Exception) {
        return try {
            listOf(
                AppVolume(
                    id = "primary",
                    label = "Internal storage",
                    root = Environment.getExternalStorageDirectory(),
                    isRemovable = false,
                    isPrimary = true
                )
            )
        } catch (_: Exception) {
            emptyList()
        }
    }
}

private fun canonicalOf(file: File): String {
    return try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
}
