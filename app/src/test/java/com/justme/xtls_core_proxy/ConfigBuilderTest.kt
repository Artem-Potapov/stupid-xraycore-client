package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.ConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderTest {
    @Test
    fun toProfileStorageConfig_keepsJsonInput() {
        val input = """{"outbounds":[{"protocol":"freedom","tag":"direct"}]}"""
        assertEquals(input, ConfigBuilder.toProfileStorageConfig(input))
    }

    @Test
    fun toProfileStorageConfig_convertsVlessToJson() {
        val input = "vless://11111111-1111-1111-1111-111111111111@demo.example:443?security=none"
        val stored = ConfigBuilder.toProfileStorageConfig(input)
        val root = JSONObject(stored)

        assertTrue(stored.trimStart().startsWith("{"))
        assertEquals(
            "vless",
            root.getJSONArray("outbounds").getJSONObject(0).getString("protocol")
        )
    }

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

    @Test(expected = IllegalArgumentException::class)
    fun fromJson_rejectsHttpInbound() {
        val input = """
            {
              "inbounds": [
                {"protocol": "http", "tag": "loopback"}
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

    @Test
    fun fromVlessUri_tlsWithWsProducesCorrectStreamSettings() {
        val uri = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?type=ws&security=tls&sni=cdn.example.com&path=%2Fws&host=cdn.example.com&alpn=h2"

        val config = JSONObject(ConfigBuilder.fromVlessUri(uri))
        val ss = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")

        assertEquals("ws", ss.getString("network"))
        assertEquals("tls", ss.getString("security"))
        assertTrue(ss.has("tlsSettings"))
        assertEquals("cdn.example.com", ss.getJSONObject("tlsSettings").getString("serverName"))
        assertEquals("h2", ss.getJSONObject("tlsSettings").getJSONArray("alpn").getString(0))
        assertTrue(ss.has("wsSettings"))
        assertEquals("/ws", ss.getJSONObject("wsSettings").getString("path"))
    }

    @Test
    fun fromVlessUri_realityWithAlpnAndSpiderX() {
        val uri = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?type=tcp&security=reality&pbk=key123&sid=ab&sni=sni.com&fp=firefox&alpn=h2&spx=%2Findex"

        val config = JSONObject(ConfigBuilder.fromVlessUri(uri))
        val ss = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")

        val reality = ss.getJSONObject("realitySettings")
        assertEquals("h2", reality.getJSONArray("alpn").getString(0))
        assertEquals("/index", reality.getString("spiderX"))
    }

    @Test
    fun fromVlessUri_preservesEncryptionParam() {
        val uri = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?type=tcp&security=none&encryption=mlkem768x25519"

        val config = JSONObject(ConfigBuilder.fromVlessUri(uri))
        val user = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("settings")
            .getJSONArray("vnext")
            .getJSONObject(0)
            .getJSONArray("users")
            .getJSONObject(0)

        assertEquals("mlkem768x25519", user.getString("encryption"))
    }

    @Test
    fun fromVlessUri_defaultsEncryptionToNoneWhenAbsent() {
        val uri = "vless://11111111-1111-1111-1111-111111111111@example.com:443?type=tcp&security=none"

        val config = JSONObject(ConfigBuilder.fromVlessUri(uri))
        val user = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("settings")
            .getJSONArray("vnext")
            .getJSONObject(0)
            .getJSONArray("users")
            .getJSONObject(0)

        assertEquals("none", user.getString("encryption"))
    }

    @Test
    fun fromVlessUri_preservesXhttpExtraJson() {
        // %7B%22xPaddingBytes%22%3A%22100-1000%22%7D == {"xPaddingBytes":"100-1000"}
        val uri = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?type=xhttp&security=tls&sni=cdn.example.com&path=%2Fxh" +
            "&extra=%7B%22xPaddingBytes%22%3A%22100-1000%22%7D"

        val config = JSONObject(ConfigBuilder.fromVlessUri(uri))
        val xhttp = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")
            .getJSONObject("xhttpSettings")

        assertTrue("xhttpSettings must contain extra", xhttp.has("extra"))
        assertEquals(
            "100-1000",
            xhttp.getJSONObject("extra").getString("xPaddingBytes")
        )
    }

    @Test
    fun fromVlessUri_xhttpModeWritesIntoXhttpSettings() {
        val uri = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?type=xhttp&security=tls&sni=cdn.example.com&path=%2Fxh&mode=stream-up"

        val config = JSONObject(ConfigBuilder.fromVlessUri(uri))
        val xhttp = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")
            .getJSONObject("xhttpSettings")

        assertEquals("stream-up", xhttp.getString("mode"))
    }

    @Test
    fun fromVlessUri_grpcModeWritesIntoGrpcSettings() {
        val uri = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?type=grpc&security=tls&sni=cdn.example.com&serviceName=svc&mode=multi"

        val config = JSONObject(ConfigBuilder.fromVlessUri(uri))
        val grpc = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")
            .getJSONObject("grpcSettings")

        assertEquals("multi", grpc.getString("mode"))
    }

    @Test
    fun fromVlessUri_grpcAuthorityWritesIntoGrpcSettings() {
        val uri = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?type=grpc&security=tls&sni=cdn.example.com&serviceName=svc&authority=auth.example.com"

        val config = JSONObject(ConfigBuilder.fromVlessUri(uri))
        val grpc = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")
            .getJSONObject("grpcSettings")

        assertEquals("auth.example.com", grpc.getString("authority"))
    }
}
