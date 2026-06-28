package com.fabmodule

import org.json.JSONObject
import java.util.zip.ZipFile

/**
 * Drawer config loader — same pattern as FabConfig:
 *   1. APK built-in res/raw/drawer_config.json (ZipFile → priority)
 *   2. SD card /sdcard/MDWechat/settings.json DrawerList section (fallback)
 */
object DrawerConfig {
    data class DrawerItem(
        val text: String, val type: String,
        val icon: String = "", val order: Int = 0,
        val enable: Boolean = true, val action: String = ""
    )

    @Volatile var enable = true
    @Volatile var widthDp = 180
    @Volatile var autoClose = true
    @Volatile var items: List<DrawerItem> = emptyList()

    val defaultItems: List<DrawerItem>
        get() = listOf(
            DrawerItem("朋友圈",  "timeline",     "fab_timeline",      0, true),
            DrawerItem("扫一扫",  "scan",         "fab_scan",          1, true),
            DrawerItem("通讯录",  "tab_contacts",  "drawer_contacts",  2, true),
            DrawerItem("收藏夹",  "favorite",     "drawer_favorite",   3, true),
            DrawerItem("表情",    "emoji",        "drawer_emoji",      4, true),
            DrawerItem("设置",    "settings",     "drawer_settings",   5, true),
        )

    fun autoLoad() {
        loadBuiltinConfig()                         // 1. APK built-in
        if (items.isEmpty()) findExternalConfig()   // 2. SD card fallback
        if (items.isEmpty()) items = defaultItems   // 3. defaults
        Log.i("Drawer: enable=$enable width=${widthDp}dp items=${items.size}")
    }

    // === APK built-in ===

    private fun loadBuiltinConfig() {
        val path = ModuleApk.path
        if (path.isEmpty()) { Log.w("DrawerConfig: no APK path"); return }
        try {
            ZipFile(path).use { zf ->
                val entry = zf.getEntry("res/raw/drawer_config.json")
                if (entry != null) {
                    val json = JSONObject(zf.getInputStream(entry).bufferedReader().use { it.readText() })
                    parseJson(json)
                    Log.i("DrawerConfig: built-in loaded")
                }
            }
        } catch (_: Throwable) {}
    }

    // === SD card fallback — DrawerList section from MDWechat settings.json ===

    private fun findExternalConfig(): Boolean {
        val roots = listOf(java.io.File("/sdcard"), java.io.File("/storage/emulated/0"))
        for (root in roots) {
            for (bn in listOf("MDWechat", "mdwechat")) {
                val base = java.io.File(root, bn)
                val direct = java.io.File(base, "settings.json")
                if (direct.exists()) { parseExternal(direct); return true }
                if (base.exists()) {
                    base.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                        val sf = java.io.File(sub, "settings.json")
                        if (sf.exists()) { parseExternal(sf); return true }
                    }
                }
            }
        }
        return false
    }

    private fun parseExternal(file: java.io.File) {
        try { parseJson(JSONObject(file.readText(Charsets.UTF_8))) }
        catch (e: Exception) { Log.w("DrawerConfig ext: ${e.message}") }
    }

    // === JSON parsing ===

    private fun parseJson(json: JSONObject) {
        enable = json.optBoolean("DrawerEnable", json.optBoolean("enable", true))
        widthDp = json.optInt("DrawerWidth", json.optInt("width", 180))
        autoClose = json.optBoolean("DrawerAutoClose", json.optBoolean("autoClose", true))

        // Try DrawerList.items (X.apk format) first, then items (simple format)
        val arr = json.optJSONObject("DrawerList")?.optJSONArray("items")
            ?: json.optJSONArray("items")
        if (arr != null) {
            val list = mutableListOf<DrawerItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("type") in skipTypes) continue
                list += DrawerItem(
                    text = o.optString("text"),
                    type = o.optString("type"),
                    icon = o.optString("icon"),
                    order = o.optInt("order", i),
                    enable = o.optBoolean("enable", true),
                    action = o.optString("action", "")
                )
            }
            if (list.isNotEmpty()) items = list
        }
    }

    private val skipTypes = setOf("wx_config_app", "wx_config_mark_read", "menu_header")
}
