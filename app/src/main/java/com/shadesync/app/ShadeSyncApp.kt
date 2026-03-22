package com.shadesync.app

import android.app.Application
import android.util.Log

/**
 * Custom Application class to catch uncaught exceptions globally
 * and prevent silent crashes on launch.
 */
class ShadeSyncApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("ShadeSync", "UNCAUGHT EXCEPTION on thread ${thread.name}", throwable)
            // Let the default handler finish the process
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
