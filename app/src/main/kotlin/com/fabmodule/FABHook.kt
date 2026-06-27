package com.fabmodule

import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * FAB overlay for WeChat.
 *
 * All view sizes use Android density-independent pixels (dp → px via `density`).
 * Follows Material Design: 56dp FAB, 240dp menu, 28dp icons, 17sp text, 48dp touch targets.
 */
class FABHook(lpparam: XC_LoadPackage.LoadPackageParam) : BaseHook(lpparam) {

    private val TAG_FAB = 0x7F100001
    private val handler = Handler(Looper.getMainLooper())
    private var keepAliveRunnable: Runnable? = null
    private var menuOverlay: ViewGroup? = null
    private var isInChat = false
    private var density = 2f    // default xhdpi
    private var screenW = 1080
    private var screenH = 1920

    private val Int.dp: Int get() = (this * density + 0.5f).toInt()

    override fun install() {
        try {
            hookAllMethods("android.app.Activity", "onResume",
                onAfter = { p ->
                    val a = p.thisObject as? android.app.Activity ?: return@hookAllMethods
                    val dm = a.resources.displayMetrics
                    density = dm.density; screenW = dm.widthPixels; screenH = dm.heightPixels
                    val cls = a.javaClass.name
                    if (cls.contains("chatting") || cls.contains("ChattingUI")) {
                        isInChat = true; stopKeepAlive(); removeFab()
                        return@hookAllMethods
                    }
                    if (!cls.contains("LauncherUI")) return@hookAllMethods
                    isInChat = false; waitLayout(a, 0)
                })

            // Tab hiding hooks — strict class-name + position match
            try {
                val vg = lpparam.classLoader.loadClass("android.view.ViewGroup")
                val vw = lpparam.classLoader.loadClass("android.view.View")
                de.robv.android.xposed.XposedBridge.hookAllMethods(vg, "addView",
                    object : de.robv.android.xposed.XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val v = param.args[0] as? View ?: return
                            val parent = param.thisObject as? ViewGroup ?: return
                            if (parent.height < 200) return
                            val cn = v.javaClass.name
                            if (!(cn.contains("Tab") || cn.contains("tab") ||
                                  cn.contains("Bottom") || cn.contains("bottom"))) return
                            val loc = IntArray(2); v.getLocationInWindow(loc)
                            if (tabMatch(v, parent, loc))
                            { v.visibility = View.GONE; v.setTag(0x7F100002, true) }
                        }
                    })
                de.robv.android.xposed.XposedBridge.hookAllMethods(vw, "setVisibility",
                    object : de.robv.android.xposed.XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if ((param.args[0] as? Int) != View.VISIBLE) return
                            val v = param.thisObject as? View ?: return
                            if (v.getTag(0x7F100002) == true) { param.args[0] = View.GONE; return }
                            val cn = v.javaClass.name
                            if (!(cn.contains("Tab") || cn.contains("tab") ||
                                  cn.contains("Bottom") || cn.contains("bottom"))) return
                            val parent = v.parent as? ViewGroup ?: return
                            if (parent.height < 200) return
                            val loc = IntArray(2); v.getLocationInWindow(loc)
                            if (tabMatch(v, parent, loc))
                            { param.args[0] = View.GONE; v.setTag(0x7F100002, true) }
                        }
                    })
            } catch (_: Throwable) {}

            Log.i("Setup: dpi=${(density*160).toInt()} w=$screenW h=$screenH")
        } catch (e: Throwable) { Log.w("Setup: ${e.message}") }
    }

    private fun tabMatch(v: View, p: ViewGroup, loc: IntArray): Boolean {
        val cBot = loc[1] + v.height
        return cBot in (p.height - 10)..(p.height + 10) &&
               v.width >= p.width * 0.95f &&
               v.height in 40.dp..120.dp
    }

    private fun waitLayout(a: android.app.Activity, attempt: Int) {
        val d = a.window?.decorView as? ViewGroup
        if (d != null && d.height > 200) { ready(a); return }
        if (attempt > 40) return
        d?.viewTreeObserver?.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout()
                { d.viewTreeObserver.removeOnGlobalLayoutListener(this); handler.post { ready(a) } }
            })
        handler.postDelayed({ waitLayout(a, attempt + 1) }, 500)
    }

    private fun ready(a: android.app.Activity) {
        if (isInChat) return
        hideTab(a); injectFab(a); startKeepAlive(a)
        handler.postDelayed({ hideTab(a) }, 1500)
        handler.postDelayed({ hideTab(a) }, 4000)
    }

    // == Tab ==

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
    private fun removeFab() { menuOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }; menuOverlay = null }

    // == FAB Button ==

    private fun injectFab(a: android.app.Activity) {
        if (isInChat) return
        try {
            val root = a.findViewById<ViewGroup>(android.R.id.content) ?: return
            root.findViewWithTag<View>(TAG_FAB)?.let { return }
            val bmp = FabConfig.iconBitmaps["fab_button"]
            // FAB: 64dp, 16dp edge, larger padding for bigger icon zone
            val sz = 64.dp; val pad = 14.dp; val mrg = 16.dp; val bot = 72.dp
            val iv = ImageView(a).apply {
                tag = TAG_FAB; scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(pad, pad, pad, pad)
                setBackgroundColor(Color.TRANSPARENT); isClickable = true; isFocusable = true
                setOnClickListener { showMenu(a) }
                if (bmp != null) { setImageBitmap(bmp); setColorFilter(Color.WHITE) }
            }
            root.addView(iv, FrameLayout.LayoutParams(sz, sz).apply {
                gravity = Gravity.END or Gravity.BOTTOM; setMargins(0, 0, mrg, mrg + bot) })
            iv.bringToFront(); Log.i("FAB+")
        } catch (t: Throwable) { Log.e("FAB+: ${t.message}") }
    }

    // == Menu Popup ==

    private fun showMenu(a: android.app.Activity) {
        removeFab()
        val root = a.findViewById<ViewGroup>(android.R.id.content) ?: return
        val mW    = minOf(240.dp, screenW - 32.dp) // 240dp, capped by screen
        val iconS = 28.dp                           // icon 28dp
        val fSize = 17f                             // text 17sp (density-scaled natively)
        val pH    = 20.dp; val pV = 14.dp           // menu padding
        val rP    = 14.dp                           // row padding
        val botM  = 155.dp; val rM = 16.dp          // positions
        val dH    = maxOf(1.dp, 1)                  // divider

        val overlay = FrameLayout(a).apply { setOnClickListener { removeFab() } }
        val menu = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD1E1E1E"))
            setPadding(pH, pV, pH, pV)
        }
        val items = FabConfig.fabItems.ifEmpty { FabConfig.defaultItems }
        var idx = 0; val total = items.count { it.enable }
        for (item in items) {
            if (!item.enable) continue
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(rP, rP, rP, rP); setBackgroundColor(Color.TRANSPARENT)
                minimumHeight = 48.dp // Material Design touch target
                setOnClickListener { removeFab(); go(a, item) }
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
            when (item.type) {
                "groupchat" -> ctx.startActivity(Intent().apply {
                    setClassName("com.tencent.mm", "com.tencent.mm.ui.contact.SelectContactUI")
                    putExtra("list_type", 0); putExtra("scene", 7); putExtra("list_attr", 4951)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                else -> map[item.type]?.let { cn -> ctx.startActivity(Intent().apply {
                    setClassName("com.tencent.mm", cn); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
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
