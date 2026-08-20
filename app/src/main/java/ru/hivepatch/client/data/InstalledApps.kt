package ru.hivepatch.client.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

data class AppEntry(
    val packageName: String,
    val label: String,
    val system: Boolean,
)

object SplitMode {
    const val ALL = "all"
    const val BYPASS = "bypass"
    const val ONLY = "only"
}

object InstalledApps {
    fun list(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
        val self = ctx.packageName
        return resolved.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == self) return@mapNotNull null
            if (pm.checkPermission(Manifest.permission.INTERNET, pkg) != PackageManager.PERMISSION_GRANTED) {
                return@mapNotNull null
            }
            val label = ri.loadLabel(pm).toString().ifBlank { pkg }
            val flags = ri.activityInfo.applicationInfo?.flags ?: 0
            val system = flags and ApplicationInfo.FLAG_SYSTEM != 0
            AppEntry(pkg, label, system)
        }.distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
