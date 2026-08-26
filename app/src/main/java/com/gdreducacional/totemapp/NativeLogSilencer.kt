package com.gdreducacional.totemapp

import android.os.Build
import android.util.Log

object NativeLogSilencer {
    private val TAGS = arrayOf(
        "FaceDetectorV2Jni",
        "ThickFaceDetector",
        "FaceDetector",
        "NativeFaceDetector",
        "VisionFace",
        "StreamingFormatChecker",
        "MediaPlayerNative",
        "MediaPlayer-JNI",
        "MediaPlayer",
        "tflite",
        "tflitejni",
        "gralloc4"
    )

    init {
        runCatching { System.loadLibrary("logsilence") }
            .onFailure { Log.w("NativeLogSilencer", "liblogsilence: ${it.message}") }
    }

    fun install() {
        exemptHiddenApis()
        runCatching { nativeSetMinPriority(ANDROID_LOG_INFO) }
        val setter = systemPropertySetter()
        if (setter != null) {
            for (tag in TAGS) {
                val key = "log.tag.$tag"
                runCatching { setter.invoke(null, key, "SILENT") }
                runCatching { setter.invoke(null, key, "S") }
                runCatching { System.setProperty(key, "SILENT") }
            }
        }
    }

    private external fun nativeSetMinPriority(priority: Int)

    private fun exemptHiddenApis() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching {
            val vmRuntime = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntime.getDeclaredMethod("getRuntime").apply { isAccessible = true }
            val runtime = getRuntime.invoke(null)
            val setHiddenApiExemptions = vmRuntime.getDeclaredMethod(
                "setHiddenApiExemptions",
                Array<String>::class.java
            ).apply { isAccessible = true }
            setHiddenApiExemptions.invoke(runtime, arrayOf("L"))
        }.onFailure { error ->
            Log.w("NativeLogSilencer", "hidden-api exemption falhou: ${error.message}")
        }
    }

    private fun systemPropertySetter() = runCatching {
        Class.forName("android.os.SystemProperties")
            .getDeclaredMethod("set", String::class.java, String::class.java)
            .apply { isAccessible = true }
    }.getOrNull()

    private const val ANDROID_LOG_INFO = 4
}
