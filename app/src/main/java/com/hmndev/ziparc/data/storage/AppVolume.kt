package com.hmndev.ziparc.data.storage

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

enum class VolumeKind {
    INTERNAL,
    SD_CARD,
    USB
}

fun classifyVolume(
    isPrimary: Boolean,
    isEmulated: Boolean,
    isRemovable: Boolean,
    usbMassStorageAttached: Boolean
): VolumeKind {
    if (isPrimary || isEmulated) return VolumeKind.INTERNAL
    if (isRemovable && usbMassStorageAttached) return VolumeKind.USB
    if (isRemovable) return VolumeKind.SD_CARD
    return VolumeKind.INTERNAL
}

fun kindForVoldMajor(major: Int): VolumeKind? {
    return try {
        when (major) {
            8 -> VolumeKind.USB
            178 -> VolumeKind.SD_CARD
            179 -> VolumeKind.SD_CARD
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

fun parseMountKinds(text: String): Map<String, VolumeKind> {
    try {
        if (text.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, VolumeKind>()
        val lines = try {
            text.split('\n')
        } catch (_: Exception) {
            return emptyMap()
        }
        for (raw in lines) {
            try {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (!line.startsWith("/dev/block/vold/public:")) continue
                if (!line.contains("/mnt/media_rw/")) continue
                val afterPrefix = line.substringAfter("/dev/block/vold/public:", "")
                if (afterPrefix.isEmpty()) continue
                val digits = StringBuilder()
                for (ch in afterPrefix) {
                    if (ch.isDigit()) digits.append(ch) else break
                }
                if (digits.isEmpty()) continue
                val major = try {
                    digits.toString().toInt()
                } catch (_: Exception) {
                    continue
                }
                val kind = try {
                    kindForVoldMajor(major)
                } catch (_: Exception) {
                    null
                } ?: continue
                val idx = line.indexOf("/mnt/media_rw/")
                if (idx < 0) continue
                val fromMount = line.substring(idx)
                val token = fromMount.split(' ', '\t').firstOrNull() ?: continue
                val trimmed = token.trimEnd('/')
                if (trimmed.isEmpty()) continue
                val volId = trimmed.substringAfterLast('/')
                if (volId.isEmpty()) continue
                result[volId] = kind
            } catch (_: Exception) {
            }
        }
        return result
    } catch (_: Exception) {
        return emptyMap()
    }
}

fun loadMountKinds(): Map<String, VolumeKind> {
    return try {
        val text = try {
            File("/proc/mounts").readText()
        } catch (_: Exception) {
            return emptyMap()
        }
        try {
            parseMountKinds(text)
        } catch (_: Exception) {
            emptyMap()
        }
    } catch (_: Exception) {
        emptyMap()
    }
}

fun hasUsbMassStorage(context: Context): Boolean {
    return try {
        val usbManager = try {
            context.getSystemService(UsbManager::class.java)
        } catch (_: Exception) {
            null
        } ?: return false
        val devices = try {
            usbManager.deviceList
        } catch (_: Exception) {
            return false
        } ?: return false
        for (entry in devices.entries) {
            try {
                val device = entry.value
                val count = try {
                    device.interfaceCount
                } catch (_: Exception) {
                    continue
                }
                for (index in 0 until count) {
                    try {
                        val iface = device.getInterface(index)
                        if (iface.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) return true
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }
        false
    } catch (_: Exception) {
        false
    }
}

data class AppVolume(
    val id: String,
    val label: String,
    val root: File,
    val isRemovable: Boolean,
    val isPrimary: Boolean,
    val kind: VolumeKind
)

fun loadAppVolumes(context: Context): List<AppVolume> {
    try {
        val usbAttached = try {
            hasUsbMassStorage(context)
        } catch (_: Exception) {
            false
        }
        val mountKinds = try {
            loadMountKinds()
        } catch (_: Exception) {
            emptyMap<String, VolumeKind>()
        }
        val primaryRoot = Environment.getExternalStorageDirectory()
        val primary = AppVolume(
            id = "primary",
            label = "Internal storage",
            root = primaryRoot,
            isRemovable = false,
            isPrimary = true,
            kind = classifyVolume(
                isPrimary = true,
                isEmulated = true,
                isRemovable = false,
                usbMassStorageAttached = usbAttached
            )
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
                        val emulated = try { volume.isEmulated } catch (_: Exception) { false }
                        val primaryFlag = try { volume.isPrimary } catch (_: Exception) { false }
                        val volId = try {
                            path.substringAfterLast('/').trimEnd('/')
                        } catch (_: Exception) {
                            ""
                        }
                        val mappedKind = try {
                            if (volId.isNotEmpty()) mountKinds[volId] else null
                        } catch (_: Exception) {
                            null
                        }
                        val resolvedKind = try {
                            if (!primaryFlag && !emulated && mappedKind != null) mappedKind
                            else classifyVolume(
                                isPrimary = primaryFlag,
                                isEmulated = emulated,
                                isRemovable = removable,
                                usbMassStorageAttached = usbAttached
                            )
                        } catch (_: Exception) {
                            classifyVolume(
                                isPrimary = primaryFlag,
                                isEmulated = emulated,
                                isRemovable = removable,
                                usbMassStorageAttached = usbAttached
                            )
                        }
                        found.add(
                            AppVolume(
                                id = "vol-" + (uuid ?: dir.name),
                                label = description ?: dir.name,
                                root = dir,
                                isRemovable = removable,
                                isPrimary = false,
                                kind = resolvedKind
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
                        val volId = try {
                            path.substringAfterLast('/').trimEnd('/')
                        } catch (_: Exception) {
                            ""
                        }
                        val mappedKind = try {
                            if (volId.isNotEmpty()) mountKinds[volId] else null
                        } catch (_: Exception) {
                            null
                        }
                        val resolvedKind = try {
                            if (mappedKind != null) mappedKind
                            else classifyVolume(
                                isPrimary = false,
                                isEmulated = false,
                                isRemovable = true,
                                usbMassStorageAttached = usbAttached
                            )
                        } catch (_: Exception) {
                            classifyVolume(
                                isPrimary = false,
                                isEmulated = false,
                                isRemovable = true,
                                usbMassStorageAttached = usbAttached
                            )
                        }
                        found.add(
                            AppVolume(
                                id = "vol-" + root.name,
                                label = "SD card (" + root.name + ")",
                                root = root,
                                isRemovable = true,
                                isPrimary = false,
                                kind = resolvedKind
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
                    isPrimary = true,
                    kind = VolumeKind.INTERNAL
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
