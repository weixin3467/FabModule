package com.fabmodule

/**
 * Logging — XposedBridge in WeChat process, android.util.Log fallback.
 * WARN and ERROR always output. INFO only in debug mode (prevent log fingerprinting).
 */
object Log {
    private const val TAG = "FabModule"

    private val debug: Boolean by lazy {
        try { BuildConfig.DEBUG } catch (_: Throwable) { true }
    }

    fun i(msg: String) { if (debug) log(msg) }
    fun w(msg: String) { log("WARN: $msg") }
    fun e(msg: String) { log("ERROR: $msg") }
    fun e(msg: String, t: Throwable) { log("ERROR: $msg — ${t.message}") }

    private fun log(msg: String) {
        try {
            de.robv.android.xposed.XposedBridge.log("[$TAG] $msg")
        } catch (_: Throwable) {
            try { android.util.Log.i(TAG, msg) } catch (_: Throwable) {}
        }
    }
}
