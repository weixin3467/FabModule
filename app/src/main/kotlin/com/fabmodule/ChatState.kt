package com.fabmodule

import android.view.View
import java.lang.ref.WeakReference

/** Shared state so FABHook and DrawerHook agree on whether we're in a chat. */
object ChatState {
    @Volatile var inChat = false

    @Volatile private var _launcherActivity: WeakReference<android.app.Activity>? = null
    /** LauncherUI reference (weak — LauncherUI may be destroyed and recreated). */
    var launcherActivity: android.app.Activity?
        get() = _launcherActivity?.get()
        set(value) { _launcherActivity = value?.let { WeakReference(it) } }

    /** SNS (Moments/发现) unread count from bottom-tab scan. Shared by FAB menu + Drawer. */
    @Volatile var snsUnreadCount = 0
    /** Contacts (通讯录) tab unread count from bottom-tab scan. Shared by Drawer. */
    @Volatile var contactsUnreadCount = 0

    @Volatile private var _hamburgerDot: WeakReference<View>? = null
    /** Hamburger red-dot View reference (set by DrawerHook, toggled on badge change). */
    var hamburgerDot: View?
        get() = _hamburgerDot?.get()
        set(value) { _hamburgerDot = value?.let { WeakReference(it) } }

    /** Show/hide hamburger red dot based on whether any tab has unread. */
    fun updateHamburgerDot() {
        val dot = hamburgerDot ?: return  // WeakReference.get() handles null
        val hasUnread = snsUnreadCount > 0 || contactsUnreadCount > 0
        dot.visibility = if (hasUnread) View.VISIBLE else View.GONE
    }
}
