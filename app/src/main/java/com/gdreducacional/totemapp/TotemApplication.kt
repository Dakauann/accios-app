package com.gdreducacional.totemapp

import android.app.Application
import android.content.Context
import com.gdreducacional.totemapp.services.FaceRecognitionService

class TotemApplication : Application() {
    override fun attachBaseContext(base: Context) {
        NativeLogSilencer.install()
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        NativeLogSilencer.install()
        FaceRecognitionService.silenceNativeLogs()
        super.onCreate()
    }
}
