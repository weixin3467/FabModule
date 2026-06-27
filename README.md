<!-- 中文版本 -->
# FabModule

> 微信悬浮按钮（FAB）独立模块 — LSPosed/Xposed 插件  
> [Switch to English](#fabmodule-1)

**688 行 Kotlin · 898KB APK · 零外部依赖 · 仅一个 `compileOnly` Xposed API**

---

## 功能

- **悬浮按钮** — 64dp 圆形按钮，常驻微信首页右下角
- **6 项快捷菜单** — 搜索 / 加好友 / 群聊 / 扫一扫 / 收付款 / 朋友圈
- **隐藏微信底栏** — 类名 + 位置双重校验，精确匹配不误伤聊天列表
- **聊天页自动隐藏 FAB** — 通过 ChattingUI Activity 检测，进入聊天 2 秒内隐藏
- **返回首页自动恢复** — keepAlive 每 2 秒检查，发现 FAB 被移除立即重新注入
- **7 层反检测** — 堆栈 / 进程 / 已安装应用 / 已安装包 / Intent / 服务 / 系统属性
- **DPI 自适应布局** — Material Design dp 值 × Android density（dpi/160），所有分辨率自适应
- **APK 自包含资源** — 图标和配置通过 ZipFile 从自有 APK 加载，无需 SD 卡文件
- **真机+模拟器双验证** — Xiaomi 13 (ARM64, Android 15) + LDPlayer (x86_64, Android 9)

---

## 架构

```
Entry.kt (初始化协调层)
  ├── ① AntiDetection.install()   ← 安全前置
  ├── ② FabConfig.autoLoad()      ← 配置加载 (APK 内置 > SD卡)
  └── ③ FABHook(lpparam).install() ← 核心 FAB 逻辑

app/src/main/kotlin/com/fabmodule/
    Entry.kt           — Zygote 初始化 + 主进程入口（37 行）
    AntiDetection.kt   — 7 层反检测钩子（185 行）
    FABHook.kt         — FAB 注入 / 菜单 / 底栏隐藏 / 聊天检测（285 行）
    FabConfig.kt       — APK ZipFile 配置加载器（117 行）
    BaseHook.kt        — 最小化 Xposed 工具基类（41 行）
    Log.kt             — XposedBridge.log + android.util.Log 回退（23 行）

app/src/main/
    assets/xposed_init      — LSPosed 模块入口声明
    res/raw/fab_config.json  — 内置 6 项菜单配置
    res/drawable/fab_*.png   — 9 个内置图标
```

### 关键架构决策

| 决策 | 原因 |
|------|------|
| Zygote 阶段捕获 `modulePath` | 绕过 Android 15 包可见性限制 |
| ZipFile 读取 APK 内资源 | 零跨进程调用，所有 Android 版本兼容 |
| `android.R.id.content` 注入 | Z 层正确，不受微信视图树变动影响 |
| `density = dpi / 160` | Android 标准 dp→px 换算，非自定义缩放 |
| 底栏「类名+位置」双校验 | 避免误隐藏聊天列表项 |
| ChattingUI Activity 检测 | 微信聊天页是**独立 Activity**，不是 Fragment |

---

## 构建

```bash
cd FabModule
./gradlew clean assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk (~898KB)
```

**环境要求**: JDK 17 / Android SDK 34 / Kotlin 1.9.20 / AGP 8.2.0  
**依赖**: `compileOnly("de.robv.android.xposed:api:82")` — 唯一一个。

---

## 安装

1. 安装 APK：`adb install app-debug.apk`
2. 打开 LSPosed 管理器 → 模块 → 启用 **FabModule**
3. 作用域勾选 **微信**（`com.tencent.mm`）
4. 重启微信：`adb shell am force-stop com.tencent.mm`

### 快速验证
```bash
adb shell su -c 'grep "7/7 layers" /data/adb/lspd/log/verbose_*'  # 反检测状态
adb shell su -c 'grep "FAB+" /data/adb/lspd/log/verbose_*'         # FAB 已注入
adb shell su -c 'grep "9 icons" /data/adb/lspd/log/verbose_*'       # 图标已加载
```

---

## 设计令牌（Material Design）

| 令牌 | 值 | 说明 |
|------|----|------|
| FAB 尺寸 | 64dp | MD 标准：56dp，实际加至 64dp 提升拇指触控 |
| 屏幕边距 | 16dp | 与所有屏幕边缘的距离 |
| 菜单宽度 | 240dp | 上限 screenW - 32dp |
| 菜单图标 | 28dp | FIT_CENTER 缩放 |
| 菜单字体 | 17sp | 白色 (#FFF) 配深色背景 |
| 触摸目标 | ≥48dp | WCAG 2.1 AA 无障碍规范 |
| 菜单背景 | #DD1E1E1E | 87% 不透明度深色 |
| 行内间距 | 14dp | 垂直 = 水平 |

**缩放公式**: `px = dp × density`（其中 `density = dpi / 160`）

---

## 反检测（7 层）

| # | Hook 目标 | 隐藏内容 |
|---|----------|---------|
| 1 | Thread/Throwable.getStackTrace | 模块类名 |
| 2 | ActivityManager.getRunningAppProcesses | 模块进程信息 |
| 3 | PackageManager.getInstalledApplications | 模块应用信息 |
| 4 | PackageManager.getInstalledPackages | 模块包信息 |
| 5 | PackageManager.queryIntentActivities | Intent 解析结果中的模块 |
| 6 | ActivityManager.getRunningServices | 模块服务信息 |
| 7 | System.getProperty | Xposed 环境变量 |

隐藏前缀: `com.fabmodule`, `de.robv.android.xposed`, `org.lsposed`

---

## 验证环境

| 设备 | 架构 | 系统 | 微信版本 | 状态 |
|------|------|------|---------|------|
| Xiaomi 13 | ARM64 | Android 15 (HyperOS) | 8.0.45 | ✅ 全部功能正常 |
| 雷电模拟器 | x86_64 | Android 9 | 8.0.65 | ✅ 全部功能正常 |

---

## 协议

MIT — 详见 [LICENSE](LICENSE)

## 致谢

本项目在开发过程中深度参考了以下开源项目：

| 项目 | 作者 | 借鉴内容 |
|------|------|---------|
| [**WAuxiliary**](https://github.com/HdShare/WAuxiliary_Public) | HdShare | Activity.onResume Hook 模式、反检测架构、decorView 注入思路 |
| [**MDwechat_mod**](https://github.com/Cyanide-zh/MDwechat_mod) | Cyanide-zh (Fork from Blankeer/MDWechat) | FAB 悬浮按钮交互设计、Material Design 配色方案、菜单布局 |
| [**WeXposed (微X模块)**](https://github.com/Xposed-Modules-Repo/com.fkzhang.wechatxposed) | fkzhang | 主题 JSON 配置格式、FAB 菜单项数据模型、图标文件组织方式 |

同时遵循 [Material Design 3](https://m3.material.io/) 设计规范。

---

---

<a name="fabmodule-1"></a>
<!-- English version -->

# FabModule

> Standalone FAB (Floating Action Button) overlay for WeChat — LSPosed/Xposed module  
> [切换到中文](#fabmodule)

**688 lines Kotlin · 898KB APK · zero external dependencies · one `compileOnly` Xposed API`

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

## Build

```bash
cd FabModule
./gradlew clean assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk (~898KB)
```

**Requirements**: JDK 17, Android SDK 34, Kotlin 1.9.20, AGP 8.2.0  
**Dependencies**: `compileOnly("de.robv.android.xposed:api:82")` — that's the only one.

## Install

1. Install APK: `adb install app-debug.apk`
2. Open LSPosed Manager → Modules → enable **FabModule**
3. Set scope to **WeChat** (`com.tencent.mm`)
4. Restart WeChat: `adb shell am force-stop com.tencent.mm`

### Quick check
```bash
adb shell su -c 'grep "7/7 layers" /data/adb/lspd/log/verbose_*'  # anti-detection
adb shell su -c 'grep "FAB+" /data/adb/lspd/log/verbose_*'         # FAB injected
adb shell su -c 'grep "9 icons" /data/adb/lspd/log/verbose_*'       # icons loaded
```

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
| Row padding | 14dp | Vertical = horizontal |

**Scale formula**: `px = dp × density` (where `density = dpi / 160`)

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

## Verified On

| Device | Arch | Android | WeChat | Status |
|--------|------|---------|--------|--------|
| Xiaomi 13 | ARM64 | 15 (HyperOS) | 8.0.45 | ✅ All features |
| LDPlayer | x86_64 | 9 | 8.0.65 | ✅ All features |

## License

MIT — see [LICENSE](LICENSE)

## Credits

This project was developed with deep reference to these open-source projects:

| Project | Author | What We Learned |
|---------|--------|-----------------|
| [**WAuxiliary**](https://github.com/HdShare/WAuxiliary_Public) | HdShare | Activity.onResume hook pattern, anti-detection architecture, decorView injection approach |
| [**MDwechat_mod**](https://github.com/Cyanide-zh/MDwechat_mod) | Cyanide-zh (Fork from Blankeer/MDWechat) | FAB interaction design, Material Design color schemes, menu layout |
| [**WeXposed (微X模块)**](https://github.com/Xposed-Modules-Repo/com.fkzhang.wechatxposed) | fkzhang | Theme JSON config format, FAB item data model, icon file organization |

Also follows [Material Design 3](https://m3.material.io/) guidelines.
