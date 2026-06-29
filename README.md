<!-- 中文版本 -->
# FabModule

> 微信悬浮按钮（FAB）+ 抽屉侧栏独立模块 — LSPosed/Xposed 插件  
> [Switch to English](#fabmodule-1)

**~1640 行 Kotlin · ~890KB APK · 零外部依赖 · 仅一个 `compileOnly` Xposed API · Android 15 全面适配**

> ⚠️ **免责声明**: 本项目**仅供学习和技术研究使用**，禁止用于任何商业用途或违反微信服务条款的行为。使用者需自行承担所有风险和责任。
>
> 🤖 **代码来源**: 本项目全部代码由 **Claude Code (Anthropic Opus 4.8)** 自动生成，未经人工手写修改。

---

## 功能

### FAB 悬浮按钮
- **64dp 圆形按钮** — 常驻微信首页右下角，点击展开/收回菜单
- **5 项快捷菜单** — 搜索 / 群聊 / 扫一扫 / 收付款 / 朋友圈
- **点击外部自动收回** — 遮罩层可点击，菜单行消费触摸事件防冒泡
- **朋友圈角标** — 未读回复数字提醒（红色圆形角标，最多显示 99+）
- **通讯录角标** — 底部 Tab section=1 检测，FAB 菜单通讯录行角标

### 抽屉侧栏
- **汉堡按钮注入 decorView** — 半透明黑色(#88000000)，绕过微信 ActionBar 遮挡
- **汉堡右上角红点** — 发现/通讯录任一有未读时显示，都清零时隐藏
- **6 项抽屉面板 + 动画** — 朋友圈 / 扫一扫 / 通讯录 / 收藏夹 / 表情 / 设置
- **抽屉角标** — 朋友圈行 + 通讯录行红色未读角标
- **点击外部关闭** — 抽屉遮罩点击关闭，面板滑出动画
- **Arrow 动画** — 汉堡三条线 ↔ 箭头流畅变换

### 智能显示/隐藏
- **6 层聊天检测** — L0 Fragment(performResume×3基类) + L1 addView + L2 removeView + L3 IME + L4 Focus + L5 setVisibility(StackTrace)，适配微信 8.0.65 Fragment 架构
- **首次进聊天 → 汉堡/FAB 自动移除** — addView + Fragment.onResume
- **返回主页 → 汉堡/FAB 自动恢复** — removeView / IME hide / Fragment.onPause，延迟重试 + 代次计数器
- **二次进聊天 → 移除** — L5 View.setVisibility + StackTrace 检测 FragmentManager 操作
- **hideTab 延迟重试** — 300ms/800ms/1200ms/2500ms 渐进式，手机慢布局也兜底

### 反检测与安全
- **8 层反检测** — 堆栈 / 进程 / 已安装应用 / 已安装包 / Intent / 服务 / 系统属性 / map 文件
- **Android 15 适配** — compileSdk/targetSdk 35 + Zygote modulePath + 包可见性绕过
- **Release 日志指纹消除** — BuildConfig.DEBUG 判断，生产环境只输出 WARN/ERROR

### 工程优化 (2026-06-29)
- **内存泄漏修复** — ChatState Activity/View 强引用改 WeakReference
- **反检测 Bug 修复** — 第7/8层 try-catch 平级拆分
- **FAB/Drawer 公共代码提取** — isChatFragment + installFragmentHooks 统一到 BaseHook
- **KeepAlive 重试上限** — 30次(60秒)防无限循环
- **延迟回调生命周期检查** — 全部 postDelayed 加 isFinishing/isDestroyed
- **Density 自愈守卫** — injectHamburger density≤0 时自行获取
- **Drawer UI 重构** — openDrawer 拆分为 4 级函数提升可读性

---

## 架构

```
app/src/main/kotlin/com/fabmodule/
    Entry.kt           — 入口: Zygote → AntiDetection → FabConfig → FABHook → DrawerConfig → DrawerHook (41行)
    AntiDetection.kt   — 8 层反检测 (约210行)
    FABHook.kt         — FAB + 5项菜单 + 底栏隐藏 + 角标 + L0 Fragment + L5 setVisibility (~640行)
    DrawerHook.kt      — 汉堡(decorView,#88000000) + 红点 + 抽屉 + L0 Fragment + L5 setVisibility (~420行)
    FabConfig.kt       — FAB 菜单配置, APK内置优先, SD卡兜底 (119行)
    DrawerConfig.kt    — 抽屉配置 (111行)
    BaseHook.kt        — Xposed 工具基类 + Fragment Hooks 共享实现 (约110行)
    Log.kt             — 日志 + BuildConfig.DEBUG 判断 (约25行)
    ChatState.kt       — FAB/Drawer 共享状态 + snsUnreadCount + contactsUnreadCount + hamburgerDot (约30行)
```

**总行数**: ~1640 行 Kotlin | **零外部依赖**

### 6 层聊天检测架构

| 层 | 信号 | 触发场景 | 状态 |
|---|------|---------|------|
| L0 | Fragment.onResume/onPause/performResume/performPause ×3 基类 | 聊天 Fragment 显示/隐藏 | ⚠️ WeChat override 无 super, 偶发 |
| L1 | addView(ChattingUILayout/MMChattingListView/ChattingContent) | 首次进聊天 | ✅ |
| L2 | removeView 聊天 View | 返回主页 | ✅ |
| L3 | IME show/hide | 弹键盘/收键盘 | ✅ 兜底 |
| L4 | MMEditText.requestFocus | 微信自动聚焦输入框 | ⚠️ 辅助 |
| L5 | View.setVisibility + StackTrace check | FragmentManager show/hide | ✅ 新增 (类比 XModule RemoveSelectionLimit) |

### 角标双阶段架构

| 阶段 | 机制 | 说明 |
|------|------|------|
| 初始捕获 | `scanSnsBadgeOnce()` | T+0 隐藏底栏前扫描一次，读取发现(section=2) + 通讯录(section=1) 角标 |
| 持续更新 | `TextView.setText()` 全局钩子 | 事件驱动，零轮询，tab 隐藏后仍生效 |
| 清零 | SnsTimeLineUI / Contact Activity | 进入朋友圈/通讯录自动清零 |
| 显示 | FAB 菜单 + Drawer 抽屉 + 汉堡红点 | ChatState 统一管理 |

### 关键架构决策

| 决策 | 原因 |
|------|------|
| Zygote 阶段捕获 `modulePath` | 绕过 Android 15 包可见性限制 |
| ZipFile 读取 APK 内资源 | 零跨进程调用，所有 Android 版本兼容 |
| APK 内置配置优先 | 资源自包含，不依赖 SD 卡文件 |
| `android.R.id.content` 注入 FAB | Z 层正确，不受微信视图树变动影响 |
| `decorView` 注入汉堡 | 绕过微信 ActionBar 左上角遮挡 |
| 6层聊天检测 | 适配微信 8.0.65 ChattingUI Activity → Fragment 架构变更 |
| `density = dpi / 160` | Android 标准 dp→px 换算，非自定义缩放 |
| 底栏「类名+位置」双校验 | 避免误隐藏聊天列表项 |
| keepAlive 自限轮询 + 次数上限 | FAB 存在即停止，60秒后放弃，省电防死循环 |
| FAB 点击外部收回 | overlay 可点击 dismissMenu，menu 消费触摸防冒泡 |
| 汉堡红点聚合 | ChatState.updateHamburgerDot() 统一管理，朋友圈/通讯录任一>0 即显示 |
| WeakReference 防泄漏 | Activity/View 强引用改弱引用 |

---

## 构建

```bash
cd FabModule
# 确保 JAVA_HOME 指向 JDK 17
./gradlew clean assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk (~890KB)
```

**环境要求**: JDK 17 / Android SDK 35 / Kotlin 1.9.x / AGP 8.x  
**依赖**: `compileOnly("de.robv.android.xposed:api:82")` — 唯一一个。

---

## 安装

1. 安装 APK：`adb install app-debug.apk`
2. 打开 LSPosed 管理器 → 模块 → 启用 **FabModule**
3. 作用域勾选 **微信**（`com.tencent.mm`）
4. 重启微信：`adb shell am force-stop com.tencent.mm`

### 模拟器测试流程

```bash
# 1. 构建 + 安装
cd FabModule
./gradlew assembleDebug
adb -s <emulator> install -r app/build/outputs/apk/debug/app-debug.apk

# 2. 冷启动微信
adb -s <emulator> shell am force-stop com.tencent.mm
adb -s <emulator> shell monkey -p com.tencent.mm -c android.intent.category.LAUNCHER 1

# 3. ⚠️ 等待 2 分钟（微信冷启动慢）
sleep 120

# 4. 确认无误后检查日志
adb -s <emulator> shell "su -c 'ls -lt /data/adb/lspd/log/ | head -3'"
adb -s <emulator> shell "su -c 'grep -i fabmodule /data/adb/lspd/log/verbose_<最新文件>.log | head -40'"
```

### 验证日志

```bash
# 反检测全部通过
adb shell su -c 'grep "8/8 layers" /data/adb/lspd/log/verbose_*'

# FAB 注入成功  
adb shell su -c 'grep "FAB+" /data/adb/lspd/log/verbose_*'

# 抽屉汉堡注入
adb shell su -c 'grep "Drawer: ☰" /data/adb/lspd/log/verbose_*'

# 图标加载
adb shell su -c 'grep "icons from APK" /data/adb/lspd/log/verbose_*'

# 菜单测试（点击 FAB 坐标 804,1360）
adb shell input tap 804 1360
adb shell su -c 'grep "showMenu" /data/adb/lspd/log/verbose_* | tail -1'
```

---

## 设计令牌（Material Design）

| 令牌 | 值 | 说明 |
|------|----|------|
| FAB 尺寸 | 64dp | MD 标准：56dp，加至 64dp 提升拇指触控 |
| 屏幕边距 | 16dp | 与所有屏幕边缘的距离 |
| 菜单宽度 | 240dp | 上限 screenW - 32dp |
| 抽屉宽度 | 180dp | 可配置 DrawerWidth |
| 菜单图标 | 28dp | FIT_CENTER 缩放 |
| 菜单字体 | 17sp | 白色 (#FFF) 配深色背景 |
| 菜单圆角 | 16dp | GradientDrawable cornerRadius |
| 汉堡尺寸 | 42dp | 半透明黑 #88000000 |
| 汉堡红点 | 8dp | #FF453A 红色圆点 |
| 触摸目标 | ≥52dp | WCAG 2.1 AA 无障碍规范 |
| 菜单背景 | #DD1E1E1E | 87% 不透明度深色 |
| 抽屉背景 | #EE1E1E1E | 93% 不透明度深色 |
| 遮罩层 | #88000000/#40000000 | 抽屉/菜单可点击遮罩 |
| 角标颜色 | #FF453A | iOS 风格红色圆形角标 |
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
| 7 | System.getProperty | Xposed/EdXposed/LSPosed 环境变量 |
| 8 | /proc/self/maps 文件访问 | 模块 .so / .apk 路径 |

隐藏前缀: `com.fabmodule`, `de.robv.android.xposed`, `org.lsposed`, `top.canyie.dreamland`, `com.elderdrivers.riru`

---

## 验证环境

| 设备 | 架构 | 系统 | 微信版本 | 状态 |
|------|------|------|---------|------|
| Xiaomi 13 | ARM64 | Android 15 (HyperOS) | 8.0.65 | ✅ 全部功能正常 |
| 雷电模拟器 | x86_64 | Android 9 | 8.0.65 | ✅ 全部功能正常 |

**模拟器配置**: 雷电 LDPlayer 9, 16GB 内存, 10 CPU 核, LSPosed 1.9.2 Zygisk, ART heap 512MB/1024MB

### 🚧 已知限制

**二次进聊天汉堡检测（部分可用）**：
微信 8.0.65 ChattingUI 改为 Fragment (`ChattingUIFragment extends BaseChattingUIFragment`)。
Fragment.show() 复用 View 树 → addView 不触发。Focus/IME/L5 setVisibility spy 作兜底方案覆盖大部分场景。

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

> Standalone FAB + Drawer Sidebar for WeChat — LSPosed/Xposed module  
> [切换到中文](#fabmodule)

**~1640 lines Kotlin · ~890KB APK · zero external dependencies · one `compileOnly` Xposed API · Full Android 15 support**

> ⚠️ **Disclaimer**: This project is for **educational and research purposes only**. Commercial use or any activity violating WeChat's Terms of Service is strictly prohibited. Users assume all risks and liabilities.
>
> 🤖 **Code Provenance**: This entire codebase was **auto-generated by Claude Code (Anthropic Opus 4.8)**, with no manual human authoring.

---

## Features

### FAB
- **64dp floating button** — pinned to WeChat home bottom-right, tap to toggle menu
- **5-item speed dial** — Search / Group Chat / Scan / Wallet / Moments
- **Click-outside-to-dismiss** — overlay clickable, menu rows consume touch events
- **Moments badge** — unread reply count (red circle, max 99+)
- **Contacts badge** — bottom tab section=1 detection

### Drawer Sidebar
- **Hamburger on decorView** — semi-transparent black (#88000000), bypasses ActionBar
- **Hamburger red-dot** — shown when Moments or Contacts has unread
- **6-item sliding panel** — Moments / Scan / Contacts / Favorites / Emoji / Settings
- **Badge on drawer rows** — Moments + Contacts unread counts
- **Arrow animation** — 3-line hamburger ↔ arrow transition

### Smart Show/Hide
- **6-layer chat detection** — L0 Fragment(performResume×3 bases) + L1 addView + L2 removeView + L3 IME + L4 Focus + L5 setVisibility(StackTrace), adapted for WeChat 8.0.65 Fragment architecture
- **Auto-hide on chat** — addView + Fragment.onResume
- **Auto-restore on return** — removeView / IME hide / Fragment.onPause with delayed retry + generation counter
- **Secondary entry detection** — L5 View.setVisibility + StackTrace check

### Anti-Detection & Security
- **8-layer anti-detection** — StackTrace / Process / InstalledApps / InstalledPkgs / ResolveInfo / Services / SystemProperty / map files
- **Android 15 adapted** — compileSdk/targetSdk 35 + Zygote modulePath
- **Release log fingerprint removal** — BuildConfig.DEBUG guard

### Engineering Optimizations (2026-06-29)
- **Memory leak fix** — WeakReference for Activity/View in ChatState
- **AntiDetection bug fix** — layer 7/8 try-catch block nesting corrected
- **Deduplication** — isChatFragment + installFragmentHooks shared in BaseHook
- **KeepAlive cap** — 30 retries (60s) max
- **Lifecycle checks** — isFinishing/isDestroyed on all delayed callbacks
- **Density self-heal** — injectHamburger acquires density if 0
- **Drawer UI refactor** — 4-tier function decomposition

## Build

```bash
cd FabModule
./gradlew clean assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk (~890KB)
```

**Requirements**: JDK 17, Android SDK 35, Kotlin 1.9.x, AGP 8.x  
**Dependencies**: `compileOnly("de.robv.android.xposed:api:82")` — that's the only one.

## Verified On

| Device | Arch | Android | WeChat | Status |
|--------|------|---------|--------|--------|
| Xiaomi 13 | ARM64 | 15 (HyperOS) | 8.0.65 | ✅ All features |
| LDPlayer 9 | x86_64 | 9 | 8.0.65 | ✅ All features |

**Emulator config**: 16GB RAM, 10 CPU cores, LSPosed 1.9.2 Zygisk, ART heap 512MB/1024MB

## License

MIT — see [LICENSE](LICENSE)

## Credits

| Project | Author | What We Learned |
|---------|--------|-----------------|
| [**WAuxiliary**](https://github.com/HdShare/WAuxiliary_Public) | HdShare | Activity.onResume hook pattern, anti-detection architecture, decorView injection |
| [**MDwechat_mod**](https://github.com/Cyanide-zh/MDwechat_mod) | Cyanide-zh | FAB interaction design, Material Design schemes, menu layout |
| [**WeXposed**](https://github.com/Xposed-Modules-Repo/com.fkzhang.wechatxposed) | fkzhang | JSON config format, FAB item data model, icon organization |

Follows [Material Design 3](https://m3.material.io/) guidelines.
