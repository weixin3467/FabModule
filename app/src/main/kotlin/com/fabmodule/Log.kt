package com.fabmodule

/**
 * Logging — XposedBridge in WeChat process, android.util.Log fallback.
 * WARN and ERROR always output. INFO only in debug mode (prevent log fingerprinting).
 */
object Log {
    private const val TAG = "FabModule"
    fun i(msg: String) { log(msg) }
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
