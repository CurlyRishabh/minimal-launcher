package com.minimal.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import java.util.concurrent.Executors

/**
 * The notes page is one plain-text file in app-private storage: no permissions,
 * nothing leaves the phone. Disk I/O happens on a background thread, created on first use.
 */
object NoteStore {

    private const val FILE = "notes.txt"

    private val executor by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "notes").apply { priority = Thread.MIN_PRIORITY } }
    }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun load(context: Context, onLoaded: (String) -> Unit) {
        val file = file(context)
        executor.execute {
            val text = try {
                String(file.readFully(), Charsets.UTF_8)
            } catch (_: Exception) {
                "" // No notes yet.
            }
            main.post { onLoaded(text) }
        }
    }

    fun save(context: Context, text: String) {
        val file = file(context)
        executor.execute {
            val out = try {
                file.startWrite()
            } catch (_: Exception) {
                return@execute
            }
            try {
                out.write(text.toByteArray(Charsets.UTF_8))
                file.finishWrite(out)
            } catch (_: Exception) {
                file.failWrite(out)
            }
        }
    }

    private fun file(context: Context) = AtomicFile(context.applicationContext.getFileStreamPath(FILE))
}
