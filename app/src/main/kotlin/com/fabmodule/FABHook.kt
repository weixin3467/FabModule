package com.fabmodule

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Color
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

            hookAllMethods("android.app.Activity", "onResume",
                onAfter = { p ->
                    val a = p.thisObject as? android.app.Activity ?: return@hookAllMethods
                    val dm = a.resources.displayMetrics
                    density = dm.density; screenW = dm.widthPixels; screenH = dm.heightPixels
                    val cls = a.javaClass.name
                    if (cls.contains("chatting") || cls.contains("ChattingUI") ||
                        (cls.startsWith("com.tencent.mm.ui.chatting.") && cls.length > 35)) {
                        isInChat = true; stopKeepAlive(); removeFab()
                        return@hookAllMethods
                    }
                    if (!cls.contains("LauncherUI")) return@hookAllMethods
                    isInChat = false; layoutDone = false; waitLayout(a)
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
                            if (parent.height < 200) return
                            val loc = IntArray(2); v.getLocationInWindow(loc)
                            if (tryBlockTab(v, parent, loc))
                            { v.visibility = View.GONE; v.setTag(0x7F100002, true) }
                        }
                    })
                de.robv.android.xposed.XposedBridge.hookAllMethods(vw, "setVisibility",
                    object : de.robv.android.xposed.XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if ((param.args[0] as? Int) != View.VISIBLE) return
                            val v = param.thisObject as? View ?: return
                            if (v.getTag(0x7F100002) == true) { param.args[0] = View.GONE; return }
                            val parent = v.parent as? ViewGroup ?: return
                            if (parent.height < 200) return
                            val loc = IntArray(2); v.getLocationInWindow(loc)
                            if (tryBlockTab(v, parent, loc))
                            { param.args[0] = View.GONE; v.setTag(0x7F100002, true) }
                        }
                    })
            } catch (_: Throwable) {}

            Log.i("Setup: hooks installed, awaiting LauncherUI resume")
        } catch (e: Throwable) { Log.w("Setup: ${e.message}") }
    }

    // == Startup validation ==

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

    // == Tab detection — class-name-first, layout-as-fallback ==

    private fun tryBlockTab(v: View, p: ViewGroup, loc: IntArray): Boolean {
        // Try class-name match first
        val cn = v.javaClass.name
        if (cn.contains("Tab") || cn.contains("tab") || cn.contains("Bottom") || cn.contains("bottom"))
            if (tabLayoutMatch(v, p, loc)) return true
        // Fallback: pure layout match (no class-name dependency)
        return if (tabLayoutMatch(v, p, loc)) { Log.i("Tab fallback: ${v.javaClass.simpleName}"); true } else false
    }

    private fun tabLayoutMatch(v: View, p: ViewGroup, loc: IntArray): Boolean {
        val cBot = loc[1] + v.height
        return cBot in (p.height - 10)..(p.height + 10) &&
               v.width >= p.width * 0.95f &&
               v.layoutParams.height in 40.dp..120.dp
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
        hideTab(a); injectFab(a); startKeepAlive(a)
        handler.postDelayed({ hideTab(a) }, 1500)
        handler.postDelayed({ hideTab(a) }, 4000)
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
                if (c.isShown && c.width > 0) {
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

    // == KeepAlive ==

    private fun startKeepAlive(a: android.app.Activity) {
        stopKeepAlive()
        val r = object : Runnable {
            override fun run() {
                try {
                    if (a.isFinishing || a.isDestroyed || isInChat) return
                    (a.findViewById<ViewGroup>(android.R.id.content)
                        ?: return).findViewWithTag<View>(TAG_FAB) ?: injectFab(a)
                    handler.postDelayed(this, 2000)
                } catch (_: Throwable) { handler.postDelayed(this, 2000) }
            }
        }
        keepAliveRunnable = r; handler.postDelayed(r, 2000)
    }
    private fun stopKeepAlive() { keepAliveRunnable?.let { handler.removeCallbacks(it) }; keepAliveRunnable = null }
    private fun removeFab() { menuOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }; menuOverlay = null; menuOpen = false }

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
                setOnClickListener { showMenu(a) }
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
        menuOpen = true; toggleFabIcon(toClose = true)
        removeFab()
        val root = a.findViewById<ViewGroup>(android.R.id.content) ?: return
        val mW    = minOf(240.dp, screenW - 32.dp)
        val iconS = 28.dp; val fSize = 17f
        val pH    = 20.dp; val pV = 14.dp
        val rP    = 14.dp; val botM  = 155.dp; val rM = 16.dp
        val dH    = maxOf(1.dp, 1)

        val overlay = FrameLayout(a).apply {
            setOnClickListener { dismissMenu() }
            alpha = 0f; animate().alpha(1f).setDuration(200).start()
        }
        val menu = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD1E1E1E"))
            setPadding(pH, pV, pH, pV)
            translationY = 120f
            animate().translationY(0f).setDuration(250).setInterpolator(DecelerateInterpolator()).start()
        }
        val items = FabConfig.fabItems.ifEmpty { FabConfig.defaultItems }
        var idx = 0; val total = items.count { it.enable }
        for (item in items) {
            if (!item.enable) continue
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(rP, rP, rP, rP); setBackgroundColor(Color.TRANSPARENT)
                minimumHeight = 48.dp
                isClickable = true; isFocusable = true
                foreground = android.graphics.drawable.ColorDrawable(Color.parseColor("#10FFFFFF"))
                contentDescription = item.text
                setOnClickListener { dismissMenu(); go(a, item) }
            }
            iconKey(item.icon)?.let { FabConfig.iconBitmaps[it] }?.let { bmp ->
                row.addView(ImageView(a).apply {
                    setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_CENTER
                    setColorFilter(Color.WHITE); layoutParams = LinearLayout.LayoutParams(iconS, iconS)
                    contentDescription = item.text
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = 12.dp
                })
            }
            row.addView(TextView(a).apply {
                text = item.text; textSize = fSize; setTextColor(Color.WHITE)
            })
            menu.addView(row)
            if (++idx < total) menu.addView(View(a).apply {
                setBackgroundColor(Color.parseColor("#30FFFFFF"))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dH))
        }
        overlay.addView(menu, FrameLayout.LayoutParams(mW, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END or Gravity.BOTTOM; setMargins(0, 0, rM, botM) })
        root.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        menuOverlay = overlay
    }

    private fun dismissMenu() {
        menuOpen = false; toggleFabIcon(toClose = false)
        removeFab()
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
        "ic_person_add.png" -> "fab_addfriend"
        "ic_chat.png"       -> "fab_groupchat"
        "ic_扫一扫.png"      -> "fab_scan"
        "ic_收付款.png"      -> "fab_wallet"
        "朋友圈.png"         -> "fab_timeline"
        "fab_search"        -> "fab_search"
        "fab_addfriend"     -> "fab_addfriend"
        "fab_groupchat"     -> "fab_groupchat"
        "fab_scan"          -> "fab_scan"
        "fab_wallet"        -> "fab_wallet"
        "fab_timeline"      -> "fab_timeline"
        else -> null
    }

    // == Navigation ==

    private fun go(ctx: android.content.Context, item: FabConfig.FabItem) {
        try {
            val cn = map[item.type] ?: return
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
                    else Log.w("Activity not found: $cn")
                }
            }
        } catch (_: Throwable) {}
    }

    private val map = mapOf(
        "search" to "com.tencent.mm.plugin.fts.ui.FTSMainUI",
        "timeline" to "com.tencent.mm.plugin.sns.ui.SnsTimeLineUI",
        "scan" to "com.tencent.mm.plugin.scanner.ui.BaseScanUI",
        "walletcoin" to "com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI",
        "addfriend" to "com.tencent.mm.plugin.subapp.ui.friend.AddMoreFriendsUI",
        "appbrand" to "com.tencent.mm.plugin.appbrand.ui.AppBrandLauncherUI",
        "favorite" to "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI",
        "emoji" to "com.tencent.mm.plugin.emoji.ui.v3.EmojiStoreV3HomeUI",
        "settings" to "com.tencent.mm.ui.setting.SettingsUI",
        "shake" to "com.tencent.mm.plugin.shake.ui.ShakeReportUI",
        "snsuser" to "com.tencent.mm.plugin.sns.ui.SnsUserUI",
        "nearbyfriends" to "com.tencent.mm.plugin.nearby.ui.NearbyFriendsUI"
    )
}
