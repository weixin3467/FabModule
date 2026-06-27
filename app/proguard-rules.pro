# FabModule ProGuard Rules

# Xposed entry class — must NOT be obfuscated (LSPosed reads from xposed_init)
-keep class com.fabmodule.Entry { *; }

# ModuleApk — Zygote path holder, accessed by Entry
-keep class com.fabmodule.ModuleApk { *; }

# BaseHook — abstract class inherited by FABHook
-keep class com.fabmodule.BaseHook { *; }

# FabConfig.FabItem — data class used in JSON deserialization
-keep class com.fabmodule.FabConfig$FabItem { *; }

# Xposed API — never strip (compileOnly, no runtime impact)
-dontwarn de.robv.android.xposed.**
-keep class de.robv.android.xposed.** { *; }

# Keep Kotlin metadata for reflection
-keepattributes *Annotation*, InnerClasses, Signature
-keepattributes EnclosingMethod

# Optimize agressively for size
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 3
