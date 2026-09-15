package com.kerneldroid.karchiver.data.storage

import android.content.Context
import android.net.Uri
import com.kerneldroid.karchiver.data.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SafBridge(private val context: Context, private val grants: SafGrants) {

    private val appContext = context.applicationContext

    fun needsStaging(file: File): Boolean {
        return try { !file.canRead() } catch (_: Exception) { true }
    }

    fun needsWriteStaging(dir: File): Boolean {
        return try { !dir.canWrite() } catch (_: Exception) { true }
    }

    suspend fun hasGrantFor(dir: File, volumes: List<AppVolume>): Boolean {
        return try { bindingFor(dir, volumes) != null } catch (_: Exception) { false }
    }

    suspend fun safFirstReady(dir: File, volumes: List<AppVolume>, preferred: Set<String>): Boolean {
        if (preferred.isEmpty()) return false
        return try {
            val id = grants.volumeIdFor(dir, volumes) ?: return false
            if (id !in preferred) return false
            hasGrantFor(dir, volumes)
        } catch (_: Exception) {
            false
        }
    }

    suspend fun itemsFor(dir: File, volumes: List<AppVolume>): List<FileItem>? =
        withContext(Dispatchers.IO) {
            try {
                val binding = bindingFor(dir, volumes) ?: return@withContext null
                val entries = SafFs.listEntries(appContext, binding.second, binding.third)
                    ?: return@withContext null
                entries.map { entry ->
                    FileItem(
                        file = File(dir, entry.name),
                        name = entry.name,
                        isDirectory = entry.isDir,
                        extension = if (entry.isDir) "" else entry.name.substringAfterLast('.', "").lowercase(),
                        size = if (entry.isDir) 0L else entry.size,
                        lastModified = entry.modified
                    )
                }
            } catch (_: Exception) {
                null
            }
        }

    suspend fun deleteTargets(files: List<File>, volumes: List<AppVolume>): Boolean =
        withContext(Dispatchers.IO) {
            try {
                for (file in files) {
                    val binding = bindingFor(file, volumes) ?: return@withContext false
                    if (binding.third.isEmpty()) return@withContext false
                    if (!SafFs.deleteRecursively(appContext, binding.second, binding.third)) {
                        return@withContext false
                    }
                }
                true
            } catch (_: Exception) {
                false
            }
        }

    suspend fun makeDir(parent: File, name: String, volumes: List<AppVolume>): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val trimmed = name.trim()
                if (trimmed.isEmpty() || trimmed.contains('/')) return@withContext false
                val binding = bindingFor(parent, volumes) ?: return@withContext false
                val rel = if (binding.third.isEmpty()) trimmed else binding.third + "/" + trimmed
                SafFs.makeDirs(appContext, binding.second, rel)
            } catch (_: Exception) {
                false
            }
        }

    suspend fun createFile(parent: File, name: String, volumes: List<AppVolume>): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val trimmed = name.trim()
                if (trimmed.isEmpty() || trimmed.contains('/')) return@withContext false
                val binding = bindingFor(parent, volumes) ?: return@withContext false
                SafFs.createFile(appContext, binding.second, binding.third, trimmed)
            } catch (_: Exception) {
                false
            }
        }

    suspend fun copyInTree(
        sources: List<File>,
        destDir: File,
        move: Boolean,
        volumes: List<AppVolume>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val binding = bindingFor(destDir, volumes) ?: return@withContext false
            for (src in sources) {
                if (!src.exists()) return@withContext false
                if (!SafFs.copyIn(appContext, binding.second, binding.third, src)) {
                    return@withContext false
                }
            }
            if (move) {
                for (src in sources) {
                    try {
                        val removed = if (src.isDirectory) src.deleteRecursively() else src.delete()
                        if (!removed && src.exists()) return@withContext false
                    } catch (_: Exception) {
                        return@withContext false
                    }
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun stageInTree(src: File, volumes: List<AppVolume>, staging: File): File? =
        withContext(Dispatchers.IO) {
            try {
                if (src.canRead()) return@withContext src
                val binding = bindingFor(src, volumes) ?: return@withContext null
                if (binding.third.isEmpty()) return@withContext null
                staging.mkdirs()
                if (!staging.isDirectory) return@withContext null
                val dst = uniqueFile(staging, src.name.ifEmpty { "staged" })
                if (!SafFs.copyOut(appContext, binding.second, binding.third, dst)) {
                    try { dst.deleteRecursively() } catch (_: Exception) {
                    }
                    return@withContext null
                }
                dst
            } catch (_: Exception) {
                null
            }
        }

    suspend fun stageArchiveIn(archive: File, volumes: List<AppVolume>, staging: File): File? {
        return try {
            stageInTree(archive, volumes, staging)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun stageExtractOut(stagedDir: File, destDir: File, volumes: List<AppVolume>): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (destDir.canWrite()) return@withContext true
                val binding = bindingFor(destDir, volumes) ?: return@withContext true
                if (!stagedDir.isDirectory || !stagedDir.canRead()) return@withContext true
                val kids = stagedDir.listFiles() ?: return@withContext true
                for (kid in kids) {
                    if (!SafFs.copyIn(appContext, binding.second, binding.third, kid)) {
                        return@withContext true
                    }
                }
                false
            } catch (_: Exception) {
                true
            }
        }

    private suspend fun bindingFor(file: File, volumes: List<AppVolume>): Triple<AppVolume, Uri, String>? {
        return try {
            val volumeId = grants.volumeIdFor(file, volumes) ?: return null
            val volume = volumes.firstOrNull { it.id == volumeId } ?: return null
            val treeUri = grants.grantFor(volumeId) ?: return null
            val rel = relativeToVolume(file, volume) ?: return null
            Triple(volume, treeUri, rel)
        } catch (_: Exception) {
            null
        }
    }

    private fun relativeToVolume(file: File, volume: AppVolume): String? {
        return try {
            val root = try { volume.root.canonicalPath } catch (_: Exception) { volume.root.absolutePath }
            val target = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
            val base = root.trimEnd('/')
            if (target == base) "" else if (target.startsWith(base + "/")) {
                target.substring(base.length + 1)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var counter = 1
        while (candidate.exists() && counter < 9999) {
            counter++
            candidate = File(dir, base + "-" + counter + ext)
        }
        return candidate
    }
}
