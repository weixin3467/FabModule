package com.fabmodule

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Minimal base class for Xposed hooks.
 */
abstract class BaseHook(
    protected val lpparam: XC_LoadPackage.LoadPackageParam
) {
    protected val classLoader: ClassLoader
        get() = lpparam.classLoader

    /** Install hooks — called once. */
    open fun install() {}

    // ═══════════════════════════════════════════════════════════
    // Hook helpers
    // ═══════════════════════════════════════════════════════════

    /**
     * Hook a single method by name (no parameter type constraint).
     */
    fun hookMethod(
        className: String, methodName: String,
        onBefore: ((XC_MethodHook.MethodHookParam) -> Unit)? = null,
        onAfter: ((XC_MethodHook.MethodHookParam) -> Unit)? = null
    ): XC_MethodHook.Unhook? = try {
        XposedHelpers.findAndHookMethod(
            XposedHelpers.findClass(className, classLoader),
            methodName,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try { onBefore?.invoke(param) } catch (e: Exception) { Log.e("hook", e) }
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    try { onAfter?.invoke(param) } catch (e: Exception) { Log.e("hook", e) }
                }
            }
        )
    } catch (e: Exception) { Log.w("hookMethod: $className.$methodName — ${e.message}"); null }

    /**
     * Hook all methods with a given name in a class.
     */
    fun hookAllMethods(
        className: String, methodName: String,
        onBefore: ((XC_MethodHook.MethodHookParam) -> Unit)? = null,
        onAfter: ((XC_MethodHook.MethodHookParam) -> Unit)? = null
    ): Set<XC_MethodHook.Unhook>? = try {
        XposedBridge.hookAllMethods(
            XposedHelpers.findClass(className, classLoader),
            methodName,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try { onBefore?.invoke(param) } catch (e: Exception) { Log.e("hook", e) }
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    try { onAfter?.invoke(param) } catch (e: Exception) { Log.e("hook", e) }
                }
            }
        )
    } catch (e: Exception) { Log.w("hookAllMethods: $className.$methodName — ${e.message}"); null }

    // ═══════════════════════════════════════════════════════════
    // Shared chat-Fragment detection (used by both FABHook & DrawerHook)
    // ═══════════════════════════════════════════════════════════

    /** Check if a Fragment is WeChat's chat UI (ChattingUIFragment / BaseChattingUIFragment). */
    fun isChatFragment(fragment: Any): Boolean {
        var cls: Class<*>? = fragment.javaClass
        while (cls != null && cls.name.startsWith("com.tencent.mm.")) {
            val name = cls.name
            if (name.contains("ChattingUI") || name.contains("chatting") ||
                name.contains("ChatRoomUI") || name.contains("chatroom"))
                return true
            cls = cls.superclass
        }
        return false
    }

    /**
     * Install Fragment lifecycle hooks on ALL Fragment base classes
     * (androidx, support-v4, android.app). Hooks both public onResume/onPause
     * AND internal performResume/performPause — catches WeChat fragments that
     * override lifecycle methods without calling super.
     *
     * @param tag log tag prefix (e.g. "FAB" or "Drawer")
     * @param onEnter callback when entering a chat Fragment (show)
     * @param onLeave callback when leaving a chat Fragment (hide)
     * @return number of Fragment base classes successfully hooked
     */
    fun installFragmentHooks(
        tag: String,
        onEnter: (fragment: Any) -> Unit,
        onLeave: (fragment: Any) -> Unit
    ): Int {
        val candidates = listOf(
            "androidx.fragment.app.Fragment" to "androidx",
            "android.support.v4.app.Fragment" to "support-v4",
            "android.app.Fragment" to "android.app"
        )
        var hooked = 0
        for ((className, label) in candidates) {
            try {
                val fragClass = classLoader.loadClass(className)

                // Public onResume/onPause (subclasses may override without super)
                XposedBridge.hookAllMethods(fragClass, "onResume",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isChatFragment(param.thisObject)) return
                            Log.i("$tag: L0($label) onResume → enter")
                            onEnter(param.thisObject)
                        }
                    })
                XposedBridge.hookAllMethods(fragClass, "onPause",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isChatFragment(param.thisObject)) return
                            Log.i("$tag: L0($label) onPause → leave")
                            onLeave(param.thisObject)
                        }
                    })

                // Internal performResume/performPause — called by FragmentManager,
                // never overridden by subclasses.
                XposedBridge.hookAllMethods(fragClass, "performResume",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isChatFragment(param.thisObject)) return
                            Log.i("$tag: L0($label) performResume → enter")
                            onEnter(param.thisObject)
                        }
                    })
                XposedBridge.hookAllMethods(fragClass, "performPause",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isChatFragment(param.thisObject)) return
                            Log.i("$tag: L0($label) performPause → leave")
                            onLeave(param.thisObject)
                        }
                    })
                hooked++
            } catch (_: Throwable) {}
        }
        if (hooked > 0) Log.i("$tag: L0 Fragment spy OK ($hooked bases × 4 hooks each)")
        else Log.w("$tag: L0 Fragment spy FAILED")
        return hooked
    }
}
