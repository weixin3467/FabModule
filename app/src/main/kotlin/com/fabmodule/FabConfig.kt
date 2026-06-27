package com.fabmodule

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.json.JSONObject
import java.util.zip.ZipFile

object FabConfig {
    data class FabItem(
        val text: String, val type: String,
        val icon: String = "", val order: Int = 0, val enable: Boolean = true
    )

    @Volatile var fabEnable = true
    @Volatile var fabColor = Color.parseColor("#FF6F00")
    @Volatile var iconPath = ""
    @Volatile var fabItems: List<FabItem> = emptyList()
    @Volatile var iconBitmaps = mutableMapOf<String, Bitmap>()

    val defaultItems: List<FabItem>
        get() = listOf(
            FabItem("搜索",   "search",      "fab_search",    0, true),

            FabItem("群聊",   "groupchat",   "fab_groupchat", 2, true),
            FabItem("扫一扫", "scan",        "fab_scan",      3, true),
            FabItem("收付款", "walletcoin",  "fab_wallet",    4, true),
            FabItem("朋友圈", "timeline",    "fab_timeline",  5, true),
        )

    fun autoLoad() {
        fabItems = defaultItems
        loadBuiltinConfig()                         // APK 内置优先
        if (fabItems.isEmpty()) findExternalConfig() // SD 卡兜底
        loadBuiltinIcons()
        if (fabItems.isEmpty()) fabItems = defaultItems
    }

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

    private fun loadBuiltinConfig() {
        val path = ModuleApk.path
        if (path.isEmpty()) { Log.w("FabConfig: no APK path, using defaults"); return }
        try {
            ZipFile(path).use { zf ->
                val entry = zf.getEntry("res/raw/fab_config.json")
                if (entry != null) {
                    val json = JSONObject(zf.getInputStream(entry).bufferedReader().use { it.readText() })
                    parseJson(json)
                    Log.i("FabConfig: built-in (${fabItems.size} items)")
                    return
                }
            }
        } catch (_: Throwable) {}
        Log.w("FabConfig: no built-in config, using defaults")
    }

    private fun loadBuiltinIcons() {
        val path = ModuleApk.path
        if (path.isEmpty()) { Log.w("FabConfig: no APK path for icons"); return }
        try {
            ZipFile(path).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val n = e.name
                    if (n.startsWith("res/drawable/fab_") && n.endsWith(".png")) {
                        iconBitmaps[n.substringAfterLast("/").removeSuffix(".png")] =
                            BitmapFactory.decodeStream(zf.getInputStream(e)) ?: continue
                    }
                }
            }
            Log.i("FabConfig: ${iconBitmaps.size} icons from APK")
        } catch (t: Throwable) { Log.e("FabConfig icons: ${t.message}") }
    }

    private fun parseExternal(file: java.io.File) {
        try {
            parseJson(JSONObject(file.readText(Charsets.UTF_8)))
            iconPath = (file.parentFile?.absolutePath ?: "") + "/icons/"
            Log.i("FabConfig: ${fabItems.size} items from ${file.absolutePath}")
        } catch (e: Exception) { Log.w("FabConfig: ${e.message}") }
    }

    private fun parseJson(json: JSONObject) {
        fabEnable = true
        try { fabColor = Color.parseColor("#${json.optString("FloatingActionMenuColor", "")}") } catch (_: Throwable) {}

        val items = mutableListOf<FabItem>()
        json.optJSONObject("FloatingActionButton")?.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val type = o.optString("type")
                if (type in skipTypes) continue
                items += FabItem(o.optString("text"), type, o.optString("icon"), o.optInt("order", i), o.optBoolean("enable", true))
            }
        }
        if (items.isNotEmpty()) fabItems = items
    }

    private val skipTypes = setOf("wx_config_app", "wx_config_mark_read")
}
