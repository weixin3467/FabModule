package com.fabmodule

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage

class Entry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        // Capture our own APK path — available only in Zygote, no cross-process call
        ModuleApk.path = startupParam?.modulePath ?: ""
        try { Log.i("FabModule Zygote: ${ModuleApk.path}") } catch (_: Throwable) {}
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        if (lpparam == null) return
        if (lpparam.packageName != "com.tencent.mm") return
        try {
            val processName = lpparam.processName ?: "unknown"
            if (processName != "com.tencent.mm") return
            Log.i("Process: $processName (main)")

            AntiDetection.install(lpparam)
            FabConfig.autoLoad()
            FABHook(lpparam).install()

            Log.i("======== FabModule ready ========")
        } catch (t: Throwable) {
            Log.e("FabModule FATAL: ${t.javaClass.name}: ${t.message}")
        }
    }
}

/** Holds our APK path captured during Zygote init. */
object ModuleApk {
    @Volatile var path: String = ""
}
