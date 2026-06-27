<!-- 中文版本 -->
# FabModule

> 微信悬浮按钮（FAB）独立模块 — LSPosed/Xposed 插件  
> [Switch to English](#fabmodule-1)

**906 行 Kotlin · ~890KB APK · 零外部依赖 · 仅一个 `compileOnly` Xposed API**

> ⚠️ **免责声明**: 本项目**仅供学习和技术研究使用**，禁止用于任何商业用途或违反微信服务条款的行为。使用者需自行承担所有风险和责任。
>
> 🤖 **代码来源**: 本项目全部代码由 **Claude Code (Anthropic Opus 4.8) + DeepSeek** 自动生成，未经人工手写修改。

---

## 功能

- **悬浮按钮** — 64dp 圆形按钮，常驻微信首页右下角，点击展开/收回菜单
- **5 项快捷菜单** — 搜索 / 群聊 / 扫一扫 / 收付款 / 朋友圈
- **朋友圈角标** — 未读回复数字提醒（红色圆形角标，最多显示 99+）
- **隐藏微信底栏** — 类名 + 位置双重校验，延迟重试 + 持久拦截，快速不误伤
- **聊天页自动隐藏 FAB** — 通过 ChattingUI Activity 检测，进聊天立即隐藏
- **返回首页自动恢复** — onResume 触发注入，keepAlive 自限轮询（FAB 在则停，省电）
- **8 层反检测** — 堆栈 / 进程 / 已安装应用 / 已安装包 / Intent / 服务 / 系统属性 / map 文件
- **DPI 自适应布局** — Material Design dp 值 × Android density（dpi/160），所有分辨率自适应
- **APK 自包含资源** — 配置和图标通过 ZipFile 从自有 APK 加载（APK 内置优先，SD 卡兜底）
- **真机+模拟器双验证** — Xiaomi 13 (ARM64, Android 15) + 雷电模拟器 (Android 9)

---

## 架构

```
Entry.kt (初始化协调层)
  ├── ① AntiDetection.install()   ← 安全前置
  ├── ② FabConfig.autoLoad()      ← 配置: APK 内置优先，SD 卡兜底
  └── ③ FABHook(lpparam).install() ← 核心 FAB 逻辑

app/src/main/kotlin/com/fabmodule/
    Entry.kt           — Zygote 初始化 + 主进程入口（37 行）
    AntiDetection.kt   — 8 层反检测钩子（211 行）
    FABHook.kt         — FAB 注入 / 菜单 / 角标 / 底栏隐藏 / 聊天检测（478 行）
    FabConfig.kt       — APK ZipFile 配置加载器（118 行）
    BaseHook.kt        — 最小化 Xposed 工具基类（41 行）
    Log.kt             — XposedBridge.log + android.util.Log 回退（21 行）

app/src/main/
    assets/xposed_init      — LSPosed 模块入口声明
    res/raw/fab_config.json  — 内置 5 项菜单配置
    res/drawable/fab_*.png   — 9 个内置图标
```

### 关键架构决策

| 决策 | 原因 |
|------|------|
| Zygote 阶段捕获 `modulePath` | 绕过 Android 15 包可见性限制 |
| ZipFile 读取 APK 内资源 | 零跨进程调用，所有 Android 版本兼容 |
| APK 内置配置优先 | 资源自包含，不依赖 SD 卡文件 |
| `android.R.id.content` 注入 | Z 层正确，不受微信视图树变动影响 |
| `density = dpi / 160` | Android 标准 dp→px 换算，非自定义缩放 |
| 底栏「类名+位置」双校验 | 避免误隐藏聊天列表项 |
| 底栏 hook 延迟重试 (`v.post{}`) | 父容器未 layout 时一帧后自动重试 |
| ChattingUI Activity 检测 | 微信聊天页是**独立 Activity**，不是 Fragment |
| keepAlive 自限轮询 | FAB 存在即停止，减少主线程唤醒、省电 |
| FAB 开关模式 | 点 FAB 展开菜单，再点收回，overlay 触摸穿透 |

---

## 构建

```bash
cd FabModule
./gradlew clean assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk (~890KB)
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
adb shell su -c 'grep "8/8 layers" /data/adb/lspd/log/verbose_*'  # 反检测状态
adb shell su -c 'grep "FAB+" /data/adb/lspd/log/verbose_*'         # FAB 已注入
adb shell su -c 'grep "9 icons" /data/adb/lspd/log/verbose_*'       # 图标已加载
adb shell su -c 'grep "built-in" /data/adb/lspd/log/verbose_*'      # APK 配置加载
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
| 菜单圆角 | 16dp | GradientDrawable cornerRadius |
| 菜单浮起 | 8dp | elevation + ViewOutlineProvider 阴影 |
| 触摸目标 | ≥52dp | WCAG 2.1 AA 无障碍规范 |
| 菜单背景 | #DD1E1E1E | 87% 不透明度深色 |
| 半透明遮罩 | #40000000 | 触摸穿透，仅视觉暗化 |
| 角标颜色 | #FF453A | iOS 风格红色圆形角标 |
| 行内间距 | 14dp | 垂直 = 水平 |
| 逐项延迟 | 45ms | 菜单项依次入场 |

**缩放公式**: `px = dp × density`（其中 `density = dpi / 160`）

---

## 反检测（8 层）

| # | Hook 目标 | 隐藏内容 |
|---|----------|---------|
| 1 | Thread/Throwable.getStackTrace | 模块类名 |
| 2 | ActivityManager.getRunningAppProcesses | 模块进程信息 |
| 3 | PackageManager.getInstalledApplications | 模块应用信息 |
| 4 | PackageManager.getInstalledPackages | 模块包信息 |
| 5 | PackageManager.queryIntentActivities | Intent 解析结果中的模块 |
| 6 | ActivityManager.getRunningServices | 模块服务信息 |
| 7 | System.getProperty | Xposed 环境变量 |
| 8 | /proc/self/maps 文件访问 | 模块 .so / .apk 路径 |

隐藏前缀: `com.fabmodule`, `de.robv.android.xposed`, `org.lsposed`

---

## 验证环境

| 设备 | 架构 | 系统 | 微信版本 | 状态 |
|------|------|------|---------|------|
| Xiaomi 13 | ARM64 | Android 15 (HyperOS) | 8.0.65 | ✅ 全部功能正常 |
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

**906 lines Kotlin · ~890KB APK · zero external dependencies · one `compileOnly` Xposed API**

> ⚠️ **Disclaimer**: This project is for **educational and research purposes only**. Commercial use or any activity violating WeChat's Terms of Service is strictly prohibited. Users assume all risks and liabilities.
>
> 🤖 **Code Provenance**: This entire codebase was **auto-generated by Claude Code (Anthropic Opus 4.8) + DeepSeek**, with no manual human authoring.

---

## Features

- **Floating Action Button** — 64dp toggle button pinned to bottom-right of WeChat home screen
- **5-item speed dial menu** — Search / Group Chat / Scan / Pay / Moments
- **Moments badge** — Unread reply count on the 朋友圈 item (red dot badge, up to 99+)
- **Hide WeChat bottom tab bar** — class-name + position strict matching, deferred retry, no false positives
- **Auto-hide FAB on chat page** — ChattingUI Activity detection, instant removal
- **Auto-restore on returning home** — onResume triggers injection, self-limiting keepAlive (stops when FAB present)
- **8-layer anti-detection** — StackTrace / Process / InstalledApps / InstalledPkgs / ResolveInfo / Services / SystemProperty / map files
- **DPI-adaptive layout** — Material Design dp values × Android density (dpi/160)
- **Self-contained APK resources** — config + icons loaded from own APK via ZipFile (APK-first, SD card fallback)
- **Phone + emulator verified** — Xiaomi 13 (ARM64, Android 15) + LDPlayer (Android 9)

## Architecture

```
Entry.kt (init coordinator)
  ├── ① AntiDetection.install()   ← security first
  ├── ② FabConfig.autoLoad()      ← APK built-in first, SD card fallback
  └── ③ FABHook(lpparam).install() ← core FAB logic

app/src/main/kotlin/com/fabmodule/
    Entry.kt           — Zygote init + main process entry (37 lines)
    AntiDetection.kt   — 8-layer anti-detection hooks (211 lines)
    FABHook.kt         — FAB inject / menu / badge / tab hide / chat detect (478 lines)
    FabConfig.kt       — Config loader from APK ZipFile (118 lines)
    BaseHook.kt        — Minimal Xposed hook utility (41 lines)
    Log.kt             — XposedBridge.log + android.util.Log fallback (21 lines)

app/src/main/
    assets/xposed_init     — LSPosed module entry declaration
    res/raw/fab_config.json — Built-in 5-item menu config
    res/drawable/fab_*.png  — 9 built-in icons
```

### Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Zygote `modulePath` capture | Bypasses Android 15 package visibility restrictions |
| ZipFile reads APK resources | Zero cross-process calls, compatible with all Android versions |
| APK built-in config priority | Self-contained, no SD card dependency |
| `android.R.id.content` injection | Correct Z-order, survives WeChat view-tree churn |
| `density = dpi / 160` | Android standard dp→px conversion, not custom scale |
| Tab name + position dual check | Avoids false-hiding conversation list items |
| Tab hook deferred retry (`v.post{}`) | Auto-retry after layout when parent not yet sized |
| `ChattingUI` Activity detection | WeChat chat page is a **separate Activity**, not a Fragment |
| Self-limiting keepAlive | Stops when FAB is present, reduces main-thread wakeups |
| FAB toggle mode | Tap to open menu, tap again to close; overlay is touch-through |

## Build

```bash
cd FabModule
./gradlew clean assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk (~890KB)
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
adb shell su -c 'grep "8/8 layers" /data/adb/lspd/log/verbose_*'  # anti-detection
adb shell su -c 'grep "FAB+" /data/adb/lspd/log/verbose_*'         # FAB injected
adb shell su -c 'grep "9 icons" /data/adb/lspd/log/verbose_*'       # icons loaded
adb shell su -c 'grep "built-in" /data/adb/lspd/log/verbose_*'      # APK config loaded
```

## Design Tokens (Material Design)

| Token | Value | Note |
|-------|-------|------|
| FAB size | 64dp | Standard MD: 56dp, enlarged for thumb comfort |
| Edge margin | 16dp | From all screen edges |
| Menu width | 240dp | Capped at screenW - 32dp |
| Menu icon | 28dp | With FIT_CENTER scale |
| Menu font | 17sp | White (#FFF) on dark overlay |
| Menu corner radius | 16dp | GradientDrawable cornerRadius |
| Menu elevation | 8dp | Elevation + ViewOutlineProvider shadow |
| Touch target | ≥52dp | WCAG 2.1 AA compliance |
| Menu background | #DD1E1E1E | 87% opacity dark |
| Dim overlay | #40000000 | Touch-through, visual only |
| Badge color | #FF453A | iOS-style red circle badge |
| Row padding | 14dp | Vertical = horizontal |
| Stagger delay | 45ms | Cascading item entrance |

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
| 8 | /proc/self/maps file access | Module .so / .apk paths |

Hidden prefixes: `com.fabmodule`, `de.robv.android.xposed`, `org.lsposed`

## Verified On

| Device | Arch | Android | WeChat | Status |
|--------|------|---------|--------|--------|
| Xiaomi 13 | ARM64 | 15 (HyperOS) | 8.0.65 | ✅ All features |
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
