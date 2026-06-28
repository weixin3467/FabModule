package com.fabmodule

import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.*
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Hamburger on decorView topmost. Hide when back button appears, show when gone.
 * Back button detection: scan decorView for a small clickable view at top-left.
 */
class DrawerHook(lpparam: XC_LoadPackage.LoadPackageParam) : BaseHook(lpparam) {

    private val TAG_HAMBURGER = 0x7F100010
    private val TAG_DRAWER = 0x7F100011

    private var hamburgerView: ViewGroup? = null
    private var lineTop: View? = null; private var lineMid: View? = null; private var lineBot: View? = null
    private var drawerOverlay: ViewGroup? = null; private var drawerPanel: ViewGroup? = null
    private var density = 0f; private var statusBarH = 48
    private var drawerOpen = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pollRunnable: Runnable? = null

    private val Int.dp: Int get() = (this * density + 0.5f).toInt()

    override fun install() {
        if (!DrawerConfig.enable) return

        hookAllMethods("android.app.Activity", "onResume",
            onAfter = { p ->
                val a = p.thisObject as? android.app.Activity ?: return@hookAllMethods
                val dm = a.resources.displayMetrics; density = dm.density
                val resId = a.resources.getIdentifier("status_bar_height", "dimen", "android")
                statusBarH = if (resId > 0) a.resources.getDimensionPixelSize(resId) else (24 * dm.density).toInt()
                if (!a.javaClass.name.contains("LauncherUI")) return@hookAllMethods
                injectHamburger(a)
                startBackButtonPoll(a)
            })
        Log.i("Drawer: installed")
    }

    private fun startBackButtonPoll(a: android.app.Activity) {
        pollRunnable?.let { handler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                try {
                    if (a.isDestroyed || a.isFinishing) return
                    val hasBack = checkBackButton(a)
                    if (hasBack && hamburgerView != null) {
                        // Back button appeared → hide
                        removeHamburger()
                    } else if (!hasBack && hamburgerView == null) {
                        // Back button gone → show
                        injectHamburger(a)
                    }
                } catch (_: Throwable) {}
                handler.postDelayed(this, 500)
            }
        }
        pollRunnable = r
        handler.postDelayed(r, 500)
    }

    // Scan decorView for a small clickable view at top-left (back button)
    private fun checkBackButton(a: android.app.Activity): Boolean {
        try {
            val decor = a.window?.decorView as? ViewGroup ?: return false
            val result = scanForBackBtn(decor, 10)
            if (result) Log.i("DRW: backBtn detected")
            return result
        } catch (_: Throwable) { return false }
    }

    private fun scanForBackBtn(parent: ViewGroup, depth: Int): Boolean {
        if (depth <= 0) return false
        for (i in 0 until parent.childCount) {
            val c = parent.getChildAt(i) ?: continue
            // Small clickable view in top-left quadrant = back button
            if (c.isClickable && c.isShown && c.width in 20.dp..160.dp && c.height in 20.dp..160.dp) {
                val loc = IntArray(2); c.getLocationInWindow(loc)
                if (loc[0] in 0..120.dp && loc[1] in (statusBarH - 10)..(statusBarH + 120.dp))
                    return true
            }
            if (c is ViewGroup && scanForBackBtn(c, depth - 1)) return true
        }
        return false
    }

    private fun removeHamburger() {
        hamburgerView?.let { try { (it.parent as? ViewGroup)?.removeView(it) } catch (_: Throwable) {} }
        hamburgerView = null; lineTop = null; lineMid = null; lineBot = null
        Log.i("DRW: hamburger removed")
    }

    private fun injectHamburger(a: android.app.Activity) {
        try {
            val decor = (a.window?.decorView as? ViewGroup) ?: return
            if (decor.findViewWithTag<View>(TAG_HAMBURGER) != null) return

            val sz = 42.dp; val ml = 4.dp; val mt = statusBarH + 4.dp
            val lw = 20.dp; val lh = 3.dp; val lg = 5.dp

            val ctr = FrameLayout(a).apply {
                tag = TAG_HAMBURGER; setBackgroundColor(Color.parseColor("#DD1976D2"))
                isClickable = true; isFocusable = true; elevation = 999f
                setOnClickListener { toggleDrawer(a) }
            }
            lineTop = View(a).apply { setBackgroundColor(Color.WHITE) }.also {
                ctr.addView(it, FrameLayout.LayoutParams(lw, lh).apply { gravity = Gravity.CENTER; bottomMargin = lg }) }
            lineMid = View(a).apply { setBackgroundColor(Color.WHITE) }.also {
                ctr.addView(it, FrameLayout.LayoutParams(lw, lh).apply { gravity = Gravity.CENTER }) }
            lineBot = View(a).apply { setBackgroundColor(Color.WHITE) }.also {
                ctr.addView(it, FrameLayout.LayoutParams(lw, lh).apply { gravity = Gravity.CENTER; topMargin = lg }) }

            decor.addView(ctr, FrameLayout.LayoutParams(sz, sz).apply {
                gravity = Gravity.START or Gravity.TOP; setMargins(ml, mt, 0, 0) })
            ctr.bringToFront()
            hamburgerView = ctr
            Log.i("Drawer: ☰")
        } catch (t: Throwable) { Log.w("Drawer ☰: ${t.message}") }
    }

    private fun toggleDrawer(a: android.app.Activity) { if (drawerOpen) closeDrawer() else openDrawer(a) }

    private fun openDrawer(a: android.app.Activity) {
        if (drawerOpen) return
        removeOverlay(); drawerOpen = true; setArrowState(true)
        val items = DrawerConfig.items.ifEmpty { DrawerConfig.defaultItems }.filter { it.enable }
        val root = (a.window?.decorView as? ViewGroup) ?: return
        val pw = (DrawerConfig.widthDp * density + 0.5f).toInt()
        val isz = 24.dp; val fs = 15f; val ph = 16.dp; val pv = 12.dp; val g = 1.dp
        val ov = FrameLayout(a).apply { tag = TAG_DRAWER; setBackgroundColor(Color.parseColor("#88000000")); elevation = 1000f; isClickable = true; isFocusable = true; setOnClickListener { closeDrawer() }; alpha = 0f; animate().alpha(1f).setDuration(200).start() }
        val pn = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#EE1E1E1E")); setPadding(0, 40.dp, 0, 0); translationX = -pw.toFloat() }
        pn.addView(TextView(a).apply { text = "FabModule"; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(ph, pv, ph, pv + 8.dp); setTypeface(null, android.graphics.Typeface.BOLD) })
        pn.addView(View(a).apply { setBackgroundColor(Color.parseColor("#30FFFFFF")) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, g))
        val sc = ScrollView(a).apply { isVerticalScrollBarEnabled = false }
        val ls = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        for (itm in items) { val rw = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ph, pv, ph, pv); minimumHeight = 48.dp; isClickable = true; isFocusable = true; foreground = android.graphics.drawable.ColorDrawable(Color.parseColor("#18FFFFFF")); setOnClickListener { go(a, itm); if (DrawerConfig.autoClose) closeDrawer() } }; val bm = FabConfig.iconBitmaps[resolveIconKey(itm.icon)]; if (bm != null) rw.addView(ImageView(a).apply { setImageBitmap(bm); scaleType = ImageView.ScaleType.FIT_CENTER; setColorFilter(Color.WHITE); layoutParams = LinearLayout.LayoutParams(isz, isz).apply { marginEnd = ph } }); rw.addView(TextView(a).apply { text = itm.text; textSize = fs; setTextColor(Color.WHITE) }); ls.addView(rw); ls.addView(View(a).apply { setBackgroundColor(Color.parseColor("#18FFFFFF")) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, g)) }
        sc.addView(ls); pn.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        pn.addView(TextView(a).apply { text = "← 点击外部关闭"; textSize = 11f; setTextColor(Color.parseColor("#60FFFFFF")); gravity = Gravity.CENTER; setPadding(0, pv, 0, pv) })
        ov.addView(pn, FrameLayout.LayoutParams(pw, FrameLayout.LayoutParams.MATCH_PARENT).apply { gravity = Gravity.START })
        root.addView(ov, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        drawerOverlay = ov; drawerPanel = pn; pn.post { pn.animate().translationX(0f).setDuration(250).setInterpolator(DecelerateInterpolator()).start() }
    }

    private fun closeDrawer() { if (!drawerOpen) return; drawerOpen = false; setArrowState(false); drawerPanel?.animate()?.translationX(-drawerPanel!!.width.toFloat())?.setDuration(180)?.setInterpolator(DecelerateInterpolator())?.start(); drawerOverlay?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction { removeOverlay() }?.start() ?: removeOverlay() }
    private fun removeOverlay() { drawerOverlay?.let { try { (it.parent as? ViewGroup)?.removeView(it) } catch (_: Throwable) {} }; drawerOverlay = null; drawerPanel = null }

    private fun setArrowState(toArrow: Boolean) {
        val tp = lineTop ?: return; val md = lineMid ?: return; val bt = lineBot ?: return
        if (toArrow) { tp.animate().rotation(45f).translationY(9.dp.toFloat()).setDuration(200).start(); md.animate().alpha(0f).setDuration(150).start(); bt.animate().rotation(-45f).translationY(-9.dp.toFloat()).setDuration(200).start() }
        else { tp.animate().rotation(0f).translationY(0f).setDuration(200).start(); md.animate().alpha(1f).setDuration(150).start(); bt.animate().rotation(0f).translationY(0f).setDuration(200).start() }
    }

    private fun resolveIconKey(icon: String): String { if (FabConfig.iconBitmaps.containsKey(icon)) return icon; return when (icon) { "ic_sousuo.png","fab_search"->"fab_search"; "ic_chat.png","fab_groupchat"->"fab_groupchat"; "ic_扫一扫.png","fab_scan"->"fab_scan"; "ic_收付款.png","fab_wallet"->"fab_wallet"; "朋友圈.png","fab_timeline"->"fab_timeline"; "tab_icon3.bak.png"->"drawer_contacts"; "shoucang1.png"->"drawer_favorite"; "表情商店.png"->"drawer_emoji"; "shezhu1.png"->"drawer_settings"; else -> icon } }
    private fun go(ctx: android.content.Context, item: DrawerConfig.DrawerItem) { try { if (item.action.isNotEmpty() && item.type == "custom") { ctx.startActivity(Intent().apply { setClassName("com.tencent.mm", item.action); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }); return }; when (item.type) { "tab_contacts"->{ ctx.startActivity(Intent().apply { setClassName("com.tencent.mm","com.tencent.mm.ui.contact.SelectContactUI"); putExtra("list_type", 1); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }); return }; "tab_me"->{ ctx.startActivity(Intent().apply { setClassName("com.tencent.mm","com.tencent.mm.ui.LauncherUI"); putExtra("LauncherUI.Show.Tab", 3); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) }); return }; "groupchat"->{ ctx.startActivity(Intent().apply { setClassName("com.tencent.mm","com.tencent.mm.ui.contact.SelectContactUI"); putExtra("list_type", 0); putExtra("scene", 7); putExtra("list_attr", 4951); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }); return }; "switch_account"->{ ctx.startActivity(Intent().apply { setClassName("com.tencent.mm","com.tencent.mm.ui.account.LoginByQRScanUI"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }); return } }; val cn = intentMap[item.type] ?: return; ctx.startActivity(Intent().apply { setClassName("com.tencent.mm", cn); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (_: Throwable) {} }
    private val intentMap = mapOf("search" to "com.tencent.mm.plugin.fts.ui.FTSMainUI","timeline" to "com.tencent.mm.plugin.sns.ui.SnsTimeLineUI","scan" to "com.tencent.mm.plugin.scanner.ui.BaseScanUI","walletcoin" to "com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI","wallet" to "com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI","groupchat" to "com.tencent.mm.ui.contact.SelectContactUI","appbrand" to "com.tencent.mm.plugin.appbrand.ui.AppBrandLauncherUI","favorite" to "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI","emoji" to "com.tencent.mm.plugin.emoji.ui.v3.EmojiStoreV3HomeUI","settings" to "com.tencent.mm.plugin.setting.ui.setting.SettingsUI","shake" to "com.tencent.mm.plugin.shake.ui.ShakeReportUI","snsuser" to "com.tencent.mm.plugin.sns.ui.SnsUserUI","nearbyfriends" to "com.tencent.mm.plugin.nearby.ui.NearbyFriendsUI","video_channels" to "com.tencent.mm.plugin.finder.ui.FinderHomeUI")
}
