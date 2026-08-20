package ru.hivepatch.client.vpn

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import ru.hivepatch.client.data.Node

object XrayConfig {
    private val PRIVATE_CIDRS = listOf(
        "0.0.0.0/8",
        "10.0.0.0/8",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "224.0.0.0/4",
        "255.255.255.255/32",
        "::1/128",
        "fc00::/7",
        "fe80::/10",
    )

    fun parseVless(uri: String): VlessEndpoint {
        val parsed = Uri.parse(uri)
        if (parsed.scheme != "vless") throw IllegalArgumentException("не vless://")
        val uuid = Uri.decode(parsed.userInfo ?: "")
        val host = parsed.host ?: ""
        val port = if (parsed.port > 0) parsed.port else 443
        if (uuid.isBlank() || host.isBlank()) throw IllegalArgumentException("пустой host/uuid")
        fun q(key: String) = parsed.getQueryParameter(key)?.let { Uri.decode(it) }.orEmpty()
        val type = q("type").ifBlank { "tcp" }.lowercase()
        val security = q("security").ifBlank { "none" }.lowercase()
        var flow = q("flow")
        if (type in setOf("xhttp", "splithttp", "grpc", "ws", "httpupgrade", "h2", "kcp")) {
            flow = ""
        }
        return VlessEndpoint(
            uuid = uuid,
            host = host,
            port = port,
            encryption = q("encryption").ifBlank { "none" },
            flow = flow,
            network = if (type == "splithttp") "xhttp" else type,
            security = security,
            sni = q("sni"),
            fingerprint = q("fp").ifBlank { "chrome" },
            publicKey = q("pbk"),
            shortId = q("sid"),
            spiderX = q("spx").ifBlank { "/" },
            path = q("path").ifBlank { "/" },
            hostHeader = q("host"),
            mode = q("mode").ifBlank { "stream-one" },
            headerType = q("headerType").ifBlank { "none" },
        )
    }

    fun tunConfig(node: Node): String = fullConfig(parseVless(node.uri), withTun = true)

    fun probeConfig(node: Node): String = fullConfig(parseVless(node.uri), withTun = false)

    private fun fullConfig(ep: VlessEndpoint, withTun: Boolean): String {
        val inbounds = JSONArray()
        if (withTun) {
            inbounds.put(
                JSONObject()
                    .put("tag", "tun")
                    .put("protocol", "tun")
                    .put(
                        "settings",
                        JSONObject()
                            .put("name", "xray0")
                            .put("MTU", 1280)
                            .put("mtu", 1280)
                            .put("userLevel", 8),
                    )
                    .put(
                        "sniffing",
                        JSONObject()
                            .put("enabled", true)
                            .put("destOverride", JSONArray().put("http").put("tls").put("quic")),
                    ),
            )
        } else {
            inbounds.put(
                JSONObject()
                    .put("tag", "socks")
                    .put("listen", "127.0.0.1")
                    .put("port", 10808)
                    .put("protocol", "socks")
                    .put("settings", JSONObject().put("auth", "noauth").put("udp", true).put("userLevel", 8)),
            )
        }

        val stream = JSONObject().put("network", ep.network)
        if (ep.security == "reality" || ep.security == "tls") {
            stream.put("security", ep.security)
        }
        if (ep.security == "reality") {
            val reality = JSONObject()
                .put("serverName", ep.sni)
                .put("fingerprint", ep.fingerprint)
                .put("publicKey", ep.publicKey)
                .put("shortId", ep.shortId)
                .put("spiderX", ep.spiderX)
            stream.put("realitySettings", reality)
        } else if (ep.security == "tls") {
            stream.put(
                "tlsSettings",
                JSONObject()
                    .put("serverName", ep.sni)
                    .put("fingerprint", ep.fingerprint)
                    .put("allowInsecure", false),
            )
        }
        when (ep.network) {
            "xhttp" -> {
                val xhttp = JSONObject()
                    .put("path", ep.path)
                    .put("mode", ep.mode)
                if (ep.hostHeader.isNotBlank()) xhttp.put("host", ep.hostHeader)
                stream.put("xhttpSettings", xhttp)
            }
            "tcp", "raw" -> {
                stream.put(
                    "tcpSettings",
                    JSONObject().put("header", JSONObject().put("type", ep.headerType)),
                )
            }
            "ws" -> {
                stream.put(
                    "wsSettings",
                    JSONObject().put("path", ep.path).put("host", ep.hostHeader),
                )
            }
        }

        val user = JSONObject()
            .put("id", ep.uuid)
            .put("encryption", ep.encryption)
            .put("level", 8)
        if (ep.flow.isNotBlank()) user.put("flow", ep.flow)

        val proxy = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", ep.host)
                            .put("port", ep.port)
                            .put("users", JSONArray().put(user)),
                    ),
                ),
            )
            .put("streamSettings", stream)

        val outbounds = JSONArray()
            .put(proxy)
            .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            .put(
                JSONObject()
                    .put("tag", "block")
                    .put("protocol", "blackhole")
                    .put("settings", JSONObject().put("response", JSONObject().put("type", "http"))),
            )
            .put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))

        val privateIps = JSONArray()
        PRIVATE_CIDRS.forEach { privateIps.put(it) }

        val rules = JSONArray()
            .put(
                JSONObject()
                    .put("type", "field")
                    .put("port", 53)
                    .put("outboundTag", "dns-out"),
            )
            .put(
                JSONObject()
                    .put("type", "field")
                    .put("ip", privateIps)
                    .put("outboundTag", "direct"),
            )

        val root = JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("stats", JSONObject())
            .put(
                "policy",
                JSONObject()
                    .put(
                        "levels",
                        JSONObject().put(
                            "8",
                            JSONObject()
                                .put("handshake", 4)
                                .put("connIdle", 300)
                                .put("uplinkOnly", 1)
                                .put("downlinkOnly", 1),
                        ),
                    )
                    .put(
                        "system",
                        JSONObject()
                            .put("statsOutboundUplink", true)
                            .put("statsOutboundDownlink", true),
                    ),
            )
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put(
                "routing",
                JSONObject()
                    .put("domainStrategy", "AsIs")
                    .put("rules", rules),
            )
            .put(
                "dns",
                JSONObject()
                    .put("queryStrategy", "UseIPv4")
                    .put("disableFallbackIfMatch", false)
                    .put(
                        "servers",
                        JSONArray()
                            .put("1.1.1.1")
                            .put("8.8.8.8")
                            .put("localhost"),
                    ),
            )
        return root.toString()
    }
}

data class VlessEndpoint(
    val uuid: String,
    val host: String,
    val port: Int,
    val encryption: String,
    val flow: String,
    val network: String,
    val security: String,
    val sni: String,
    val fingerprint: String,
    val publicKey: String,
    val shortId: String,
    val spiderX: String,
    val path: String,
    val hostHeader: String,
    val mode: String,
    val headerType: String,
)
