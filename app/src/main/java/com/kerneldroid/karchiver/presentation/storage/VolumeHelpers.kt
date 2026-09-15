package com.kerneldroid.karchiver.presentation.storage

import com.kerneldroid.karchiver.data.storage.AppVolume
import java.io.File

fun File.isWithin(root: File): Boolean {
    val base = root.absolutePath.trimEnd('/')
    return absolutePath == base || absolutePath.startsWith(base + "/")
}

fun deepestVolumeFor(dir: File, volumes: List<AppVolume>): AppVolume? {
    return volumes
        .filter { dir.isWithin(it.root) }
        .maxByOrNull { it.root.absolutePath.length }
}
