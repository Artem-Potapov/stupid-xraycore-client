package com.justme.xtls_core_proxy.subs

import com.justme.xtls_core_proxy.config.ConfigBuilder
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

data class ParsedConfig(val displayName: String, val config: String)

data class ParseOutcome(val parsed: List<ParsedConfig>, val parseErrorCount: Int)

object SubscriptionBodyParser {

    private val BASE64_BODY_PATTERN = Regex("^[A-Za-z0-9+/=_\\-\\s]+$")
    private val BASE64_LINE_PATTERN = Regex("^[A-Za-z0-9+/=_\\-]+$")

    fun parseBody(body: String): ParseOutcome {
        val effective = decodeOuterBase64IfApplicable(body.trim())
        val lines = effective.split("\r\n", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .filter { it.contains("://") || it.contains("{") || looksLikeBase64Line(it) }

        val parsed = mutableListOf<ParsedConfig>()
        var errors = 0
        val nameCounts = mutableMapOf<String, Int>()

        for ((index, raw) in lines.withIndex()) {
            val candidate = unwrapPerLineBase64(raw) ?: raw
            val accepted = runCatching { ConfigBuilder.buildRuntimeConfig(candidate) }.isSuccess
            if (!accepted) {
                errors++
                continue
            }
            val baseName = deriveDisplayName(candidate, index)
            val unique = uniquify(baseName, nameCounts)
            val storedConfig = ConfigBuilder.toProfileStorageConfig(candidate)
            parsed += ParsedConfig(displayName = unique, config = storedConfig)
        }
        return ParseOutcome(parsed = parsed, parseErrorCount = errors)
    }

    private fun decodeOuterBase64IfApplicable(trimmed: String): String {
        if (trimmed.isEmpty()) return trimmed
        if (!BASE64_BODY_PATTERN.matches(trimmed)) return trimmed
        val decoded = decodeBase64Permissive(trimmed) ?: return trimmed
        return if (decoded.contains("vless://", ignoreCase = true) || decoded.contains("{")) {
            decoded
        } else {
            trimmed
        }
    }

    private fun unwrapPerLineBase64(line: String): String? {
        if (line.length < 8) return null
        if (!BASE64_LINE_PATTERN.matches(line)) return null
        val decoded = decodeBase64Permissive(line) ?: return null
        if (!decoded.contains("vless://", ignoreCase = true) && !decoded.contains("{")) return null
        return decoded.trim()
    }

    private fun looksLikeBase64Line(line: String): Boolean {
        if (line.length < 8) return false
        return BASE64_LINE_PATTERN.matches(line)
    }

    private fun decodeBase64Permissive(value: String): String? {
        val cleaned = value.replace("\\s".toRegex(), "")
        val padded = padBase64(cleaned)
        val candidates = listOf(
            { Base64.getDecoder().decode(padded) },
            { Base64.getUrlDecoder().decode(padded) },
            { Base64.getMimeDecoder().decode(padded) }
        )
        for (decoder in candidates) {
            val bytes = runCatching { decoder() }.getOrNull() ?: continue
            val text = runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull() ?: continue
            if (text.isNotBlank()) return text
        }
        return null
    }

    private fun padBase64(value: String): String {
        val remainder = value.length % 4
        if (remainder == 0) return value
        return value + "=".repeat(4 - remainder)
    }

    private fun deriveDisplayName(candidate: String, index: Int): String {
        val trimmed = candidate.trim()
        return if (trimmed.startsWith("vless://", ignoreCase = true)) {
            deriveVlessDisplayName(trimmed, index)
        } else {
            deriveJsonDisplayName(trimmed, index)
        }
    }

    internal fun deriveVlessDisplayName(uri: String, index: Int): String {
        val fragmentRaw = uri.substringAfter('#', "").substringBefore('?').takeIf { it.isNotBlank() }
        val fragment = fragmentRaw?.let {
            runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrNull()
        }?.takeIf { it.isNotBlank() }
        if (fragment != null) return fragment

        val parsed = runCatching { URI(uri.substringBefore('#')) }.getOrNull()
        val host = parsed?.host?.takeIf { it.isNotBlank() }
        val port = parsed?.port?.takeIf { it > 0 }
        return when {
            host != null && port != null -> "$host:$port"
            host != null -> host
            else -> "Config ${index + 1}"
        }
    }

    internal fun deriveJsonDisplayName(rawJson: String, index: Int): String {
        return runCatching {
            val root = JSONObject(rawJson)
            val outbounds = root.optJSONArray("outbounds")
            val first = outbounds?.optJSONObject(0)
            first?.optString("tag")?.takeIf { it.isNotBlank() }?.let { return@runCatching it }

            val ss = first?.optJSONObject("streamSettings")
            ss?.optJSONObject("tlsSettings")?.optString("serverName")?.takeIf { it.isNotBlank() }
                ?.let { return@runCatching it }
            ss?.optJSONObject("realitySettings")?.optString("serverName")?.takeIf { it.isNotBlank() }
                ?.let { return@runCatching it }

            val vnext = first?.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0)
            val address = vnext?.optString("address")?.takeIf { it.isNotBlank() }
            val port = vnext?.optInt("port", -1)?.takeIf { it > 0 }
            when {
                address != null && port != null -> "$address:$port"
                address != null -> address
                else -> "Config ${index + 1}"
            }
        }.getOrElse { "Config ${index + 1}" }
    }

    private fun uniquify(base: String, counts: MutableMap<String, Int>): String {
        val key = base.lowercase()
        val seen = counts.getOrDefault(key, 0)
        counts[key] = seen + 1
        return if (seen == 0) base else "$base (${seen + 1})"
    }
}
