package com.fabmodule

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
 * Drawer sidebar. Hamburger injected into decorView (window root).
 * WeChat ActionBar covers top-left of android.R.id.content — only decorView
 * floats above it reliably. Since decorView children persist across all
 * screens, we explicitly remove the hamburger on non-LauncherUI resume.
 */
class DrawerHook(lpparam: XC_LoadPackage.LoadPackageParam) : BaseHook(lpparam) {

    private val TAG_HAMBURGER = 0x7F100010
    private val TAG_DRAWER = 0x7F100011

    private var hamburgerView: ViewGroup? = null
    private var lineTop: View? = null; private var lineMid: View? = null; private var lineBot: View? = null
    private var drawerOverlay: ViewGroup? = null; private var drawerPanel: ViewGroup? = null
    private var density = 0f; private var statusBarH = 48
    private var drawerOpen = false
    private val handler = Handler(Looper.getMainLooper())

    // Generation bumped on every LauncherUI enter/leave — any pending delayed
    // injectHamburger call becomes a no-op after navigation away from LauncherUI.
    private var injectionGen = 0

    // Set to true once hamburger is successfully injected (prevents premature
    // removal-signal processing before the first layout completes).
    private var hamburgerLaidOut = false

    // Shared re-injection helper: extracts Activity from View/ViewGroup context
    private fun reInjectHamburger(origin: Any) {
        val a = when (origin) {
            is View -> origin.context
            is ViewGroup -> origin.context
            else -> return
        } as? android.app.Activity ?: return
        injectionGen++; val gen = injectionGen
        handler.postDelayed({ if (injectionGen == gen) injectHamburger(a) }, 300)
        handler.postDelayed({ if (injectionGen == gen) injectHamburger(a) }, 1200)
    }

    private val Int.dp: Int get() = (this * density + 0.5f).toInt()

    // Chat indicator views — when these are added, we're entering chat.
    // When removed, we're leaving chat and should re-inject hamburger.

    private val chatViewPatterns = listOf(
        "com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout",
        "com.tencent.mm.ui.chatting.view.MMChattingListView",
        "com.tencent.mm.pluginsdk.ui.chat.ChattingContent"
    )

    override fun install() {
        if (!DrawerConfig.enable) return

        // ═══════════════════════════════════════════════════════════
        // Layer 0: Fragment lifecycle — primary detection for 8.0.65+
        // ═══════════════════════════════════════════════════════════
        // WeChat 8.0.65: ChattingUI changed from Activity to Fragment.
        // BaseHook.installFragmentHooks() hooks all 3 Fragment base classes
        // (androidx, support-v4, android.app) with both public onResume/onPause
        // AND internal performResume/performPause.
        // ═══════════════════════════════════════════════════════════
        installFragmentHooks("Drawer",
            onEnter = {
                injectionGen++; removeHamburger()
                if (drawerOpen) closeDrawer()
            },
            onLeave = {
                if (hamburgerView != null) return@installFragmentHooks
                handler.postDelayed({
                    if (hamburgerView == null) {
                        val a = ChatState.launcherActivity ?: return@postDelayed
                        if (a.isFinishing || a.isDestroyed) return@postDelayed
                        injectionGen++; val gen = injectionGen
                        if (injectionGen == gen) injectHamburger(a)
                    }
                }, 500)
            })

        // Layer 1: addView chat view → first entry per session
        try {
            val vgClass = lpparam.classLoader.loadClass("android.view.ViewGroup")
            de.robv.android.xposed.XposedBridge.hookAllMethods(vgClass, "addView",
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val child = param.args[0] as? View ?: return
                        if (chatViewPatterns.any { child.javaClass.name == it }) {
                            Log.i("Drawer: +addView chat → remove")
                            injectionGen++; removeHamburger()
                            if (drawerOpen) closeDrawer()
                        }
                    }
                })
            Log.i("Drawer: addView chat spy OK")
        } catch (_: Throwable) { Log.w("Drawer: addView spy FAILED") }

        // Layer 2: removeView chat view → leaving chat, re-inject
        try {
            val vgClass = lpparam.classLoader.loadClass("android.view.ViewGroup")
            de.robv.android.xposed.XposedBridge.hookAllMethods(vgClass, "removeView",
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val child = param.args[0] as? View ?: return
                        if (chatViewPatterns.any { child.javaClass.name == it }) {
                            Log.i("Drawer: -removeView chat → re-inject")
                            reInjectHamburger(param.thisObject)
                        }
                    }
                })
            Log.i("Drawer: removeView chat spy OK")
        } catch (_: Throwable) { Log.w("Drawer: removeView spy FAILED") }

        // Layer 3: IME popup/hide
        try {
            val immClass = lpparam.classLoader.loadClass(
                "android.view.inputmethod.InputMethodManager")
            de.robv.android.xposed.XposedBridge.hookAllMethods(immClass, "showSoftInput",
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!hamburgerLaidOut) return
                        Log.i("Drawer: IME show → remove")
                        injectionGen++; removeHamburger()
                        if (drawerOpen) closeDrawer()
                    }
                })
            de.robv.android.xposed.XposedBridge.hookAllMethods(immClass, "hideSoftInputFromWindow",
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (hamburgerView != null) return
                        Log.i("Drawer: IME hide → re-inject")
                        val a = ChatState.launcherActivity ?: return
                        if (a.isFinishing || a.isDestroyed) return
                        injectionGen++; val gen = injectionGen
                        handler.postDelayed({
                            if (injectionGen == gen && !a.isFinishing && !a.isDestroyed)
                                injectHamburger(a)
                        }, 300)
                        handler.postDelayed({
                            if (injectionGen == gen && !a.isFinishing && !a.isDestroyed)
                                injectHamburger(a)
                        }, 1200)
                    }
                })
            Log.i("Drawer: IME spy OK")
        } catch (_: Throwable) { Log.w("Drawer: IME spy FAILED") }

        // Layer 4: Focus spy — MMEditText gets focus when entering chat
        // Catches subsequent entries where chat views are reused (no addView).
        try {
            val viewClass = lpparam.classLoader.loadClass("android.view.View")
            de.robv.android.xposed.XposedBridge.hookAllMethods(viewClass, "requestFocus",
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!hamburgerLaidOut) return
                        val cls = (param.thisObject as? View)?.javaClass?.name ?: return
                        if (cls == "com.tencent.mm.ui.widget.MMEditText" ||
                            cls == "com.tencent.mm.ui.widget.cedit.api.MMFlexEditText") {
                            Log.i("Drawer: focus $cls → remove")
                            injectionGen++; removeHamburger()
                            if (drawerOpen) closeDrawer()
                        }
                    }
                })
            Log.i("Drawer: Focus spy OK")
        } catch (_: Throwable) { Log.w("Drawer: Focus spy FAILED") }

        // Layer 5: View.setVisibility — catches Fragment.show()/hide().
        // When WeChat 8.0.65 shows/hides ChattingUIFragment via FragmentTransaction,
        // the fragment's root View gets setVisibility(VISIBLE/GONE). Our Fragment
        // base-class hooks never fire because WeChat overrides onResume without super.
        // StackTrace check (like XModule RemoveSelectionLimit) catches the fragment
        // manager dispatch from code paths containing "chatting".
        try {
            val viewClass = lpparam.classLoader.loadClass("android.view.View")
            de.robv.android.xposed.XposedBridge.hookAllMethods(viewClass, "setVisibility",
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!hamburgerLaidOut) return
                        val newVis = param.args[0] as? Int ?: return
                        val v = param.thisObject as? View ?: return
                        // Filter: only large views (fragment-roots, >half screen)
                        if (v.width < 300 || v.height < 300) return
                        val st = Thread.currentThread().stackTrace
                        val inChatContext = st.any { f ->
                            val cn = f.className
                            cn.contains("chatting") || cn.contains("ChattingUI") ||
                            cn.contains("Chatting") || cn.contains("chatroom")
                        }
                        if (!inChatContext) return
                        when (newVis) {
                            View.VISIBLE -> {
                                Log.i("Drawer: L5 setVisibility(VISIBLE) chat → remove")
                                injectionGen++; removeHamburger()
                                if (drawerOpen) closeDrawer()
                            }
                            View.GONE, View.INVISIBLE -> {
                                if (hamburgerView != null) return
                                Log.i("Drawer: L5 setVisibility(GONE) chat → re-inject")
                                handler.postDelayed({
                                    if (hamburgerView == null) {
                                        val a = ChatState.launcherActivity ?: return@postDelayed
                                        if (a.isFinishing || a.isDestroyed) return@postDelayed
                                        injectionGen++; val gen = injectionGen
                                        if (injectionGen == gen) injectHamburger(a)
                                    }
                                }, 500)
                            }
                        }
                    }
                })
            Log.i("Drawer: L5 View.setVisibility spy OK")
        } catch (_: Throwable) { Log.w("Drawer: L5 setVisibility spy FAILED") }

        // === Activity lifecycle — initial LauncherUI injection ===
        hookAllMethods("android.app.Activity", "onResume",
            onAfter = { p ->
                val a = p.thisObject as? android.app.Activity ?: return@hookAllMethods
                val cls = a.javaClass.name
                if (!cls.startsWith("com.tencent.mm.")) return@hookAllMethods
                Log.i("Drawer: resume $cls")

                // Grab display metrics on every resume
                val dm = a.resources.displayMetrics; density = dm.density
                val resId = a.resources.getIdentifier("status_bar_height", "dimen", "android")
                statusBarH = if (resId > 0) a.resources.getDimensionPixelSize(resId) else (24 * dm.density).toInt()

                // Fallback for older WeChat that still uses separate ChattingUI Activity
                if (cls.contains("chatting") || cls.contains("ChattingUI") ||
                    cls.contains("chatroom") || cls.contains("ChatRoomUI") ||
                    (cls.startsWith("com.tencent.mm.ui.chatting.") && cls.length > 35)) {
                    Log.i("Drawer: in chat via Activity ($cls)")
                    injectionGen++; removeHamburger();
                    if (drawerOpen) closeDrawer()
                    return@hookAllMethods
                }

                // Main LauncherUI — initial injection (subsequent returns handled by addView spy)
                if (cls.contains("LauncherUI")) {
                    injectionGen++
                    val gen = injectionGen
                    handler.postDelayed({ if (injectionGen == gen) injectHamburger(a) }, 300)
                    handler.postDelayed({ if (injectionGen == gen) injectHamburger(a) }, 1200)
                    handler.postDelayed({ if (injectionGen == gen) injectHamburger(a) }, 3000)
                    return@hookAllMethods
                }

                // Any other WeChat Activity — remove
                injectionGen++; removeHamburger();
                if (drawerOpen) closeDrawer()
            })

        Log.i("Drawer: installed")
    }

    private fun injectHamburger(a: android.app.Activity) {
        try {
            // Self-sufficient — compute density from current Activity if not yet set
            if (density <= 0f) {
                val dm = a.resources.displayMetrics; density = dm.density
                val resId = a.resources.getIdentifier("status_bar_height", "dimen", "android")
                statusBarH = if (resId > 0) a.resources.getDimensionPixelSize(resId)
                             else (24 * dm.density).toInt()
            }
            // decorView (window root) — floats above android.R.id.content
            // where WeChat ActionBar covers the top-left corner.
            val root = (a.window?.decorView as? ViewGroup) ?: return
            if (root.findViewWithTag<View>(TAG_HAMBURGER) != null) return

            val sz = 42.dp; val ml = 4.dp; val mt = 6.dp + statusBarH
            val lw = 20.dp; val lh = 3.dp; val lg = 5.dp
            val ctr = FrameLayout(a).apply {
                tag = TAG_HAMBURGER; setBackgroundColor(Color.parseColor("#88000000"))
                isClickable = true; isFocusable = true; elevation = 999f
                setOnClickListener { toggleDrawer(a) }
            }
            lineTop = View(a).apply { setBackgroundColor(Color.WHITE) }; ctr.addView(lineTop!!, FrameLayout.LayoutParams(lw, lh).apply { gravity = Gravity.CENTER; bottomMargin = lg })
            lineMid = View(a).apply { setBackgroundColor(Color.WHITE) }; ctr.addView(lineMid!!, FrameLayout.LayoutParams(lw, lh).apply { gravity = Gravity.CENTER })
            lineBot = View(a).apply { setBackgroundColor(Color.WHITE) }; ctr.addView(lineBot!!, FrameLayout.LayoutParams(lw, lh).apply { gravity = Gravity.CENTER; topMargin = lg })
            // Red dot indicator (top-right) — shown when Moments or Contacts has unread
            val dotSz = 8.dp; val dot = View(a).apply {
                setBackgroundColor(Color.parseColor("#FF453A"))
                visibility = View.GONE
            }
            ctr.addView(dot, FrameLayout.LayoutParams(dotSz, dotSz).apply {
                gravity = Gravity.END or Gravity.TOP; setMargins(0, 2.dp, 2.dp, 0)
            })
            ChatState.hamburgerDot = dot; ChatState.updateHamburgerDot()
            root.addView(ctr, FrameLayout.LayoutParams(sz, sz).apply { gravity = Gravity.START or Gravity.TOP; setMargins(ml, mt, 0, 0) })
            ctr.bringToFront(); hamburgerView = ctr
            hamburgerLaidOut = true
            Log.i("Drawer: ☰")
        } catch (t: Throwable) { Log.w("Drawer ☰: ${t.message}") }
    }

    /** Remove hamburger from decorView (needed because decorView children persist). */
    private fun removeHamburger() {
        hamburgerLaidOut = false
        ChatState.hamburgerDot = null
        hamburgerView?.let {
            try { (it.parent as? ViewGroup)?.removeView(it) } catch (_: Throwable) {}
        }
        hamburgerView = null
        lineTop = null; lineMid = null; lineBot = null
    }

    private fun toggleDrawer(a: android.app.Activity) { if (drawerOpen) closeDrawer() else openDrawer(a) }

    // ═══════════════════════════════════════════════════════════
    // Drawer open / close
    // ═══════════════════════════════════════════════════════════

    private fun openDrawer(a: android.app.Activity) {
        if (drawerOpen) return
        removeOverlay()
        drawerOpen = true; setArrowState(true)

        val items = DrawerConfig.items.ifEmpty { DrawerConfig.defaultItems }.filter { it.enable }
        val root = (a.window?.decorView as? ViewGroup) ?: return
        val pw = (DrawerConfig.widthDp * density + 0.5f).toInt()
        val ph = 16.dp; val pv = 12.dp; val g = 1.dp

        // Dim overlay — click to close
        val ov = FrameLayout(a).apply {
            tag = TAG_DRAWER
            setBackgroundColor(Color.parseColor("#88000000"))
            elevation = 1000f; isClickable = true; isFocusable = true
            setOnClickListener { closeDrawer() }
            alpha = 0f; animate().alpha(1f).setDuration(200).start()
        }

        // Sliding panel
        val pn = buildDrawerPanel(a, items, pw, ph, pv, g)

        ov.addView(pn, FrameLayout.LayoutParams(pw, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.START
        })
        root.addView(ov, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        drawerOverlay = ov; drawerPanel = pn
        pn.post {
            pn.animate().translationX(0f).setDuration(250)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false; setArrowState(false)
        drawerPanel?.animate()
            ?.translationX(-drawerPanel!!.width.toFloat())
            ?.setDuration(180)?.setInterpolator(DecelerateInterpolator())?.start()
        drawerOverlay?.animate()?.alpha(0f)?.setDuration(180)
            ?.withEndAction { removeOverlay() }?.start()
            ?: removeOverlay()
    }

    private fun removeOverlay() {
        drawerOverlay?.let {
            try { (it.parent as? ViewGroup)?.removeView(it) } catch (_: Throwable) {}
        }
        drawerOverlay = null; drawerPanel = null
    }

    /** Build the sliding panel with title, item list, and hint. */
    private fun buildDrawerPanel(
        a: android.app.Activity, items: List<DrawerConfig.DrawerItem>,
        pw: Int, ph: Int, pv: Int, g: Int
    ): LinearLayout {
        val pn = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE1E1E1E"))
            setPadding(0, 40.dp, 0, 0)
            translationX = -pw.toFloat()
        }
        // Title
        pn.addView(TextView(a).apply {
            text = "FabModule"; textSize = 16f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setPadding(ph, pv, ph, pv + 8.dp)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        pn.addView(View(a).apply {
            setBackgroundColor(Color.parseColor("#30FFFFFF"))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, g))

        // Scrollable item list
        val sc = ScrollView(a).apply { isVerticalScrollBarEnabled = false }
        val ls = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        for (itm in items) {
            ls.addView(buildDrawerItem(a, itm, ph, pv))
            ls.addView(View(a).apply {
                setBackgroundColor(Color.parseColor("#18FFFFFF"))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, g))
        }
        sc.addView(ls)
        pn.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Hint
        pn.addView(TextView(a).apply {
            text = "← 点击外部关闭"; textSize = 11f
            setTextColor(Color.parseColor("#60FFFFFF"))
            gravity = Gravity.CENTER; setPadding(0, pv, 0, pv)
        })
        return pn
    }

    /** Build a single drawer menu item row (icon + label + optional badge). */
    private fun buildDrawerItem(
        a: android.app.Activity, item: DrawerConfig.DrawerItem, ph: Int, pv: Int
    ): LinearLayout {
        val isz = 24.dp; val fs = 15f
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ph, pv, ph, pv); minimumHeight = 48.dp
            isClickable = true; isFocusable = true
            foreground = android.graphics.drawable.ColorDrawable(Color.parseColor("#18FFFFFF"))
            setOnClickListener { go(a, item); if (DrawerConfig.autoClose) closeDrawer() }
        }
        FabConfig.iconBitmaps[resolveIconKey(item.icon)]?.let { bm ->
            row.addView(ImageView(a).apply {
                setImageBitmap(bm); scaleType = ImageView.ScaleType.FIT_CENTER
                setColorFilter(Color.WHITE)
            }, LinearLayout.LayoutParams(isz, isz).apply { marginEnd = ph })
        }
        row.addView(TextView(a).apply {
            text = item.text; textSize = fs; setTextColor(Color.WHITE)
        })
        // Badge for Moments / Contacts
        val unread = when (item.type) {
            "timeline" -> ChatState.snsUnreadCount
            "tab_contacts" -> ChatState.contactsUnreadCount
            else -> 0
        }
        if (unread > 0) addBadgeToRow(a, row, unread)
        return row
    }

    /** Attach red unread-count badge to a [row]. */
    private fun addBadgeToRow(a: android.app.Activity, row: LinearLayout, count: Int) {
        val badgeS = 18.dp
        val cnt = if (count > 99) "99+" else count.toString()
        row.addView(TextView(a).apply {
            text = cnt; textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#FF453A"))
                shape = android.graphics.drawable.GradientDrawable.OVAL
            }
            val padH = if (cnt.length > 1) 4.dp else 0
            setPadding(padH, 0, padH, 0)
        }, LinearLayout.LayoutParams(badgeS, badgeS).apply { marginStart = 8.dp })
    }

    private fun setArrowState(toArrow: Boolean) {
        val tp = lineTop ?: return; val md = lineMid ?: return; val bt = lineBot ?: return
        if (toArrow) { tp.animate().rotation(45f).translationY(9.dp.toFloat()).setDuration(200).start(); md.animate().alpha(0f).setDuration(150).start(); bt.animate().rotation(-45f).translationY(-9.dp.toFloat()).setDuration(200).start() }
        else { tp.animate().rotation(0f).translationY(0f).setDuration(200).start(); md.animate().alpha(1f).setDuration(150).start(); bt.animate().rotation(0f).translationY(0f).setDuration(200).start() }
    }

    private fun resolveIconKey(icon: String): String { if (FabConfig.iconBitmaps.containsKey(icon)) return icon; return when (icon) { "ic_sousuo.png","fab_search"->"fab_search"; "ic_chat.png","fab_groupchat"->"fab_groupchat"; "ic_扫一扫.png","fab_scan"->"fab_scan"; "ic_收付款.png","fab_wallet"->"fab_wallet"; "朋友圈.png","fab_timeline"->"fab_timeline"; "tab_icon3.bak.png"->"drawer_contacts"; "shoucang1.png"->"drawer_favorite"; "表情商店.png"->"drawer_emoji"; "shezhu1.png"->"drawer_settings"; else -> icon } }
    private fun go(ctx: android.content.Context, item: DrawerConfig.DrawerItem) { try { if (item.action.isNotEmpty() && item.type == "custom") { ctx.startActivity(Intent().apply { setClassName("com.tencent.mm", item.action); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }); return }; when (item.type) { "tab_contacts"->{ ctx.startActivity(Intent().apply { setClassName("com.tencent.mm", "com.tencent.mm.ui.contact.SelectContactUI"); putExtra("list_type", 1); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }); return }; "tab_me"->{ ctx.startActivity(Intent().apply { setClassName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"); putExtra("LauncherUI.Show.Tab", 3); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) }); return }; "groupchat"->{ ctx.startActivity(Intent().apply { setClassName("com.tencent.mm", "com.tencent.mm.ui.contact.SelectContactUI"); putExtra("list_type", 0); putExtra("scene", 7); putExtra("list_attr", 4951); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }); return }; "switch_account"->{ ctx.startActivity(Intent().apply { setClassName("com.tencent.mm", "com.tencent.mm.ui.account.LoginByQRScanUI"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }); return } }; val cn = intentMap[item.type] ?: return; ctx.startActivity(Intent().apply { setClassName("com.tencent.mm", cn); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (_: Throwable) {} }
    private val intentMap = mapOf("search" to "com.tencent.mm.plugin.fts.ui.FTSMainUI", "timeline" to "com.tencent.mm.plugin.sns.ui.SnsTimeLineUI", "scan" to "com.tencent.mm.plugin.scanner.ui.BaseScanUI", "walletcoin" to "com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI", "wallet" to "com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI", "groupchat" to "com.tencent.mm.ui.contact.SelectContactUI", "appbrand" to "com.tencent.mm.plugin.appbrand.ui.AppBrandLauncherUI", "favorite" to "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI", "emoji" to "com.tencent.mm.plugin.emoji.ui.v3.EmojiStoreV3HomeUI", "settings" to "com.tencent.mm.plugin.setting.ui.setting.SettingsUI", "shake" to "com.tencent.mm.plugin.shake.ui.ShakeReportUI", "snsuser" to "com.tencent.mm.plugin.sns.ui.SnsUserUI", "nearbyfriends" to "com.tencent.mm.plugin.nearby.ui.NearbyFriendsUI", "video_channels" to "com.tencent.mm.plugin.finder.ui.FinderHomeUI")
}
