package com.kerneldroid.karchiver.data.elevation

import android.os.ParcelFileDescriptor
import java.io.File

data class ElevatedEntry(
    val file: File,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
    val mode: Int
)

interface ElevatedFS {
    suspend fun listFiles(dir: File): List<File>?
    suspend fun listDetailed(dir: File): List<ElevatedEntry>? {
        return listFiles(dir)?.map { file ->
            ElevatedEntry(
                file = file,
                isDirectory = file.isDirectory,
                size = if (file.isDirectory) 0 else file.length(),
                modified = file.lastModified(),
                mode = 0
            )
        }
    }
    suspend fun deleteRecursively(targets: List<File>): Boolean
    suspend fun mkdirs(dir: File): Boolean
    suspend fun chmod(path: File, mode: Int): Boolean
    suspend fun openReadFd(path: String): ParcelFileDescriptor? = null
    suspend fun openWriteFd(path: String): ParcelFileDescriptor? = null
    suspend fun copyInto(src: File, dst: File): Boolean = false
}
