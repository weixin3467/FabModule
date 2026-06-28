package com.fabmodule

/** Shared state so FABHook and DrawerHook agree on whether we're in a chat. */
object ChatState {
    @Volatile var inChat = false
    @Volatile var launcherActivity: android.app.Activity? = null
    /** SNS unread count from bottom-tab scan. Shared by FAB menu + Drawer. */
    @Volatile var snsUnreadCount = 0
}
