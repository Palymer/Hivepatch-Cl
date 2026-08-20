package ru.hivepatch.client.data

import android.net.Uri
import org.json.JSONObject

data class Node(
    val id: String,
    val nodeId: String,
    val profile: String,
    val name: String,
    val uri: String,
    val port: Int,
    val network: String,
    val zone: String,
) {
    val isIntl: Boolean get() = zone != "ru"
    val shortLabel: String
        get() {
            val kind = when (profile) {
                "mobile" -> "443"
                "throne" -> "2054"
                else -> "31095"
            }
            return "${name.removePrefix("HivePatch-")} · $kind"
        }

    companion object {
        fun fromJson(obj: JSONObject): Node? {
            val uri = obj.optString("uri")
            if (!uri.startsWith("vless://")) return null
            return Node(
                id = obj.optString("id").ifBlank { uri },
                nodeId = obj.optString("node_id"),
                profile = obj.optString("profile"),
                name = obj.optString("name").ifBlank { uri },
                uri = uri,
                port = obj.optInt("port"),
                network = obj.optString("network"),
                zone = obj.optString("zone", "intl"),
            )
        }
    }
}

data class HiveGroup(
    val name: String,
    val probeUrl: String,
    val nodes: List<Node>,
) {
    companion object {
        fun parse(raw: String): HiveGroup {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("nodes") ?: throw IllegalStateException("нет nodes")
            val nodes = buildList {
                for (i in 0 until arr.length()) {
                    Node.fromJson(arr.getJSONObject(i))?.let(::add)
                }
            }
            if (nodes.isEmpty()) throw IllegalStateException("пустая группа")
            return HiveGroup(
                name = root.optString("name", "HivePatch"),
                probeUrl = root.optString("probe_url", "https://www.gstatic.com/generate_204"),
                nodes = nodes,
            )
        }
    }
}

fun normalizeSubUrl(input: String): String {
    val t = input.trim()
    if (t.startsWith("http://") || t.startsWith("https://")) {
        val uri = Uri.parse(t)
        val builder = uri.buildUpon().clearQuery()
        val names = uri.queryParameterNames
        for (name in names) {
            if (name == "fmt") continue
            builder.appendQueryParameter(name, uri.getQueryParameter(name))
        }
        builder.appendQueryParameter("fmt", "hive")
        return builder.build().toString()
    }
    val token = t.trim('/')
    return "https://vma.maybe-co.ru/sub/$token?fmt=hive"
}
