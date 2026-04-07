package com.justme.xtls_core_proxy.config

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object ConfigBuilder {
    fun buildRuntimeConfig(input: String): String {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "Configuration input is empty" }

        return if (trimmed.startsWith("vless://", ignoreCase = true)) {
            fromVlessUri(trimmed)
        } else {
            fromJson(trimmed)
        }
    }

    fun fromVlessUri(uri: String): String {
        val profile = parseVlessUri(uri)
        return buildXrayJson(profile).toString()
    }

    fun fromJson(raw: String): String {
        val root = JSONObject(raw)
        val inbounds = root.optJSONArray("inbounds") ?: JSONArray()
        rejectLocalProxyInbounds(inbounds)

        root.put("inbounds", JSONArray().put(tunInboundJson()))

        if (!root.has("outbounds")) {
            throw IllegalArgumentException("Runtime config must include outbounds")
        }

        return root.toString()
    }

    private fun parseVlessUri(uri: String): VlessProfile {
        val parsed = URI(uri)
        if (!parsed.scheme.equals("vless", ignoreCase = true)) {
            throw IllegalArgumentException("Unsupported URI scheme: ${parsed.scheme}")
        }

        val uuid = parsed.userInfo?.trim().orEmpty()
        require(uuid.isNotEmpty()) { "Missing UUID in vless link" }

        val host = parsed.host?.trim().orEmpty()
        require(host.isNotEmpty()) { "Missing host in vless link" }

        val port = parsed.port
        require(port in 1..65535) { "Missing or invalid port in vless link" }

        val params = parseQuery(parsed.rawQuery)
        val security = params["security"]?.ifBlank { null } ?: "none"
        val network = params["type"]?.ifBlank { null } ?: "tcp"

        return VlessProfile(
            uuid = uuid,
            host = host,
            port = port,
            flow = params["flow"].orEmpty(),
            security = security,
            publicKey = params["pbk"],
            shortId = params["sid"],
            fingerprint = params["fp"]?.ifBlank { null } ?: "chrome",
            serverName = params["sni"]?.ifBlank { null } ?: host,
            network = network
        )
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&")
            .filter { it.isNotBlank() }
            .mapNotNull { pair ->
                val split = pair.split("=", limit = 2)
                if (split.isEmpty()) return@mapNotNull null
                val key = decode(split[0])
                if (key.isBlank()) return@mapNotNull null
                val value = if (split.size == 2) decode(split[1]) else ""
                key to value
            }
            .toMap()
    }

    private fun decode(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }

    private fun rejectLocalProxyInbounds(inbounds: JSONArray) {
        for (index in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(index) ?: continue
            val protocol = inbound.optString("protocol").lowercase()
            if (protocol == "socks" || protocol == "http") {
                throw IllegalArgumentException("Local $protocol inbound is not allowed in tun-only mode")
            }
        }
    }

    private fun buildXrayJson(profile: VlessProfile): JSONObject {
        val root = JSONObject()
        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
        })
        root.put("inbounds", JSONArray().put(tunInboundJson()))

        val outbounds = JSONArray()
        outbounds.put(buildVlessOutbound(profile))
        outbounds.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
        })
        outbounds.put(JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
        })
        root.put("outbounds", outbounds)

        root.put("routing", JSONObject().apply {
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "field")
                    put("ip", JSONArray().put("geoip:private"))
                    put("outboundTag", "direct")
                })
            })
        })
        return root
    }

    private fun buildVlessOutbound(profile: VlessProfile): JSONObject {
        val outbound = JSONObject()
        outbound.put("tag", "proxy")
        outbound.put("protocol", "vless")
        outbound.put("settings", JSONObject().apply {
            put("vnext", JSONArray().put(JSONObject().apply {
                put("address", profile.host)
                put("port", profile.port)
                put("users", JSONArray().put(JSONObject().apply {
                    put("id", profile.uuid)
                    put("encryption", "none")
                    if (profile.flow.isNotBlank()) {
                        put("flow", profile.flow)
                    }
                }))
            }))
        })

        val streamSettings = JSONObject()
        streamSettings.put("network", profile.network)
        streamSettings.put("security", profile.security)
        if (profile.security.equals("reality", ignoreCase = true)) {
            require(!profile.publicKey.isNullOrBlank()) { "Missing pbk for REALITY config" }
            streamSettings.put("realitySettings", JSONObject().apply {
                put("serverName", profile.serverName)
                put("fingerprint", profile.fingerprint)
                put("publicKey", profile.publicKey)
                put("shortId", profile.shortId ?: "")
            })
        }
        outbound.put("streamSettings", streamSettings)
        return outbound
    }

    private fun tunInboundJson(): JSONObject {
        return JSONObject().apply {
            put("tag", "tun-in")
            put("protocol", "tun")
            put("settings", JSONObject().apply {
                put("name", "xray_tun")
                put("network", "tcp,udp")
                put("MTU", 1500)
            })
        }
    }
}

data class VlessProfile(
    val uuid: String,
    val host: String,
    val port: Int,
    val flow: String,
    val security: String,
    val publicKey: String?,
    val shortId: String?,
    val fingerprint: String,
    val serverName: String,
    val network: String
)
