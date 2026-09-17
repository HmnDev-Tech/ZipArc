package com.hmndev.ziparc

import android.app.Application
import com.hmndev.ziparc.data.elevation.ShizukuEngine

class ZipArcApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuEngine.init(this)
        try {
            System.loadLibrary("ziparc_rs")
        } catch (_: UnsatisfiedLinkError) {
        }
    }
}
