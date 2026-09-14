package com.kerneldroid.karchiver.data.elevation

import java.io.File

interface ElevatedFS {
    suspend fun listFiles(dir: File): List<File>?
    suspend fun deleteRecursively(targets: List<File>): Boolean
    suspend fun mkdirs(dir: File): Boolean
    suspend fun chmod(path: File, mode: Int): Boolean
}
