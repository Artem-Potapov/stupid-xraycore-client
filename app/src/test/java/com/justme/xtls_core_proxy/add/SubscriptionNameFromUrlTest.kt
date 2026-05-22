package com.justme.xtls_core_proxy.add

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionNameFromUrlTest {

    @Test
    fun returnsHostForTypicalHttpsUrl() {
        assertEquals(
            "one.two.site.com",
            subscriptionNameFromUrl("https://one.two.site.com/sub/v1/hYFcA1")
        )
    }

    @Test
    fun stripsPortAndPath() {
        assertEquals("1.2.3.4", subscriptionNameFromUrl("http://1.2.3.4:8080/x"))
    }

    @Test
    fun stripsUserInfo() {
        assertEquals(
            "host.com",
            subscriptionNameFromUrl("https://user:pw@host.com/x")
        )
    }

    @Test
    fun preservesPunycodeHost() {
        assertEquals(
            "xn--brger-kva.example",
            subscriptionNameFromUrl("https://xn--brger-kva.example/x")
        )
    }

    @Test
    fun trimsLeadingAndTrailingWhitespace() {
        assertEquals(
            "host.com",
            subscriptionNameFromUrl("   https://host.com/x   ")
        )
    }

    @Test
    fun fallsBackToRawWhenUnparseable() {
        // No scheme + bare gibberish — URI parses to no host. Should fall back to raw input.
        val raw = "not a url at all"
        assertEquals(raw, subscriptionNameFromUrl(raw))
    }

    @Test
    fun fallsBackToRawWhenHostMissing() {
        val raw = "https://"
        assertEquals(raw, subscriptionNameFromUrl(raw))
    }

    @Test
    fun lowercasesHostForUppercaseUrl() {
        assertEquals(
            "example.com",
            subscriptionNameFromUrl("HTTPS://EXAMPLE.COM/SUB")
        )
    }
}
