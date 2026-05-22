package com.justme.xtls_core_proxy.add

import java.net.URI

fun subscriptionNameFromUrl(url: String): String {
    val trimmed = url.trim()
    return runCatching { URI(trimmed) }
        .mapCatching {
            it.host?.trim()?.takeIf(String::isNotEmpty)?.lowercase() ?: error("no host")
        }
        .getOrElse { trimmed }
}
