package com.kerneldroid.karchiver.data.elevation

import android.system.Os
import java.io.File
import java.nio.file.Files

class PrivilegedFSService : IPrivilegedFS.Stub() {

    companion object {
        private const val FAILURE = 1
        private const val SUCCESS = 0
        private const val FIELD_SEPARATOR = '\u0000'
        private const val PERMISSION_MASK = 0xFFF
    }

    override fun destroy() {
        try {
            System.exit(0)
        } catch (_: Throwable) {
        }
    }

    override fun listDir(path: String?): List<String>? {
        try {
            if (path.isNullOrEmpty()) return null
            val dir = File(path)
            if (!dir.isDirectory) return null
            val children = dir.listFiles() ?: return null
            return children.mapNotNull { encodeEntry(it) }
        } catch (_: Throwable) {
            return null
        }
    }

    override fun deleteAll(paths: List<String>?): Int {
        try {
            if (paths == null) return FAILURE
            for (raw in paths) {
                if (raw.isNullOrEmpty()) return FAILURE
                if (!removeRecursively(File(raw))) return FAILURE
            }
            return SUCCESS
        } catch (_: Throwable) {
            return FAILURE
        }
    }

    override fun makeDirs(path: String?): Int {
        try {
            if (path.isNullOrEmpty()) return FAILURE
            val dir = File(path)
            if (dir.isDirectory) return SUCCESS
            if (dir.mkdirs()) return SUCCESS
            return if (dir.isDirectory) SUCCESS else FAILURE
        } catch (_: Throwable) {
            return FAILURE
        }
    }

    override fun setMode(path: String?, mode: Int): Int {
        try {
            if (path.isNullOrEmpty()) return FAILURE
            Os.chmod(path, mode)
            return SUCCESS
        } catch (_: Throwable) {
            return FAILURE
        }
    }

    private fun encodeEntry(file: File): String? {
        try {
            val name = file.name
            if (name.isEmpty()) return null
            val dirFlag = if (file.isDirectory) "1" else "0"
            val mode = try {
                Os.stat(file.absolutePath).st_mode and PERMISSION_MASK
            } catch (_: Throwable) {
                0
            }
            return name + FIELD_SEPARATOR + dirFlag + FIELD_SEPARATOR + file.length() +
                FIELD_SEPARATOR + file.lastModified() + FIELD_SEPARATOR + mode
        } catch (_: Throwable) {
            return null
        }
    }

    private fun removeRecursively(file: File): Boolean {
        try {
            val link = try {
                Files.isSymbolicLink(file.toPath())
            } catch (_: Throwable) {
                false
            }
            if (!file.exists() && !link) return true
            if (file.isDirectory && !link) {
                val children = file.listFiles() ?: return false
                for (child in children) {
                    if (!removeRecursively(child)) return false
                }
            }
            return file.delete() || (!file.exists() && !link)
        } catch (_: Throwable) {
            return false
        }
    }
}
