package com.justme.xtls_core_proxy.config

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class ConfigKind {
    VLESS_URI,
    JSON
}

data class SimpleServerFields(
    val uuid: String,
    val host: String,
    val port: String,
    val flow: String,
    val security: String,
    val publicKey: String,
    val shortId: String,
    val fingerprint: String,
    val serverName: String,
    val network: String,
    val alpn: String = "",
    val spiderX: String = "",
    val allowInsecure: String = "false",
    val transportPath: String = "",
    val transportHost: String = "",
    val grpcServiceName: String = "",
    val grpcAuthority: String = "",
    val kcpSeed: String = "",
    val quicKey: String = "",
    val xhttpExtraJson: String = "",
    val finalmaskJson: String = "",
    val encryption: String = ""
) {
    fun toVlessProfile(): VlessProfile {
        val parsedPort = port.toIntOrNull()
            ?: throw IllegalArgumentException("Port must be a number")
        require(parsedPort in 1..65535) { "Port must be between 1 and 65535" }
        require(uuid.isNotBlank()) { "UUID is required" }
        require(host.isNotBlank()) { "Server host is required" }
        val normalizedSecurity = security.ifBlank { "none" }
        if (normalizedSecurity.equals("reality", ignoreCase = true)) {
            require(publicKey.isNotBlank()) { "Missing pbk for REALITY config" }
        }
        val normalizedXhttpExtra = parseJsonObjectOrNull("XHTTP extra", xhttpExtraJson)
        val normalizedFinalmask = parseJsonObjectOrNull("FinalMask", finalmaskJson)
        return VlessProfile(
            uuid = uuid.trim(),
            host = host.trim(),
            port = parsedPort,
            flow = flow.trim(),
            security = normalizedSecurity,
            publicKey = publicKey.trim().ifBlank { null },
            shortId = shortId.trim().ifBlank { null },
            fingerprint = fingerprint.trim().ifBlank { "chrome" },
            serverName = serverName.trim().ifBlank { host.trim() },
            network = network.trim().ifBlank { "tcp" },
            alpn = alpn.trim(),
            spiderX = spiderX.trim().ifBlank { null },
            allowInsecure = allowInsecure.trim().equals("true", ignoreCase = true),
            transportPath = transportPath.trim().ifBlank { null },
            transportHost = transportHost.trim().ifBlank { null },
            grpcServiceName = grpcServiceName.trim().ifBlank { null },
            grpcAuthority = grpcAuthority.trim().ifBlank { null },
            kcpSeed = kcpSeed.trim().ifBlank { null },
            quicKey = quicKey.trim().ifBlank { null },
            xhttpExtraJson = normalizedXhttpExtra,
            finalmaskJson = normalizedFinalmask,
            encryption = encryption.trim().ifBlank { "none" }
        )
    }

    private fun parseJsonObjectOrNull(fieldLabel: String, rawValue: String): String? {
        val trimmed = rawValue.trim()
        if (trimmed.isBlank()) return null
        return try {
            JSONObject(trimmed)
            trimmed
        } catch (_: Exception) {
            throw IllegalArgumentException("$fieldLabel must be a valid JSON object")
        }
    }

    companion object {
        fun fromVlessProfile(profile: VlessProfile): SimpleServerFields {
            return SimpleServerFields(
                uuid = profile.uuid,
                host = profile.host,
                port = profile.port.toString(),
                flow = profile.flow,
                security = profile.security,
                publicKey = profile.publicKey.orEmpty(),
                shortId = profile.shortId.orEmpty(),
                fingerprint = profile.fingerprint,
                serverName = profile.serverName,
                network = profile.network,
                alpn = profile.alpn,
                spiderX = profile.spiderX.orEmpty(),
                allowInsecure = if (profile.allowInsecure) "true" else "false",
                transportPath = profile.transportPath.orEmpty(),
                transportHost = profile.transportHost.orEmpty(),
                grpcServiceName = profile.grpcServiceName.orEmpty(),
                grpcAuthority = profile.grpcAuthority.orEmpty(),
                kcpSeed = profile.kcpSeed.orEmpty(),
                quicKey = profile.quicKey.orEmpty(),
                xhttpExtraJson = profile.xhttpExtraJson.orEmpty(),
                finalmaskJson = profile.finalmaskJson.orEmpty(),
                encryption = profile.encryption
            )
        }
    }
}

object ProfileConfigCodec {
    fun detectKind(input: String): ConfigKind {
        return if (input.trim().startsWith("vless://", ignoreCase = true)) {
            ConfigKind.VLESS_URI
        } else {
            ConfigKind.JSON
        }
    }

    fun extractVlessProfile(input: String): VlessProfile {
        return when (detectKind(input)) {
            ConfigKind.VLESS_URI -> parseVlessUri(input)
            ConfigKind.JSON -> parseVlessProfileFromJson(input)
        }
    }

    fun applyBasicEdits(originalConfig: String, updatedProfile: VlessProfile): String {
        val baseJson = when (detectKind(originalConfig)) {
            ConfigKind.VLESS_URI -> ConfigBuilder.fromVlessUri(originalConfig)
            ConfigKind.JSON -> originalConfig
        }
        return mergeVlessProfileIntoJson(baseJson, updatedProfile)
    }

    fun parseVlessUri(uri: String): VlessProfile {
        val parsed = URI(uri.trim())
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
            network = network,
            alpn = params["alpn"].orEmpty(),
            spiderX = params["spx"],
            allowInsecure = params["allowInsecure"]?.equals("1") == true,
            transportPath = params["path"],
            transportHost = params["host"],
            grpcServiceName = params["serviceName"],
            kcpSeed = params["seed"],
            quicKey = params["key"],
            encryption = params["encryption"]?.ifBlank { null } ?: "none",
            xhttpExtraJson = params["extra"]?.ifBlank { null }
        )
    }

    fun toVlessUri(profile: VlessProfile): String {
        val params = linkedMapOf(
            "type" to profile.network.ifBlank { "tcp" },
            "security" to profile.security.ifBlank { "none" }
        )
        if (profile.encryption.isNotBlank() && !profile.encryption.equals("none", ignoreCase = true)) {
            params["encryption"] = profile.encryption
        }
        if (profile.flow.isNotBlank()) params["flow"] = profile.flow
        if (profile.serverName.isNotBlank() && profile.serverName != profile.host) {
            params["sni"] = profile.serverName
        }
        if (profile.fingerprint.isNotBlank() && profile.fingerprint != "chrome") {
            params["fp"] = profile.fingerprint
        }
        if (!profile.publicKey.isNullOrBlank()) params["pbk"] = profile.publicKey
        if (!profile.shortId.isNullOrBlank()) params["sid"] = profile.shortId
        if (profile.alpn.isNotBlank()) params["alpn"] = profile.alpn
        if (!profile.spiderX.isNullOrBlank()) params["spx"] = profile.spiderX
        if (profile.allowInsecure) params["allowInsecure"] = "1"
        if (!profile.transportPath.isNullOrBlank()) params["path"] = profile.transportPath
        if (!profile.transportHost.isNullOrBlank()) params["host"] = profile.transportHost
        if (!profile.grpcServiceName.isNullOrBlank()) params["serviceName"] = profile.grpcServiceName
        if (!profile.kcpSeed.isNullOrBlank()) params["seed"] = profile.kcpSeed
        if (!profile.quicKey.isNullOrBlank()) params["key"] = profile.quicKey

        val query = params.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }

        return buildString {
            append("vless://")
            append(profile.uuid)
            append("@")
            append(profile.host)
            append(":")
            append(profile.port)
            if (query.isNotEmpty()) {
                append("?")
                append(query)
            }
        }
    }

    fun parseVlessProfileFromJson(rawJson: String): VlessProfile {
        val root = JSONObject(rawJson.trim())
        val vlessOutbound = findFirstVlessOutbound(root)
            ?: throw IllegalArgumentException("JSON config does not contain a vless outbound")

        val vnext = vlessOutbound.optJSONObject("settings")
            ?.optJSONArray("vnext")
            ?.optJSONObject(0)
            ?: throw IllegalArgumentException("Missing settings.vnext[0] in vless outbound")

        val user = vnext.optJSONArray("users")?.optJSONObject(0)
            ?: throw IllegalArgumentException("Missing settings.vnext[0].users[0] in vless outbound")

        val host = vnext.optString("address").trim()
        require(host.isNotBlank()) { "Missing vless outbound address" }

        val port = vnext.optInt("port", -1)
        require(port in 1..65535) { "Missing or invalid vless outbound port" }

        val ss = vlessOutbound.optJSONObject("streamSettings") ?: JSONObject()
        val security = ss.optString("security").ifBlank { "none" }
        val network = ss.optString("network").ifBlank { "tcp" }

        val realitySettings = ss.optJSONObject("realitySettings")
        val tlsSettings = ss.optJSONObject("tlsSettings")

        val secObj = when (security.lowercase()) {
            "reality" -> realitySettings
            "tls" -> tlsSettings
            else -> null
        }

        val alpnArray = secObj?.optJSONArray("alpn")
        val alpn = if (alpnArray != null) {
            (0 until alpnArray.length()).joinToString(",") { alpnArray.optString(it) }
        } else ""

        val transport = readTransportFields(ss, network)
        val xhttpExtraJson = ss.optJSONObject("xhttpSettings")
            ?.optJSONObject("extra")
            ?.toString(2)
        val finalmaskJson = ss.optJSONObject("finalmask")?.toString(2)

        return VlessProfile(
            uuid = user.optString("id").trim().also {
                require(it.isNotBlank()) { "Missing vless user id" }
            },
            host = host,
            port = port,
            flow = user.optString("flow").orEmpty(),
            security = security,
            publicKey = realitySettings?.optString("publicKey")?.ifBlank { null }
                ?: realitySettings?.optString("password")?.ifBlank { null },
            shortId = realitySettings?.optString("shortId")?.ifBlank { null },
            fingerprint = secObj?.optString("fingerprint")?.ifBlank { null } ?: "chrome",
            serverName = secObj?.optString("serverName")?.ifBlank { null } ?: host,
            network = network,
            alpn = alpn,
            spiderX = realitySettings?.optString("spiderX")?.ifBlank { null },
            allowInsecure = tlsSettings?.optBoolean("allowInsecure", false) == true,
            transportPath = transport.first,
            transportHost = transport.second,
            grpcServiceName = transport.third,
            grpcAuthority = transport.fourth,
            kcpSeed = transport.fifth,
            quicKey = transport.sixth,
            xhttpExtraJson = xhttpExtraJson,
            finalmaskJson = finalmaskJson,
            encryption = user.optString("encryption").ifBlank { "none" }
        )
    }

    private data class TransportFields(
        val first: String? = null,
        val second: String? = null,
        val third: String? = null,
        val fourth: String? = null,
        val fifth: String? = null,
        val sixth: String? = null
    )

    private fun readTransportFields(ss: JSONObject, network: String): TransportFields {
        return when (network.lowercase()) {
            "ws" -> {
                val w = ss.optJSONObject("wsSettings")
                TransportFields(
                    first = w?.optString("path")?.ifBlank { null },
                    second = w?.optJSONObject("headers")?.optString("Host")?.ifBlank { null }
                )
            }
            "httpupgrade" -> {
                val h = ss.optJSONObject("httpupgradeSettings")
                TransportFields(
                    first = h?.optString("path")?.ifBlank { null },
                    second = h?.optString("host")?.ifBlank { null }
                )
            }
            "h2" -> {
                val h = ss.optJSONObject("httpSettings")
                TransportFields(
                    first = h?.optString("path")?.ifBlank { null },
                    second = h?.optJSONArray("host")?.optString(0)?.ifBlank { null }
                )
            }
            "xhttp" -> {
                val x = ss.optJSONObject("xhttpSettings")
                TransportFields(
                    first = x?.optString("path")?.ifBlank { null },
                    second = x?.optString("host")?.ifBlank { null }
                )
            }
            "grpc" -> {
                val g = ss.optJSONObject("grpcSettings")
                TransportFields(
                    third = g?.optString("serviceName")?.ifBlank { null },
                    fourth = g?.optString("authority")?.ifBlank { null }
                )
            }
            "kcp" -> {
                val k = ss.optJSONObject("kcpSettings")
                TransportFields(fifth = k?.optString("seed")?.ifBlank { null })
            }
            "quic" -> {
                val q = ss.optJSONObject("quicSettings")
                TransportFields(sixth = q?.optString("key")?.ifBlank { null })
            }
            else -> TransportFields()
        }
    }

    fun mergeVlessProfileIntoJson(rawJson: String, updatedProfile: VlessProfile): String {
        val root = JSONObject(rawJson.trim())
        val outbounds = root.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("JSON config must include outbounds")

        val index = findFirstVlessOutboundIndex(outbounds)
        if (index == -1) {
            throw IllegalArgumentException("JSON config does not contain a vless outbound")
        }

        val outbound = outbounds.optJSONObject(index)
            ?: throw IllegalArgumentException("Invalid vless outbound")

        val vnextNode = JSONObject().apply {
            put("address", updatedProfile.host)
            put("port", updatedProfile.port)
            put("users", JSONArray().put(JSONObject().apply {
                put("id", updatedProfile.uuid)
                put("encryption", updatedProfile.encryption)
                if (updatedProfile.flow.isNotBlank()) {
                    put("flow", updatedProfile.flow)
                }
            }))
        }

        outbound.put("settings", JSONObject().apply {
            put("vnext", JSONArray().put(vnextNode))
        })

        val ss = outbound.optJSONObject("streamSettings") ?: JSONObject()
        ss.put("network", updatedProfile.network)
        ss.put("security", updatedProfile.security)

        removeStaleTransportKeys(ss, updatedProfile.network)

        ss.remove("realitySettings")
        ss.remove("tlsSettings")

        when (updatedProfile.security.lowercase()) {
            "reality" -> {
                require(!updatedProfile.publicKey.isNullOrBlank()) { "Missing pbk for REALITY config" }
                ss.put("realitySettings", JSONObject().apply {
                    put("serverName", updatedProfile.serverName)
                    put("fingerprint", updatedProfile.fingerprint)
                    put("publicKey", updatedProfile.publicKey)
                    put("shortId", updatedProfile.shortId ?: "")
                    if (updatedProfile.alpn.isNotBlank()) {
                        put("alpn", alpnToJsonArray(updatedProfile.alpn))
                    }
                    if (!updatedProfile.spiderX.isNullOrBlank()) {
                        put("spiderX", updatedProfile.spiderX)
                    }
                })
            }
            "tls" -> {
                ss.put("tlsSettings", JSONObject().apply {
                    put("serverName", updatedProfile.serverName)
                    if (updatedProfile.fingerprint.isNotBlank()) {
                        put("fingerprint", updatedProfile.fingerprint)
                    }
                    if (updatedProfile.allowInsecure) {
                        put("allowInsecure", true)
                    }
                    if (updatedProfile.alpn.isNotBlank()) {
                        put("alpn", alpnToJsonArray(updatedProfile.alpn))
                    }
                })
            }
        }

        writeTransportSettings(ss, updatedProfile)
        applyFinalmaskSettings(ss, updatedProfile)

        outbound.put("streamSettings", ss)
        return root.toString()
    }

    private val TRANSPORT_SETTINGS_KEYS = listOf(
        "wsSettings", "httpupgradeSettings", "httpSettings",
        "xhttpSettings", "grpcSettings", "kcpSettings", "quicSettings"
    )

    private fun alpnToJsonArray(alpn: String): JSONArray {
        return JSONArray().apply {
            alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { put(it) }
        }
    }

    private fun writeTransportSettings(ss: JSONObject, profile: VlessProfile) {
        when (profile.network.lowercase()) {
            "ws" -> if (!profile.transportPath.isNullOrBlank() || !profile.transportHost.isNullOrBlank()) {
                ss.put("wsSettings", JSONObject().apply {
                    if (!profile.transportPath.isNullOrBlank()) put("path", profile.transportPath)
                    if (!profile.transportHost.isNullOrBlank()) {
                        put("headers", JSONObject().put("Host", profile.transportHost))
                    }
                })
            }
            "httpupgrade" -> if (!profile.transportPath.isNullOrBlank() || !profile.transportHost.isNullOrBlank()) {
                ss.put("httpupgradeSettings", JSONObject().apply {
                    if (!profile.transportPath.isNullOrBlank()) put("path", profile.transportPath)
                    if (!profile.transportHost.isNullOrBlank()) put("host", profile.transportHost)
                })
            }
            "h2" -> if (!profile.transportPath.isNullOrBlank() || !profile.transportHost.isNullOrBlank()) {
                ss.put("httpSettings", JSONObject().apply {
                    if (!profile.transportPath.isNullOrBlank()) put("path", profile.transportPath)
                    if (!profile.transportHost.isNullOrBlank()) {
                        put("host", JSONArray().put(profile.transportHost))
                    }
                })
            }
            "xhttp" -> {
                val merged = mergeXhttpSettings(ss.optJSONObject("xhttpSettings"), profile)
                if (merged.length() == 0) {
                    ss.remove("xhttpSettings")
                } else {
                    ss.put("xhttpSettings", merged)
                }
            }
            "grpc" -> if (!profile.grpcServiceName.isNullOrBlank() || !profile.grpcAuthority.isNullOrBlank()) {
                ss.put("grpcSettings", JSONObject().apply {
                    if (!profile.grpcServiceName.isNullOrBlank()) put("serviceName", profile.grpcServiceName)
                    if (!profile.grpcAuthority.isNullOrBlank()) put("authority", profile.grpcAuthority)
                })
            }
            "kcp" -> if (!profile.kcpSeed.isNullOrBlank()) {
                ss.put("kcpSettings", JSONObject().apply {
                    put("seed", profile.kcpSeed)
                })
            }
            "quic" -> if (!profile.quicKey.isNullOrBlank()) {
                ss.put("quicSettings", JSONObject().apply {
                    put("key", profile.quicKey)
                })
            }
        }
    }

    private fun mergeXhttpSettings(existingSettings: JSONObject?, profile: VlessProfile): JSONObject {
        val merged = JSONObject()
        if (existingSettings != null) {
            val keys = existingSettings.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                merged.put(key, existingSettings.opt(key))
            }
        }

        if (!profile.transportPath.isNullOrBlank()) {
            merged.put("path", profile.transportPath)
        } else {
            merged.remove("path")
        }
        if (!profile.transportHost.isNullOrBlank()) {
            merged.put("host", profile.transportHost)
        } else {
            merged.remove("host")
        }
        if (!profile.xhttpExtraJson.isNullOrBlank()) {
            merged.put("extra", JSONObject(profile.xhttpExtraJson))
        } else {
            merged.remove("extra")
        }
        return merged
    }

    private fun applyFinalmaskSettings(ss: JSONObject, profile: VlessProfile) {
        if (profile.finalmaskJson.isNullOrBlank()) {
            ss.remove("finalmask")
            return
        }
        ss.put("finalmask", JSONObject(profile.finalmaskJson))
    }

    private fun removeStaleTransportKeys(ss: JSONObject, currentNetwork: String) {
        val keepKey = when (currentNetwork.lowercase()) {
            "ws" -> "wsSettings"
            "httpupgrade" -> "httpupgradeSettings"
            "h2" -> "httpSettings"
            "xhttp" -> "xhttpSettings"
            "grpc" -> "grpcSettings"
            "kcp" -> "kcpSettings"
            "quic" -> "quicSettings"
            else -> null
        }
        TRANSPORT_SETTINGS_KEYS.filter { it != keepKey }.forEach { ss.remove(it) }
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

    private fun findFirstVlessOutbound(root: JSONObject): JSONObject? {
        val outbounds = root.optJSONArray("outbounds") ?: return null
        val index = findFirstVlessOutboundIndex(outbounds)
        return if (index == -1) null else outbounds.optJSONObject(index)
    }

    private fun findFirstVlessOutboundIndex(outbounds: JSONArray): Int {
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            if (outbound.optString("protocol").equals("vless", ignoreCase = true)) {
                return index
            }
        }
        return -1
    }

    private fun decode(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }
}
