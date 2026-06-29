package com.fabmodule

import android.view.View

/** Shared state so FABHook and DrawerHook agree on whether we're in a chat. */
object ChatState {
    @Volatile var inChat = false
    @Volatile var launcherActivity: android.app.Activity? = null
    /** SNS (Moments/发现) unread count from bottom-tab scan. Shared by FAB menu + Drawer. */
    @Volatile var snsUnreadCount = 0
    /** Contacts (通讯录) tab unread count from bottom-tab scan. Shared by Drawer. */
    @Volatile var contactsUnreadCount = 0

    /** Hamburger red-dot View reference (set by DrawerHook, toggled on badge change). */
    @Volatile var hamburgerDot: View? = null

    /** Show/hide hamburger red dot based on whether any tab has unread. */
    fun updateHamburgerDot() {
        val dot = hamburgerDot ?: return
        val hasUnread = snsUnreadCount > 0 || contactsUnreadCount > 0
        dot.visibility = if (hasUnread) View.VISIBLE else View.GONE
    }
}
