package ru.hivepatch.client.data

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var p: SharedPreferences

    fun init(ctx: Context) {
        p = ctx.getSharedPreferences("hivepatch", Context.MODE_PRIVATE)
    }

    var subUrl: String
        get() = p.getString("sub_url", "") ?: ""
        set(v) { p.edit().putString("sub_url", v).apply() }

    var lastNodeId: String
        get() = p.getString("last_node", "") ?: ""
        set(v) { p.edit().putString("last_node", v).apply() }

    var cachedGroup: String
        get() = p.getString("group_json", "") ?: ""
        set(v) { p.edit().putString("group_json", v).apply() }

    var trafficBytes: Long
        get() = p.getLong("traffic_bytes", 0L)
        set(v) { p.edit().putLong("traffic_bytes", v).apply() }

    var splitMode: String
        get() = p.getString("split_mode", SplitMode.ALL) ?: SplitMode.ALL
        set(v) { p.edit().putString("split_mode", v).apply() }

    var splitPackages: Set<String>
        get() = p.getStringSet("split_pkgs", emptySet())?.toSet() ?: emptySet()
        set(v) { p.edit().putStringSet("split_pkgs", HashSet(v)).apply() }
}
