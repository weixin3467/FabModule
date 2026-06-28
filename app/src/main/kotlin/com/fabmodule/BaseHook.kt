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

    /**
     * Hook a single method by name and parameter types.
     * Parameter types are inferred from the hook callbacks, or accept the first match.
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
}
