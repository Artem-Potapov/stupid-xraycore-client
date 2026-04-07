package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.ConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderTest {
    @Test
    fun fromVlessUri_buildsTunOnlyInboundAndVlessOutbound() {
        val uri = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?type=tcp&security=reality&pbk=abc123&sid=1a2b3c&fp=chrome&sni=cdn.example.com"

        val config = JSONObject(ConfigBuilder.fromVlessUri(uri))
        val inbound = config.getJSONArray("inbounds").getJSONObject(0)
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)

        assertEquals("tun", inbound.getString("protocol"))
        assertEquals("vless", outbound.getString("protocol"))
        assertEquals("example.com", outbound.getJSONObject("settings")
            .getJSONArray("vnext")
            .getJSONObject(0)
            .getString("address"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun fromVlessUri_missingPort_throws() {
        ConfigBuilder.fromVlessUri("vless://11111111-1111-1111-1111-111111111111@example.com")
    }

    @Test
    fun fromJson_injectsTunInbound() {
        val input = """
            {
              "inbounds": [
                {"protocol": "dokodemo-door", "tag": "legacy"}
              ],
              "outbounds": [
                {"protocol": "freedom", "tag": "direct"}
              ]
            }
        """.trimIndent()

        val config = JSONObject(ConfigBuilder.fromJson(input))
        val inbound = config.getJSONArray("inbounds").getJSONObject(0)

        assertEquals(1, config.getJSONArray("inbounds").length())
        assertEquals("tun", inbound.getString("protocol"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun fromJson_rejectsSocksInbound() {
        val input = """
            {
              "inbounds": [
                {"protocol": "socks", "tag": "loopback"}
              ],
              "outbounds": [
                {"protocol": "freedom", "tag": "direct"}
              ]
            }
        """.trimIndent()

        ConfigBuilder.fromJson(input)
    }

    @Test
    fun buildRuntimeConfig_acceptsVlessAndJson() {
        val vless = ConfigBuilder.buildRuntimeConfig(
            "vless://11111111-1111-1111-1111-111111111111@demo.example:443?security=none"
        )
        val json = ConfigBuilder.buildRuntimeConfig(
            """{"outbounds":[{"protocol":"freedom","tag":"direct"}]}"""
        )
        assertTrue(vless.contains("\"protocol\":\"tun\""))
        assertTrue(json.contains("\"protocol\":\"tun\""))
    }
}
