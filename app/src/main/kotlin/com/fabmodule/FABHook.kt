package com.fabmodule

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.*
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * FAB overlay for WeChat.
 */
class FABHook(lpparam: XC_LoadPackage.LoadPackageParam) : BaseHook(lpparam) {

    private val TAG_FAB = 0x7F100001
    private val handler = Handler(Looper.getMainLooper())
    private var keepAliveRunnable: Runnable? = null
    private var menuOverlay: ViewGroup? = null
    private var fabView: View? = null
    @Volatile private var isInChat = false
    @Volatile private var menuOpen = false
    private var density = -1f  // sentinel — forces recompute on first onResume
    private var screenW = 1080
    private var screenH = 1920

    private val Int.dp: Int get() = (this * density + 0.5f).toInt()

    override fun install() {
        try {
            // Validate critical WeChat Activity classes on startup
            validateClasses(lpparam.classLoader)

            // Fragment lifecycle hooks for 8.0.65+ (ChattingUI is Fragment, not Activity)
            // Try all three Fragment base classes — support-v4 on older Android,
            // AndroidX on newer, android.app.Fragment as last resort.
            installFragmentHooks()

            hookAllMethods("android.app.Activity", "onResume",
                onAfter = { p ->
                    val a = p.thisObject as? android.app.Activity ?: return@hookAllMethods
                    val dm = a.resources.displayMetrics
                    density = dm.density; screenW = dm.widthPixels; screenH = dm.heightPixels
                    val cls = a.javaClass.name
                    // Log ALL Activity onResume — no filter, so we see the REAL chat class
                    Log.i("ACTIVITY: $cls")
                    if (cls.contains("chatting") || cls.contains("ChattingUI") ||
                        cls.contains("chatroom") || cls.contains("ChatRoomUI") ||
                        cls.contains("MainUI") ||
                        (cls.startsWith("com.tencent.mm.ui.chatting.") && cls.length > 35)) {
                        Log.i("FAB: isInChat=true")
                        isInChat = true; ChatState.inChat = true; stopKeepAlive(); removeOverlay(); removeFab()
                        return@hookAllMethods
                    }
                    // SnsTimeLineUI opened → badge cleared (user viewing Moments)
                    if (cls.contains("SnsTimeLineUI")) {
                        ChatState.snsUnreadCount = 0
                        return@hookAllMethods
                    }
                    if (!cls.contains("LauncherUI")) return@hookAllMethods
                    isInChat = false; ChatState.inChat = false; ChatState.launcherActivity = a
                    layoutDone = false; waitLayout(a)
                })

            // Tab hiding hooks
            try {
                val vg = lpparam.classLoader.loadClass("android.view.ViewGroup")
                val vw = lpparam.classLoader.loadClass("android.view.View")
                de.robv.android.xposed.XposedBridge.hookAllMethods(vg, "addView",
                    object : de.robv.android.xposed.XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val v = param.args[0] as? View ?: return
                            val parent = param.thisObject as? ViewGroup ?: return
                            // Check immediately if parent is already sized
                            if (parent.height >= 200) {
                                val loc = IntArray(2); v.getLocationInWindow(loc)
                                if (tryBlockTab(v, parent, loc))
                                { v.visibility = View.GONE; v.setTag(0x7F100002, true); return }
                            }
                            // Parent not laid out yet — defer until after layout
                            v.post {
                                val p = v.parent as? ViewGroup ?: return@post
                                if (p.height < 200) return@post
                                val loc = IntArray(2); v.getLocationInWindow(loc)
                                if (tryBlockTab(v, p, loc))
                                { v.visibility = View.GONE; v.setTag(0x7F100002, true) }
                            }
                        }
                    })
                de.robv.android.xposed.XposedBridge.hookAllMethods(vw, "setVisibility",
                    object : de.robv.android.xposed.XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if ((param.args[0] as? Int) != View.VISIBLE) return
                            val v = param.thisObject as? View ?: return
                            if (v.getTag(0x7F100002) == true) { param.args[0] = View.GONE; return }
                            val parent = v.parent as? ViewGroup ?: return
                            // Check immediately if parent is sized
                            if (parent.height >= 200) {
                                val loc = IntArray(2); v.getLocationInWindow(loc)
                                if (tryBlockTab(v, parent, loc))
                                { param.args[0] = View.GONE; v.setTag(0x7F100002, true); return }
                            }
                            // Defer if parent not laid out yet
                            v.post {
                                val p = v.parent as? ViewGroup ?: return@post
                                if (p.height < 200) return@post
                                val loc = IntArray(2); v.getLocationInWindow(loc)
                                if (tryBlockTab(v, p, loc))
                                { v.visibility = View.GONE; v.setTag(0x7F100002, true) }
                            }
                        }
                    })
            } catch (_: Throwable) {}

            // Badge spy — hook ALL TextView.setText() calls, filter for
            // small views at screen bottom with digit text. This catches
            // badge updates even AFTER we hide the tab bar, and covers
            // new-reply increments / read clears in real time.
            try {
                val tvClass = lpparam.classLoader.loadClass("android.widget.TextView")
                de.robv.android.xposed.XposedBridge.hookAllMethods(tvClass, "setText",
                    object : de.robv.android.xposed.XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            onBadgeSetText(param)
                        }
                    })
                Log.i("Badge: TextView.setText hook OK")
            } catch (_: Throwable) { Log.w("Badge: setText hook FAILED") }

            // Layer 5: View.setVisibility — catches Fragment.show()/hide().
            // WeChat 8.0.65 ChattingUIFragment root View gets setVisibility
            // toggled by FragmentManager. StackTrace check catches it.
            try {
                val viewClass = lpparam.classLoader.loadClass("android.view.View")
                de.robv.android.xposed.XposedBridge.hookAllMethods(viewClass, "setVisibility",
                    object : de.robv.android.xposed.XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val newVis = param.args[0] as? Int ?: return
                            val v = param.thisObject as? View ?: return
                            if (v.width < 300 || v.height < 300) return
                            val st = Thread.currentThread().stackTrace
                            if (!st.any { f -> f.className.let { c ->
                                c.contains("chatting") || c.contains("ChattingUI") ||
                                c.contains("Chatting") || c.contains("chatroom")
                            }}) return
                            when (newVis) {
                                View.VISIBLE -> {
                                    Log.i("FAB: L5 setVisibility(VISIBLE) → hide")
                                    isInChat = true; ChatState.inChat = true
                                    stopKeepAlive(); removeOverlay(); removeFab()
                                }
                                View.GONE, View.INVISIBLE -> {
                                    Log.i("FAB: L5 setVisibility(GONE) → re-inject")
                                    isInChat = false; ChatState.inChat = false
                                    val a = ChatState.launcherActivity ?: return
                                    if (a.isFinishing || a.isDestroyed) return
                                    handler.postDelayed({ injectFab(a) }, 300)
                                    handler.postDelayed({ injectFab(a) }, 800)
                                    handler.postDelayed({ startKeepAlive(a) }, 1200)
                                }
                            }
                        }
                    })
                Log.i("FAB: L5 View.setVisibility spy OK")
            } catch (_: Throwable) { Log.w("FAB: L5 setVisibility spy FAILED") }

            Log.i("Setup: hooks installed, awaiting LauncherUI resume")
        } catch (e: Throwable) { Log.w("Setup: ${e.message}") }
    }

    // == Startup validation ==

    /** Check if a Fragment is WeChat's chat UI (ChattingUIFragment / BaseChattingUIFragment). */
    private fun isChatFragment(fragment: Any): Boolean {
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
     * Install Fragment lifecycle hooks on ALL Fragment base classes.
     * Hooks both public onResume/onPause AND internal performResume/performPause
     * to catch WeChat fragments that override lifecycle methods without super.
     */
    private fun installFragmentHooks() {
        val candidates = listOf(
            "androidx.fragment.app.Fragment" to "androidx",
            "android.support.v4.app.Fragment" to "support-v4",
            "android.app.Fragment" to "android.app"
        )
        var hooked = 0
        for ((className, label) in candidates) {
            try {
                val fragClass = lpparam.classLoader.loadClass(className)

                // Public hooks (may be overridden without super)
                de.robv.android.xposed.XposedBridge.hookAllMethods(fragClass, "onResume",
                    object : de.robv.android.xposed.XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isChatFragment(param.thisObject)) return
                            Log.i("FAB: L0($label) onResume → hide")
                            isInChat = true; ChatState.inChat = true
                            stopKeepAlive(); removeOverlay(); removeFab()
                        }
                    })
                de.robv.android.xposed.XposedBridge.hookAllMethods(fragClass, "onPause",
                    object : de.robv.android.xposed.XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isChatFragment(param.thisObject)) return
                            Log.i("FAB: L0($label) onPause → re-inject")
                            isInChat = false; ChatState.inChat = false
                            val a = ChatState.launcherActivity ?: return
                            if (a.isFinishing || a.isDestroyed) return
                            handler.postDelayed({ injectFab(a) }, 300)
                            handler.postDelayed({ injectFab(a) }, 800)
                            handler.postDelayed({ startKeepAlive(a) }, 1200)
                        }
                    })

                // Internal dispatch hooks — called by FragmentManager,
                // never overridden by subclasses (package-private/final).
                de.robv.android.xposed.XposedBridge.hookAllMethods(fragClass, "performResume",
                    object : de.robv.android.xposed.XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isChatFragment(param.thisObject)) return
                            Log.i("FAB: L0($label) performResume → hide")
                            isInChat = true; ChatState.inChat = true
                            stopKeepAlive(); removeOverlay(); removeFab()
                        }
                    })
                de.robv.android.xposed.XposedBridge.hookAllMethods(fragClass, "performPause",
                    object : de.robv.android.xposed.XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isChatFragment(param.thisObject)) return
                            Log.i("FAB: L0($label) performPause → re-inject")
                            isInChat = false; ChatState.inChat = false
                            val a = ChatState.launcherActivity ?: return
                            if (a.isFinishing || a.isDestroyed) return
                            handler.postDelayed({ injectFab(a) }, 300)
                            handler.postDelayed({ injectFab(a) }, 800)
                            handler.postDelayed({ startKeepAlive(a) }, 1200)
                        }
                    })
                hooked++
            } catch (_: Throwable) {}
        }
        if (hooked > 0) Log.i("FAB: L0 Fragment spy OK ($hooked bases × 4 hooks each)")
        else Log.w("FAB: L0 Fragment spy FAILED")
    }

    private fun validateClasses(cl: ClassLoader) {
        val critical = listOf(
            "com.tencent.mm.ui.LauncherUI",
            "com.tencent.mm.ui.chatting.ChattingUI"
        )
        for (cn in critical) {
            try { cl.loadClass(cn); Log.i("Verify OK: $cn") }
            catch (_: Throwable) { Log.w("Verify FAIL: $cn — may need fallback") }
        }
    }

    // == Tab detection — class-name match only (no layout-only fallback) ==

    private fun tryBlockTab(v: View, p: ViewGroup, loc: IntArray): Boolean {
        val cn = v.javaClass.name
        // MUST contain Tab/Bottom/tab/bottom — never hide by layout alone
        if (!(cn.contains("Tab") || cn.contains("tab") || cn.contains("Bottom") || cn.contains("bottom")))
            return false
        return tabLayoutMatch(v, p, loc)
    }

    private fun tabLayoutMatch(v: View, p: ViewGroup, loc: IntArray): Boolean {
        val cBot = loc[1] + v.height
        return cBot in (p.height - 10)..(p.height + 10) &&
               v.width >= p.width * 0.95f &&
               v.height in 40.dp..120.dp
    }

    // == Layout readiness ==

    private var layoutDone = false

    private fun waitLayout(a: android.app.Activity) {
        if (layoutDone) { ready(a); return }
        val d = a.window?.decorView as? ViewGroup ?: return
        if (d.height > 200) { layoutDone = true; ready(a); return }
        d.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    d.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    if (!layoutDone) { layoutDone = true; ready(a) }
                }
            })
        // 5s max timeout as safety net
        handler.postDelayed({ if (!layoutDone) { layoutDone = true; ready(a) } }, 5000)
    }

    private fun ready(a: android.app.Activity) {
        if (isInChat) return
        // Initial scan while tab bar is still visible; TextView.setText hook
        // handles all subsequent updates (new replies, clears) in real time.
        scanSnsBadgeOnce(a)
        hideTab(a); injectFab(a); startKeepAlive(a)
        // Progressive hide retries — phones layout slower than emulators
        handler.postDelayed({ hideTab(a) }, 100)
        handler.postDelayed({ hideTab(a) }, 400)
        handler.postDelayed({ hideTab(a) }, 1000)
        handler.postDelayed({ hideTab(a) }, 2500)
    }

    // == Tab hiding ==

    private fun hideTab(a: android.app.Activity) {
        try {
            val d = (a.window?.decorView as? ViewGroup) ?: return
            val tab = findTabView(d, 10)
            if (tab != null) {
                tab.visibility = View.GONE; tab.setTag(0x7F100002, true)
                Log.i("Tab hidden: ${tab.javaClass.simpleName}")
            }
        } catch (_: Throwable) {}
    }

    private fun findTabView(p: ViewGroup, depth: Int): View? {
        if (depth == 0) return null
        for (i in p.childCount - 1 downTo 0) {
            val c = p.getChildAt(i) ?: continue
            val cn = c.javaClass.name
            if (cn.contains("Tab") || cn.contains("tab") || cn.contains("Bottom") || cn.contains("bottom")) {
                // Minimal: view has been measured (width>0). Don't require isShown —
                // on phones the parent chain may not be fully visible yet.
                if (c.width > 0 && c.height > 0) {
                    val loc = IntArray(2); c.getLocationInWindow(loc)
                    val b = loc[1] + c.height
                    if (c.width >= p.rootView.width * 0.95f && b in (p.rootView.height - 10)..(p.rootView.height + 10) && c.height in 40.dp..120.dp)
                        return c
                }
            }
            if (c is ViewGroup) { val r = findTabView(c, depth - 1); if (r != null) return r }
        }
        return null
    }

    // == SNS badge — initial scan + persistent setText hook ==

    /**
     * One-shot scan for initial unread count, runs BEFORE tab bar is hidden.
     * After this, [onBadgeSetText] catches all future updates in real time.
     */
    private fun scanSnsBadgeOnce(a: android.app.Activity) {
        try {
            if (density <= 0f) return
            val d = (a.window?.decorView as? ViewGroup) ?: return
            val tabBar = findTabView(d, 10) ?: return
            val tbLoc = IntArray(2); tabBar.getLocationInWindow(tbLoc)
            val top = tbLoc[1]; val bot = top + tabBar.height
            val left = tbLoc[0]; val w = tabBar.width
            if (w <= 0) return

            // Walk entire decorView to find badges in the tab region
            val found = mutableListOf<Triple<View, Int, Int>>() // view, cx, count
            collectBadgesNow(d, top, bot, found)

            for ((_, cx, count) in found) {
                val section = ((cx - left) * 4) / w
                if (section == 2) { // 发现 tab
                    ChatState.snsUnreadCount = count
                    Log.i("Badge init: 发现 tab count=$count")
                    return
                }
            }
        } catch (_: Throwable) {}
    }

    private fun collectBadgesNow(v: View, tbTop: Int, tbBottom: Int,
                                  out: MutableList<Triple<View, Int, Int>>) {
        if (v is TextView && v.width in 8.dp..40.dp && v.height in 8.dp..26.dp) {
            val text = v.text?.toString() ?: ""
            if (text.any { it.isDigit() } && v.background != null) {
                val loc = IntArray(2); v.getLocationInWindow(loc)
                if (loc[1] in tbTop..tbBottom) {
                    val count = if (text.endsWith("+")) text.dropLast(1).toIntOrNull() ?: 99
                                else text.toIntOrNull() ?: 0
                    out.add(Triple(v, loc[0] + v.width / 2, count))
                    return
                }
            }
        }
        if (v !is TextView && v.width in 6.dp..16.dp && v.height in 6.dp..16.dp
            && v.background != null) {
            val loc = IntArray(2); v.getLocationInWindow(loc)
            if (loc[1] in tbTop..tbBottom) {
                out.add(Triple(v, loc[0] + v.width / 2, 1))
                return
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                v.getChildAt(i)?.let { collectBadgesNow(it, tbTop, tbBottom, out) }
            }
        }
    }

    /**
     * Hooked on ALL TextView.setText() calls. When WeChat updates a badge
     * anywhere on screen, we check: small view + bottom ~15% + has digits.
     * Works regardless of tab bar visibility — event-driven, zero polling.
     */
    private fun onBadgeSetText(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
        try {
            if (density <= 0f) return
            val v = param.thisObject as? TextView ?: return
            if (v.width !in 8.dp..40.dp || v.height !in 8.dp..26.dp) return
            if (v.background == null) return

            val text = param.args.getOrNull(0)?.toString() ?: return
            val loc = IntArray(2); v.getLocationInWindow(loc)

            // Must be in bottom ~15% of screen (tab bar region)
            if (loc[1] < screenH * 0.85f) return

            val count = when {
                text.isEmpty() -> 0  // badge cleared
                text.any { it.isDigit() } -> {
                    if (text.endsWith("+")) text.dropLast(1).toIntOrNull() ?: 99
                    else text.toIntOrNull() ?: 0
                }
                else -> return  // not a badge (e.g. tab label)
            }

            // Guess tab section by X position (0..3)
            val cx = loc[0] + v.width / 2
            val section = (cx * 4) / screenW
            if (section != 2) return  // not 发现 tab

            if (ChatState.snsUnreadCount != count) {
                ChatState.snsUnreadCount = count
                Log.i("Badge event: section=$section count=$count text='$text'")
            }
        } catch (_: Throwable) {}
    }

    // == KeepAlive — self-limiting: stops when FAB is present ==

    private fun startKeepAlive(a: android.app.Activity) {
        stopKeepAlive()
        val r = object : Runnable {
            override fun run() {
                try {
                    if (a.isFinishing || a.isDestroyed || isInChat) return
                    val fab = (a.findViewById<ViewGroup>(android.R.id.content)
                        ?: return).findViewWithTag<View>(TAG_FAB)
                    if (fab != null) return  // FAB present, stop polling — save battery
                    injectFab(a)
                    handler.postDelayed(this, 2000)
                } catch (_: Throwable) { handler.postDelayed(this, 2000) }
            }
        }
        keepAliveRunnable = r; handler.postDelayed(r, 2000)
    }
    private fun stopKeepAlive() { keepAliveRunnable?.let { handler.removeCallbacks(it) }; keepAliveRunnable = null }
    private fun removeOverlay() { menuOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }; menuOverlay = null }
    private fun removeFab() { fabView?.let { (it.parent as? ViewGroup)?.removeView(it) }; fabView = null }

    // == FAB Button ==

    private fun injectFab(a: android.app.Activity) {
        if (isInChat) return
        try {
            val root = a.findViewById<ViewGroup>(android.R.id.content) ?: return
            root.findViewWithTag<View>(TAG_FAB)?.let { return }
            val bmp = FabConfig.iconBitmaps["fab_button"]
            val sz = 64.dp; val pad = 14.dp; val mrg = 16.dp; val bot = 72.dp
            val iv = ImageView(a).apply {
                tag = TAG_FAB; scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(pad, pad, pad, pad)
                setBackgroundColor(Color.TRANSPARENT); isClickable = true; isFocusable = true
                contentDescription = "快速菜单"
                setOnClickListener { if (menuOpen) dismissMenu() else showMenu(a) }
                if (bmp != null) { setImageBitmap(bmp); setColorFilter(Color.WHITE) }
            }
            root.addView(iv, FrameLayout.LayoutParams(sz, sz).apply {
                gravity = Gravity.END or Gravity.BOTTOM; setMargins(0, 0, mrg, mrg + bot) })
            iv.bringToFront(); fabView = iv
            Log.i("FAB+")
        } catch (t: Throwable) { Log.e("FAB+: ${t.message}") }
    }

    // == Menu Popup ==

    private fun showMenu(a: android.app.Activity) {
        val items = FabConfig.fabItems.ifEmpty { FabConfig.defaultItems }
        Log.i("showMenu: ${items.size} config items, ${items.count { it.enable }} enabled")
        menuOpen = true; toggleFabIcon(toClose = true)
        removeOverlay()           // clean up any stale overlay (keep FAB)
        val root = a.findViewById<ViewGroup>(android.R.id.content) ?: return
        val mW    = minOf(240.dp, screenW - 32.dp)
        val iconS = 28.dp; val fSize = 17f
        val pH    = 20.dp; val pV = 14.dp
        val rP    = 14.dp; val botM  = 155.dp; val rM = 16.dp
        val dH    = maxOf(1.dp, 1)
        val rad   = 16.dp.toFloat()

        val overlay = FrameLayout(a).apply {
            setBackgroundColor(Color.parseColor("#40000000"))  // visual dim ONLY
            isClickable = false; isFocusable = false           // pass through ALL touches
            alpha = 0f; animate().alpha(1f).setDuration(200).start()
        }
        val menu = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#DD1E1E1E"))
                cornerRadius = rad
            }
            setPadding(pH, pV, pH, pV)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 8.dp.toFloat()
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, rad)
                    }
                }
                clipToOutline = true
            }
            translationY = 120f
            animate().translationY(0f).setDuration(250)
                .setInterpolator(DecelerateInterpolator()).start()
        }

        val rows = mutableListOf<View>()
        var idx = 0; val total = items.count { it.enable }
        for (item in items) {
            if (!item.enable) continue
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(rP, rP, rP, rP)
                minimumHeight = 52.dp
                isClickable = true; isFocusable = true
                foreground = android.graphics.drawable.ColorDrawable(Color.parseColor("#18FFFFFF"))
                contentDescription = item.text
                setOnClickListener { go(a, item); dismissMenu() }
                // Stagger entry: hidden initially
                alpha = 0f; translationY = 16f
            }
            iconKey(item.icon)?.let { FabConfig.iconBitmaps[it] }?.let { bmp ->
                row.addView(ImageView(a).apply {
                    setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_CENTER
                    setColorFilter(Color.WHITE); layoutParams = LinearLayout.LayoutParams(iconS, iconS)
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = 12.dp
                })
            }
            row.addView(TextView(a).apply {
                text = item.text; textSize = fSize; setTextColor(Color.WHITE)
            })
            // Badge for 朋友圈 unread (from bottom-tab scan)
            if (item.type == "timeline") {
                val unread = ChatState.snsUnreadCount
                if (unread > 0) {
                    val cnt = if (unread > 99) "99+" else unread.toString()
                    val badgeS = 18.dp
                    row.addView(TextView(a).apply {
                        text = cnt; textSize = 11f
                        setTextColor(Color.WHITE); gravity = Gravity.CENTER
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(Color.parseColor("#FF453A"))
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                        }
                        val padH = if (cnt.length > 1) 4.dp else 0
                        setPadding(padH, 0, padH, 0)
                    }, LinearLayout.LayoutParams(badgeS, badgeS).apply {
                        marginStart = 8.dp
                    })
                }
            }
            menu.addView(row)
            rows += row
            if (++idx < total) menu.addView(View(a).apply {
                setBackgroundColor(Color.parseColor("#30FFFFFF"))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dH))
        }
        overlay.addView(menu, FrameLayout.LayoutParams(mW, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END or Gravity.BOTTOM; setMargins(0, 0, rM, botM) })
        root.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        menuOverlay = overlay

        // Staggered item entrance — cascading reveal
        handler.post {
            rows.forEachIndexed { i, row ->
                row.animate()
                    .alpha(1f).translationY(0f)
                    .setStartDelay((i * 45).toLong())
                    .setDuration(180)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun dismissMenu() {
        if (!menuOpen) return
        menuOpen = false; toggleFabIcon(toClose = false)
        // Animate overlay out; FAB stays (it's the toggle)
        menuOverlay?.animate()?.alpha(0f)?.setDuration(150)
            ?.withEndAction { removeOverlay() }?.start()
            ?: removeOverlay()
    }

    private fun toggleFabIcon(toClose: Boolean) {
        val v = fabView as? ImageView ?: return
        if (toClose) {
            ObjectAnimator.ofFloat(v, "rotation", 0f, 45f).apply {
                duration = 200; start()
            }
        } else {
            ObjectAnimator.ofFloat(v, "rotation", 45f, 0f).apply {
                duration = 200; start()
            }
        }
    }

    // == Icon mapping ==

    private fun iconKey(icon: String): String? = when (icon) {
        "ic_sousuo.png"     -> "fab_search"
        "ic_chat.png"       -> "fab_groupchat"
        "ic_扫一扫.png"      -> "fab_scan"
        "ic_收付款.png"      -> "fab_wallet"
        "朋友圈.png"         -> "fab_timeline"
        "fab_search"        -> "fab_search"
        "fab_groupchat"     -> "fab_groupchat"
        "fab_scan"          -> "fab_scan"
        "fab_wallet"        -> "fab_wallet"
        "fab_timeline"      -> "fab_timeline"
        else -> null
    }

    // == Navigation ==

    private fun go(ctx: android.content.Context, item: FabConfig.FabItem) {
        try {
            Log.i("go: type=${item.type}, text=${item.text}")
            val cn = map[item.type]
            if (cn == null) {
                Log.w("go: unknown type '${item.type}', not in map")
                return
            }
            when (item.type) {
                "groupchat" -> ctx.startActivity(Intent().apply {
                    setClassName("com.tencent.mm", cn)
                    putExtra("list_type", 0); putExtra("scene", 7); putExtra("list_attr", 4951)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                else -> {
                    // Validate activity exists before starting
                    val intent = Intent().apply {
                        setClassName("com.tencent.mm", cn)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (ctx.packageManager.resolveActivity(intent, 0) != null)
                        ctx.startActivity(intent)
                    else Log.w("go: Activity not found — $cn (type=${item.type})")
                }
            }
        } catch (t: Throwable) { Log.w("go: ${item.type} → ${t.javaClass.simpleName}: ${t.message}") }
    }

    private val map = mapOf(
        "search" to "com.tencent.mm.plugin.fts.ui.FTSMainUI",
        "timeline" to "com.tencent.mm.plugin.sns.ui.SnsTimeLineUI",
        "scan" to "com.tencent.mm.plugin.scanner.ui.BaseScanUI",
        "walletcoin" to "com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI",

        "groupchat" to "com.tencent.mm.ui.contact.SelectContactUI",
        "appbrand" to "com.tencent.mm.plugin.appbrand.ui.AppBrandLauncherUI",
        "favorite" to "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI",
        "emoji" to "com.tencent.mm.plugin.emoji.ui.v3.EmojiStoreV3HomeUI",
        "settings" to "com.tencent.mm.ui.setting.SettingsUI",
        "shake" to "com.tencent.mm.plugin.shake.ui.ShakeReportUI",
        "snsuser" to "com.tencent.mm.plugin.sns.ui.SnsUserUI",
        "nearbyfriends" to "com.tencent.mm.plugin.nearby.ui.NearbyFriendsUI"
    )
}
