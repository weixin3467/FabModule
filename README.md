# FabModule

> Standalone FAB (Floating Action Button) overlay for WeChat — LSPosed/Xposed module.

**688 lines Kotlin · 898KB APK · zero external dependencies · one `compileOnly` Xposed API**

<p align="center">
  <img src="https://img.shields.io/badge/platform-Android%209%2B-green" alt="Android 9+">
  <img src="https://img.shields.io/badge/WeChat-8.0.45%20%E2%80%93%208.0.65-blue" alt="WeChat 8.0.45-8.0.65">
  <img src="https://img.shields.io/badge/LSPosed-1.9.2%2B-purple" alt="LSPosed 1.9.2+">
  <img src="https://img.shields.io/badge/language-Kotlin-orange" alt="Kotlin">
  <img src="https://img.shields.io/badge/license-MIT-lightgrey" alt="MIT">
</p>

---

## Features

- **Floating Action Button** — 64dp button pinned to bottom-right of WeChat home screen
- **6-item speed dial menu** — Search / Add Friend / Group Chat / Scan / Pay / Moments
- **Hide WeChat bottom tab bar** — class-name + position strict matching, no false positives
- **Auto-hide FAB on chat page** — ChattingUI Activity detection, FAB disappears within 2s
- **Auto-restore on returning home** — keepAlive checks every 2s, re-injects if removed
- **7-layer anti-detection** — StackTrace / Process / InstalledApps / InstalledPkgs / ResolveInfo / Services / SystemProperty
- **DPI-adaptive layout** — Material Design dp values × Android density (dpi/160)
- **Self-contained APK resources** — icons + config loaded from own APK via ZipFile, no SD card required
- **Phone + emulator verified** — Xiaomi 13 (ARM64, Android 15) + LDPlayer (x86_64, Android 9)

---

## Screenshots

| Home Screen | FAB Menu |
|:-----------:|:--------:|
| FAB at bottom-right, tab bar hidden | Dark overlay, white icons + text |

---

## Architecture

```
Entry.kt (init coordinator)
  ├── ① AntiDetection.install()   ← security first
  ├── ② FabConfig.autoLoad()      ← config (built-in APK > SD card)
  └── ③ FABHook(lpparam).install() ← core FAB logic

app/src/main/kotlin/com/fabmodule/
    Entry.kt           — Zygote init + main process entry (37 lines)
    AntiDetection.kt   — 7-layer anti-detection hooks (185 lines)
    FABHook.kt         — FAB inject / menu / tab hide / chat detect (285 lines)
    FabConfig.kt       — Config loader from APK ZipFile (117 lines)
    BaseHook.kt        — Minimal Xposed hook utility (41 lines)
    Log.kt             — XposedBridge.log + android.util.Log fallback (23 lines)

app/src/main/
    assets/xposed_init     — LSPosed module entry declaration
    res/raw/fab_config.json — Built-in 6-item menu config
    res/drawable/fab_*.png  — 9 built-in icons
```

### Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Zygote `modulePath` capture | Bypasses Android 15 package visibility restrictions |
| ZipFile reads APK resources | Zero cross-process calls, compatible with all Android versions |
| `android.R.id.content` injection | Correct Z-order, survives WeChat view-tree churn |
| `density = dpi / 160` | Android standard dp→px conversion, not custom scale |
| Tab name + position dual check | Avoids false-hiding conversation list items |
| `ChattingUI` Activity detection | WeChat chat page is a **separate Activity**, not a Fragment |

---

## Build

```bash
cd FabModule
./gradlew clean assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk (~898KB)
```

**Requirements**: JDK 17, Android SDK 34, Kotlin 1.9.20, AGP 8.2.0

**Dependencies**: `compileOnly("de.robv.android.xposed:api:82")` — that's the only one.

---

## Install

1. Install APK on your device: `adb install app-debug.apk`
2. Open LSPosed Manager → Modules → enable **FabModule**
3. Set scope to **WeChat** (`com.tencent.mm`)
4. Restart WeChat: `adb shell am force-stop com.tencent.mm`

### On-device quick check
```bash
adb shell su -c 'grep "7/7 layers" /data/adb/lspd/log/verbose_*'  # anti-detection
adb shell su -c 'grep "FAB+" /data/adb/lspd/log/verbose_*'         # FAB injected
adb shell su -c 'grep "9 icons" /data/adb/lspd/log/verbose_*'       # icons loaded
```

---

## Design Tokens (Material Design)

| Token | Value | Note |
|-------|-------|------|
| FAB size | 64dp | Standard MD: 56dp, enlarged for thumb comfort |
| Edge margin | 16dp | From all screen edges |
| Menu width | 240dp | Capped at screenW - 32dp |
| Menu icon | 28dp | With FIT_CENTER scale |
| Menu font | 17sp | White (#FFF) on dark overlay |
| Touch target | ≥48dp | WCAG 2.1 AA compliance |
| Overlay | #DD1E1E1E | 87% opacity dark |
| Row padding | 14dp | Vertical = horizontal for simplicity |

---

## Anti-Detection Layers

| # | Hook Target | What It Hides |
|---|------------|---------------|
| 1 | Thread/Throwable.getStackTrace | Module class names |
| 2 | ActivityManager.getRunningAppProcesses | Module process entries |
| 3 | PackageManager.getInstalledApplications | Module application info |
| 4 | PackageManager.getInstalledPackages | Module package info |
| 5 | PackageManager.queryIntentActivities | Module from intent resolution |
| 6 | ActivityManager.getRunningServices | Module service entries |
| 7 | System.getProperty | Xposed environment variables |

Hidden prefixes: `com.fabmodule`, `de.robv.android.xposed`, `org.lsposed`

---

## Verified On

| Device | Arch | Android | WeChat | Status |
|--------|------|---------|--------|--------|
| Xiaomi 13 | ARM64 | 15 (HyperOS) | 8.0.45 | ✅ All features |
| LDPlayer | x86_64 | 9 | 8.0.65 | ✅ All features |

---

## License

MIT — see [LICENSE](LICENSE)

## Credits

Built with insights from WAuxiliary, MDWechat_mod, XModule, and Material Design guidelines.
