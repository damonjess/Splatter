package com.example.splatter.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TEMPORARY diagnostic sink. On this device logcat's 256 KiB ring buffer is
 * flooded by ARCore's native logging and the app's own `Log.i` lines are
 * evicted before they can be read, so the renderer writes its state to a file
 * that can be pulled with `adb shell run-as`. Delete once the preview bug is
 * understood.
 */
object PreviewTrace {
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var file: File? = null
    @Volatile
    private var enabled = false

    fun init(context: Context, enable: Boolean) {
        enabled = enable
        if (!enable) return
        file = File(context.filesDir, "preview_trace.log").also {
            runCatching { it.writeText("") }
        }
    }

    fun log(tag: String, message: String) {
        if (!enabled) return
        val target = file ?: return
        runCatching {
            target.appendText("${format.format(Date())} [$tag] $message\n")
        }
    }
}
