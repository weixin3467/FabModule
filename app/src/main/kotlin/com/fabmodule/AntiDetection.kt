package com.fabmodule

import android.app.ActivityManager
import android.content.pm.PackageInfo
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Anti-detection layer — hides FabModule / Xposed presence from WeChat.
 *
 * Detection vectors covered (referenced from X.apk, WAuxiliary, MDWechat):
 * 1. Thread.getStackTrace / Throwable.getStackTrace — filter LSPosed/Xposed class names
 * 2. ActivityManager.getRunningAppProcesses — hide our process entries
 * 3. PackageManager.getInstalledApplications — filter module package name
 * 4. PackageManager.getInstalledPackages — filter module package
 * 5. PackageManager.queryIntentActivities — hide ResolveInfo
 * 6. ActivityManager.getRunningServices — hide services
 * 7. System.getProperty / System.getenv — block Xposed environment variable leaks
 * 8. FileInputStream("/proc/self/maps") — filter exposed shared-library entries
 */
object AntiDetection {

    private val hidePatterns = listOf(
        "com.fabmodule",
        "de.robv.android.xposed",
        "org.lsposed",
        "top.canyie.dreamland",
        "com.elderdrivers.riru"
    )

    /** Check if a name should be hidden from WeChat queries. */
    fun shouldHide(name: String?): Boolean {
        if (name == null || name.isEmpty()) return false
        val lower = name.lowercase()
        return hidePatterns.any { lower.contains(it) }
    }

    /**
     * Install all anti-detection hooks. Must be called from Entry.handleLoadPackage
     * with a valid classLoader and XC_LoadPackage param.
     */
    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        var installed = 0

        // 1. Stack trace filter
        try {
            for (cn in listOf("java.lang.Thread", "java.lang.Throwable")) {
                XposedHelpers.findAndHookMethod(
                    lpparam.classLoader.loadClass(cn),
                    "getStackTrace",
                    object : de.robv.android.xposed.XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val arr = param.result as? Array<StackTraceElement> ?: return
                            if (arr.isEmpty()) return
                            val filtered = arr.filter { !shouldHide(it.className) }
                            if (filtered.size != arr.size) param.result = filtered.toTypedArray()
                        }
                    })
            }
            installed++; Log.i("AntiDetect: 1.stacktrace OK")
        } catch (e: Exception) { Log.w("AntiDetect: stack ${e.message}") }

        // 2. getRunningAppProcesses
        try {
            XposedHelpers.findAndHookMethod(
                ActivityManager::class.java, "getRunningAppProcesses",
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        @Suppress("UNCHECKED_CAST")
                        val list = param.result as? List<ActivityManager.RunningAppProcessInfo>
                            ?: return
                        if (list.isEmpty()) return
                        val filtered = list.filter { !shouldHide(it.processName) }
                        if (filtered.size != list.size) param.result = filtered
                    }
                })
            installed++; Log.i("AntiDetect: 2.processlist OK")
        } catch (e: Exception) { Log.w("AntiDetect: process ${e.message}") }

        // 3. getInstalledApplications
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager",
                lpparam.classLoader, "getInstalledApplications", Int::class.java,
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        @Suppress("UNCHECKED_CAST")
                        val list = param.result as? List<android.content.pm.ApplicationInfo>
                            ?: return
                        if (list.isEmpty()) return
                        val filtered = list.filter { info ->
                            !shouldHide(info.packageName) &&
                            !shouldHide(info.className) &&
                            !shouldHide(info.processName)
                        }
                        if (filtered.size != list.size) param.result = filtered
                    }
                })
            installed++; Log.i("AntiDetect: 3.installedapps OK")
        } catch (e: Exception) { Log.w("AntiDetect: apps ${e.message}") }

        // 4. getInstalledPackages
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager",
                lpparam.classLoader, "getInstalledPackages", Int::class.java,
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        @Suppress("UNCHECKED_CAST")
                        val list = param.result as? List<PackageInfo> ?: return
                        if (list.isEmpty()) return
                        val filtered = list.filter { pkg ->
                            !shouldHide(pkg.packageName) &&
                            !shouldHide(pkg.applicationInfo?.className) &&
                            !shouldHide(pkg.applicationInfo?.processName)
                        }
                        if (filtered.size != list.size) param.result = filtered
                    }
                })
            installed++; Log.i("AntiDetect: 4.installedpkgs OK")
        } catch (e: Exception) { Log.w("AntiDetect: pkgs ${e.message}") }

        // 5. queryIntentActivities
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager",
                lpparam.classLoader, "queryIntentActivities",
                android.content.Intent::class.java, Int::class.java,
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        @Suppress("UNCHECKED_CAST")
                        val list = param.result as? List<android.content.pm.ResolveInfo>
                            ?: return
                        if (list.isEmpty()) return
                        val filtered = list.filter { ri ->
                            !shouldHide(ri.activityInfo?.packageName) &&
                            !shouldHide(ri.activityInfo?.applicationInfo?.className) &&
                            !shouldHide(ri.resolvePackageName)
                        }
                        if (filtered.size != list.size) param.result = filtered
                    }
                })
            installed++; Log.i("AntiDetect: 5.resolveinfo OK")
        } catch (e: Exception) { Log.w("AntiDetect: resolve ${e.message}") }

        // 6. getRunningServices
        try {
            XposedHelpers.findAndHookMethod(
                ActivityManager::class.java, "getRunningServices", Int::class.java,
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        @Suppress("UNCHECKED_CAST")
                        val list = param.result as? List<ActivityManager.RunningServiceInfo>
                            ?: return
                        if (list.isEmpty()) return
                        val filtered = list.filter { info ->
                            !shouldHide(info.process) && !shouldHide(info.service?.packageName)
                        }
                        if (filtered.size != list.size) param.result = filtered
                    }
                })
            installed++; Log.i("AntiDetect: 6.services OK")
        } catch (e: Exception) { Log.w("AntiDetect: services ${e.message}") }

        // 7. Block system property leaks (Xposed-related props)
        try {
            XposedHelpers.findAndHookMethod(
                System::class.java, "getProperty", String::class.java,
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        if (key.lowercase().contains("xposed") ||
                            key.lowercase().contains("edxposed") ||
                            key.lowercase().contains("lsposed")) {
                            param.result = null
                        }
                    }
                })
            installed++; Log.i("AntiDetect: 7.props OK")
        } catch (e: Exception) { Log.w("AntiDetect: props ${e.message}") }

        // 8. Block /proc/self/maps read — filter xposed/lsposed so entries
        try {
            XposedHelpers.findAndHookConstructor(
                java.io.FileInputStream::class.java, String::class.java,
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val path = param.args[0] as? String ?: return
                        if (path == "/proc/self/maps" || path.endsWith("/maps")) {
                            runCatching {
                                val original = java.io.File(path).readLines()
                                val filtered = original.filter { line ->
                                    !hidePatterns.any { line.contains(it) }
                                }
                                val cleanFile = java.io.File.createTempFile("maps_clean", null)
                                cleanFile.writeText(filtered.joinToString("\n"))
                                cleanFile.deleteOnExit()
                                param.args[0] = cleanFile.absolutePath
                            }
                        }
                    }
                })
            installed++; Log.i("AntiDetect: 8.maps OK")
        } catch (e: Exception) { Log.w("AntiDetect: maps ${e.message}") }

        Log.i("AntiDetect: $installed/8 layers active")
    }
}
