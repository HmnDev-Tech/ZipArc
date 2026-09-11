package com.kerneldroid.karchiver

import android.app.Application

class KArchiverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            System.loadLibrary("karchiver_rs")
        } catch (_: UnsatisfiedLinkError) {
        }
    }
}
