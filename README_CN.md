# FabModule

> 微信悬浮按钮（FAB）独立模块 — LSPosed/Xposed 插件

**688 行 Kotlin · 898KB APK · 零外部依赖 · 仅一个 `compileOnly` Xposed API**

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

借鉴了 WAuxiliary、MDWechat_mod、XModule 和 Material Design 指南的实践经验。
