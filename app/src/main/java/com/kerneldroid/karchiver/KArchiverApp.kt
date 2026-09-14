package com.kerneldroid.karchiver

import android.app.Application
import com.kerneldroid.karchiver.data.elevation.ShizukuEngine

class KArchiverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuEngine.init(this)
        try {
            System.loadLibrary("karchiver_rs")
        } catch (_: UnsatisfiedLinkError) {
        }
    }
}
