package com.justme.xtls_core_proxy.add

import com.justme.xtls_core_proxy.config.ConfigBuilder
import java.net.URI

sealed class ClipboardKind {
    object Empty : ClipboardKind()
    data class Subscription(val url: String) : ClipboardKind()
    data class Vless(val uri: String) : ClipboardKind()
    data class UnsupportedScheme(val scheme: String) : ClipboardKind()
    data class Json(val text: String) : ClipboardKind()
    object Invalid : ClipboardKind()
}

object ClipboardAddRouter {
    fun classify(raw: String): ClipboardKind {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return ClipboardKind.Empty
        }

        if (
            startsWithIgnoreCase(trimmed, "http://") ||
            startsWithIgnoreCase(trimmed, "https://")
        ) {
            return classifyHttpUrl(trimmed)
        }

        if (startsWithIgnoreCase(trimmed, "vless://")) {
            return classifyVless(trimmed)
        }

        if (startsWithIgnoreCase(trimmed, "trojan://")) {
            return ClipboardKind.UnsupportedScheme("trojan")
        }

        if (startsWithIgnoreCase(trimmed, "ss://")) {
            return ClipboardKind.UnsupportedScheme("ss")
        }

        return classifyAsJson(trimmed)
    }

    private fun classifyHttpUrl(trimmed: String): ClipboardKind {
        val uri = parseUri(trimmed)
        return if (isValidHttpUri(uri)) {
            ClipboardKind.Subscription(trimmed)
        } else {
            ClipboardKind.Invalid
        }
    }

    private fun classifyVless(trimmed: String): ClipboardKind {
        val uri = parseUri(trimmed)
        return if (isValidVlessUri(uri)) {
            ClipboardKind.Vless(trimmed)
        } else {
            ClipboardKind.Invalid
        }
    }

    private fun classifyAsJson(trimmed: String): ClipboardKind {
        return runCatching {
            ConfigBuilder.buildRuntimeConfig(trimmed)
        }.fold(
            onSuccess = { ClipboardKind.Json(trimmed) },
            onFailure = { ClipboardKind.Invalid }
        )
    }

    private fun startsWithIgnoreCase(value: String, prefix: String): Boolean {
        if (value.length < prefix.length) {
            return false
        }
        return value.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)
    }

    private fun parseUri(value: String): URI? = runCatching { URI(value) }.getOrNull()

    private fun isValidHttpUri(uri: URI?): Boolean {
        val host = uri?.host?.trim()
        return !host.isNullOrEmpty()
    }

    private fun isValidVlessUri(uri: URI?): Boolean {
        val uuid = uri?.userInfo?.trim()
        val host = uri?.host?.trim()
        val port = uri?.port ?: -1
        return !uuid.isNullOrEmpty() && !host.isNullOrEmpty() && port in 1..65535
    }

}
