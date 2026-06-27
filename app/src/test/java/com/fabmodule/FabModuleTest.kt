package com.fabmodule

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for FabModule core logic — pure JVM, no Android runtime required.
 */
class FabModuleTest {

    // === AntiDetection.shouldHide ===

    @Test
    fun `shouldHide returns false for null or empty`() {
        assertFalse(AntiDetection.shouldHide(null))
        assertFalse(AntiDetection.shouldHide(""))
    }

    @Test
    fun `shouldHide returns false for whitespace only`() {
        assertFalse(AntiDetection.shouldHide("   "))
    }

    @Test
    fun `shouldHide blocks module package`() {
        assertTrue(AntiDetection.shouldHide("com.fabmodule.Entry"))
        assertTrue(AntiDetection.shouldHide("com.fabmodule.hook.theme.FABHook"))
    }

    @Test
    fun `shouldHide blocks Xposed package`() {
        assertTrue(AntiDetection.shouldHide("de.robv.android.xposed.XposedBridge"))
    }

    @Test
    fun `shouldHide blocks LSPosed package`() {
        assertTrue(AntiDetection.shouldHide("org.lsposed.manager.ui.MainActivity"))
    }

    @Test
    fun `shouldHide blocks Dreamland`() {
        assertTrue(AntiDetection.shouldHide("top.canyie.dreamland.manager.MainActivity"))
    }

    @Test
    fun `shouldHide blocks Riru`() {
        assertTrue(AntiDetection.shouldHide("com.elderdrivers.riru.core.Riru"))
    }

    @Test
    fun `shouldHide does not block WeChat`() {
        assertFalse(AntiDetection.shouldHide("com.tencent.mm.ui.LauncherUI"))
        assertFalse(AntiDetection.shouldHide("com.tencent.mm.plugin.sns.ui.SnsTimeLineUI"))
    }

    @Test
    fun `shouldHide does not block normal apps`() {
        assertFalse(AntiDetection.shouldHide("com.android.launcher3.Launcher"))
        assertFalse(AntiDetection.shouldHide("com.google.android.gms"))
        assertFalse(AntiDetection.shouldHide("com.example.myapp.MainActivity"))
    }

    @Test
    fun `shouldHide is case insensitive`() {
        assertTrue(AntiDetection.shouldHide("COM.FABMODULE.Entry"))
        assertTrue(AntiDetection.shouldHide("DE.ROBV.ANDROID.XPOSED.XposedBridge"))
    }

    @Test
    fun `shouldHide with package substring`() {
        // Should block any name containing the pattern, not just exact match
        assertTrue(AntiDetection.shouldHide("com.fabmodule"))
        assertTrue(AntiDetection.shouldHide("com.fabmodule.sub.Hook"))
    }

    @Test
    fun `shouldHide mixed case`() {
        assertTrue(AntiDetection.shouldHide("Com.FabModule.Entry"))
        assertTrue(AntiDetection.shouldHide("De.Robv.Android.Xposed.XposedBridge"))
    }

    // === FabConfig.FabItem (pure data class, no runtime dependency) ===

    @Test
    fun `FabItem constructor sets all fields`() {
        val item = FabConfig.FabItem("测试", "test", "icon.png", 5, true)
        assertEquals("测试", item.text)
        assertEquals("test", item.type)
        assertEquals("icon.png", item.icon)
        assertEquals(5, item.order)
        assertTrue(item.enable)
    }

    @Test
    fun `FabItem default values`() {
        val item = FabConfig.FabItem("test", "test")
        assertEquals("", item.icon)
        assertEquals(0, item.order)
        assertTrue(item.enable)
    }

    @Test
    fun `FabItem copy preserves fields`() {
        val item = FabConfig.FabItem("a", "b", "c", 1, false)
        val copied = item.copy(text = "new")
        assertEquals("new", copied.text)
        assertEquals("b", copied.type)
        assertEquals("c", copied.icon)
        assertEquals(1, copied.order)
        assertFalse(copied.enable)
    }

    @Test
    fun `FabItem equals works correctly`() {
        val a = FabConfig.FabItem("搜索", "search", "fab_search", 0, true)
        val b = FabConfig.FabItem("搜索", "search", "fab_search", 0, true)
        assertEquals(a, b)
    }
}
