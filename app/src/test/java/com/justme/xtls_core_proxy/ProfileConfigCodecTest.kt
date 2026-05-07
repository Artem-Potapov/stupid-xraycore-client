package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.ConfigBuilder
import com.justme.xtls_core_proxy.config.ProfileConfigCodec
import com.justme.xtls_core_proxy.config.VlessProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileConfigCodecTest {
    @Test
    fun vlessUri_roundTrip_preservesCoreFields() {
        val original = "vless://11111111-1111-1111-1111-111111111111@demo.example:443" +
            "?type=tcp&security=reality&pbk=pubkey123&sid=a1b2c3&fp=chrome&sni=cdn.example.com&flow=xtls-rprx-vision"

        val parsed = ProfileConfigCodec.parseVlessUri(original)
        val rebuilt = ProfileConfigCodec.toVlessUri(parsed)
        val reparsed = ProfileConfigCodec.parseVlessUri(rebuilt)

        assertEquals(parsed.uuid, reparsed.uuid)
        assertEquals(parsed.host, reparsed.host)
        assertEquals(parsed.port, reparsed.port)
        assertEquals(parsed.security, reparsed.security)
        assertEquals(parsed.publicKey, reparsed.publicKey)
        assertEquals(parsed.shortId, reparsed.shortId)
        assertEquals(parsed.serverName, reparsed.serverName)
        assertEquals(parsed.flow, reparsed.flow)
    }

    @Test
    fun vlessUri_roundTrip_preservesTransportAndAlpn() {
        val original = "vless://11111111-1111-1111-1111-111111111111@demo.example:443" +
            "?type=ws&security=tls&sni=cdn.example.com&path=%2Fws&host=cdn.example.com&alpn=h2%2Chttp%2F1.1&allowInsecure=1"

        val parsed = ProfileConfigCodec.parseVlessUri(original)
        assertEquals("ws", parsed.network)
        assertEquals("/ws", parsed.transportPath)
        assertEquals("cdn.example.com", parsed.transportHost)
        assertEquals("h2,http/1.1", parsed.alpn)
        assertTrue(parsed.allowInsecure)

        val rebuilt = ProfileConfigCodec.toVlessUri(parsed)
        val reparsed = ProfileConfigCodec.parseVlessUri(rebuilt)
        assertEquals(parsed.network, reparsed.network)
        assertEquals(parsed.transportPath, reparsed.transportPath)
        assertEquals(parsed.transportHost, reparsed.transportHost)
        assertEquals(parsed.alpn, reparsed.alpn)
        assertEquals(parsed.allowInsecure, reparsed.allowInsecure)
    }

    @Test
    fun vlessUri_roundTrip_preservesGrpcServiceName() {
        val original = "vless://11111111-1111-1111-1111-111111111111@demo.example:443" +
            "?type=grpc&security=tls&sni=cdn.example.com&serviceName=myService"

        val parsed = ProfileConfigCodec.parseVlessUri(original)
        assertEquals("grpc", parsed.network)
        assertEquals("myService", parsed.grpcServiceName)

        val rebuilt = ProfileConfigCodec.toVlessUri(parsed)
        assertTrue(rebuilt.contains("serviceName=myService"))
    }

    @Test
    fun mergeVlessProfileIntoJson_preservesUnrelatedSections() {
        val sourceJson = """
            {
              "log": { "loglevel": "warning" },
              "outbounds": [
                {
                  "tag": "proxy",
                  "protocol": "vless",
                  "settings": {
                    "vnext": [
                      {
                        "address": "old.example.com",
                        "port": 443,
                        "users": [{ "id": "11111111-1111-1111-1111-111111111111", "encryption": "none" }]
                      }
                    ]
                  },
                  "streamSettings": {
                    "network": "tcp",
                    "security": "reality",
                    "realitySettings": {
                      "serverName": "old.example.com",
                      "fingerprint": "chrome",
                      "publicKey": "oldKey",
                      "shortId": "oldSid"
                    }
                  }
                },
                { "tag": "direct", "protocol": "freedom" }
              ],
              "routing": {
                "rules": [
                  { "type": "field", "domain": ["geosite:category-ads-all"], "outboundTag": "block" }
                ]
              }
            }
        """.trimIndent()

        val updated = ProfileConfigCodec.parseVlessUri(
            "vless://22222222-2222-2222-2222-222222222222@new.example.com:8443" +
                "?type=tcp&security=reality&pbk=newPublicKey&sid=newSid&fp=firefox&sni=cdn.new.example"
        )

        val merged = ProfileConfigCodec.mergeVlessProfileIntoJson(sourceJson, updated)
        val mergedRoot = JSONObject(merged)
        val proxyOutbound = mergedRoot.getJSONArray("outbounds").getJSONObject(0)
        val vnext = proxyOutbound.getJSONObject("settings")
            .getJSONArray("vnext")
            .getJSONObject(0)

        assertEquals("new.example.com", vnext.getString("address"))
        assertEquals(8443, vnext.getInt("port"))
        assertEquals("22222222-2222-2222-2222-222222222222", vnext.getJSONArray("users").getJSONObject(0).getString("id"))

        val directOutbound = mergedRoot.getJSONArray("outbounds").getJSONObject(1)
        assertEquals("direct", directOutbound.getString("tag"))
        assertEquals("freedom", directOutbound.getString("protocol"))
        assertTrue(mergedRoot.has("routing"))
        assertTrue(mergedRoot.getJSONObject("routing").getJSONArray("rules").length() > 0)

        val runtime = ConfigBuilder.buildRuntimeConfig(merged)
        assertTrue(runtime.contains("\"protocol\":\"tun\""))
    }

    @Test
    fun mergeVlessProfileIntoJson_switchesNetworkAndClearsStaleTransportSettings() {
        val sourceJson = """
            {
              "outbounds": [{
                "tag": "proxy", "protocol": "vless",
                "settings": { "vnext": [{ "address": "a.com", "port": 443, "users": [{"id":"aaaa","encryption":"none"}] }] },
                "streamSettings": {
                  "network": "ws",
                  "security": "none",
                  "wsSettings": { "path": "/old", "headers": { "Host": "old.com" } }
                }
              }]
            }
        """.trimIndent()

        val updated = VlessProfile(
            uuid = "bbbb", host = "b.com", port = 443, flow = "",
            security = "none", publicKey = null, shortId = null,
            fingerprint = "chrome", serverName = "b.com", network = "grpc",
            grpcServiceName = "mygrpc"
        )

        val merged = ProfileConfigCodec.mergeVlessProfileIntoJson(sourceJson, updated)
        val ss = JSONObject(merged).getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")

        assertEquals("grpc", ss.getString("network"))
        assertFalse(ss.has("wsSettings"))
        assertTrue(ss.has("grpcSettings"))
        assertEquals("mygrpc", ss.getJSONObject("grpcSettings").getString("serviceName"))
    }

    @Test
    fun mergeVlessProfileIntoJson_realityWithAlpnAndSpiderX() {
        val sourceJson = """
            {
              "outbounds": [{
                "tag": "proxy", "protocol": "vless",
                "settings": { "vnext": [{ "address": "a.com", "port": 443, "users": [{"id":"aaaa","encryption":"none"}] }] },
                "streamSettings": { "network": "tcp", "security": "none" }
              }]
            }
        """.trimIndent()

        val updated = VlessProfile(
            uuid = "aaaa", host = "a.com", port = 443, flow = "",
            security = "reality", publicKey = "pk123", shortId = "ab",
            fingerprint = "chrome", serverName = "sni.com", network = "tcp",
            alpn = "h2,http/1.1", spiderX = "/index"
        )

        val merged = ProfileConfigCodec.mergeVlessProfileIntoJson(sourceJson, updated)
        val ss = JSONObject(merged).getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")
        val reality = ss.getJSONObject("realitySettings")

        assertEquals("pk123", reality.getString("publicKey"))
        val alpn = reality.getJSONArray("alpn")
        assertEquals(2, alpn.length())
        assertEquals("h2", alpn.getString(0))
        assertEquals("http/1.1", alpn.getString(1))
        assertEquals("/index", reality.getString("spiderX"))
        assertFalse(ss.has("tlsSettings"))
    }

    @Test
    fun mergeVlessProfileIntoJson_tlsProducesTlsSettings() {
        val sourceJson = """
            {
              "outbounds": [{
                "tag": "proxy", "protocol": "vless",
                "settings": { "vnext": [{ "address": "a.com", "port": 443, "users": [{"id":"aaaa","encryption":"none"}] }] },
                "streamSettings": { "network": "tcp", "security": "none" }
              }]
            }
        """.trimIndent()

        val updated = VlessProfile(
            uuid = "aaaa", host = "a.com", port = 443, flow = "xtls-rprx-vision",
            security = "tls", publicKey = null, shortId = null,
            fingerprint = "firefox", serverName = "tls.example.com", network = "tcp",
            alpn = "h2", allowInsecure = true
        )

        val merged = ProfileConfigCodec.mergeVlessProfileIntoJson(sourceJson, updated)
        val ss = JSONObject(merged).getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")

        assertEquals("tls", ss.getString("security"))
        assertFalse(ss.has("realitySettings"))
        val tls = ss.getJSONObject("tlsSettings")
        assertEquals("tls.example.com", tls.getString("serverName"))
        assertEquals("firefox", tls.getString("fingerprint"))
        assertTrue(tls.getBoolean("allowInsecure"))
        assertEquals("h2", tls.getJSONArray("alpn").getString(0))
    }

    @Test
    fun parseVlessProfileFromJson_readsTlsSettings() {
        val json = """
            {
              "outbounds": [{
                "protocol": "vless",
                "settings": { "vnext": [{ "address": "a.com", "port": 443, "users": [{"id":"aaaa","encryption":"none"}] }] },
                "streamSettings": {
                  "network": "ws",
                  "security": "tls",
                  "tlsSettings": {
                    "serverName": "tls.example.com",
                    "fingerprint": "safari",
                    "allowInsecure": true,
                    "alpn": ["h2","http/1.1"]
                  },
                  "wsSettings": { "path": "/ws", "headers": { "Host": "cdn.example.com" } }
                }
              }]
            }
        """.trimIndent()

        val profile = ProfileConfigCodec.parseVlessProfileFromJson(json)
        assertEquals("tls", profile.security)
        assertEquals("tls.example.com", profile.serverName)
        assertEquals("safari", profile.fingerprint)
        assertTrue(profile.allowInsecure)
        assertEquals("h2,http/1.1", profile.alpn)
        assertEquals("/ws", profile.transportPath)
        assertEquals("cdn.example.com", profile.transportHost)
    }

    @Test
    fun parseVlessProfileFromJson_readsGrpcSettings() {
        val json = """
            {
              "outbounds": [{
                "protocol": "vless",
                "settings": { "vnext": [{ "address": "a.com", "port": 443, "users": [{"id":"aaaa","encryption":"none"}] }] },
                "streamSettings": {
                  "network": "grpc",
                  "security": "none",
                  "grpcSettings": { "serviceName": "svc", "authority": "auth.com" }
                }
              }]
            }
        """.trimIndent()

        val profile = ProfileConfigCodec.parseVlessProfileFromJson(json)
        assertEquals("grpc", profile.network)
        assertEquals("svc", profile.grpcServiceName)
        assertEquals("auth.com", profile.grpcAuthority)
    }

    @Test
    fun parseVlessProfileFromJson_readsXhttpExtraAndFinalmask() {
        val json = """
            {
              "outbounds": [{
                "protocol": "vless",
                "settings": { "vnext": [{ "address": "a.com", "port": 443, "users": [{"id":"aaaa","encryption":"none"}] }] },
                "streamSettings": {
                  "network": "xhttp",
                  "security": "none",
                  "xhttpSettings": {
                    "path": "/x",
                    "host": "cdn.example.com",
                    "mode": "auto",
                    "extra": { "maxPaddingBytesObfs": "100-1000" }
                  },
                  "finalmask": {
                    "udp": [{ "type": "mkcp-original", "settings": {} }]
                  }
                }
              }]
            }
        """.trimIndent()

        val profile = ProfileConfigCodec.parseVlessProfileFromJson(json)
        assertEquals("xhttp", profile.network)
        assertEquals("/x", profile.transportPath)
        assertEquals("cdn.example.com", profile.transportHost)
        assertEquals(
            "100-1000",
            JSONObject(requireNotNull(profile.xhttpExtraJson)).getString("maxPaddingBytesObfs")
        )
        assertEquals(
            "mkcp-original",
            JSONObject(requireNotNull(profile.finalmaskJson))
                .getJSONArray("udp")
                .getJSONObject(0)
                .getString("type")
        )
    }

    @Test
    fun mergeVlessProfileIntoJson_xhttpPreservesUnknownKeysAndWritesExtra() {
        val sourceJson = """
            {
              "outbounds": [{
                "protocol": "vless",
                "settings": { "vnext": [{ "address": "a.com", "port": 443, "users": [{"id":"aaaa","encryption":"none"}] }] },
                "streamSettings": {
                  "network": "xhttp",
                  "security": "none",
                  "xhttpSettings": {
                    "mode": "auto",
                    "uploadSettings": { "maxStreams": 6 },
                    "extra": { "maxPaddingBytesObfs": "10-50" }
                  }
                }
              }]
            }
        """.trimIndent()

        val updated = VlessProfile(
            uuid = "bbbb", host = "b.com", port = 443, flow = "",
            security = "none", publicKey = null, shortId = null,
            fingerprint = "chrome", serverName = "b.com", network = "xhttp",
            transportPath = "/new",
            transportHost = "new.example.com",
            xhttpExtraJson = """{"maxPaddingBytesObfs":"100-1000"}"""
        )

        val merged = ProfileConfigCodec.mergeVlessProfileIntoJson(sourceJson, updated)
        val xhttp = JSONObject(merged).getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")
            .getJSONObject("xhttpSettings")

        assertEquals("/new", xhttp.getString("path"))
        assertEquals("new.example.com", xhttp.getString("host"))
        assertEquals("auto", xhttp.getString("mode"))
        assertEquals(6, xhttp.getJSONObject("uploadSettings").getInt("maxStreams"))
        assertEquals(
            "100-1000",
            xhttp.getJSONObject("extra").getString("maxPaddingBytesObfs")
        )
    }

    @Test
    fun mergeVlessProfileIntoJson_writesAndRemovesFinalmaskFromSimpleFields() {
        val sourceJson = """
            {
              "outbounds": [{
                "protocol": "vless",
                "settings": { "vnext": [{ "address": "a.com", "port": 443, "users": [{"id":"aaaa","encryption":"none"}] }] },
                "streamSettings": {
                  "network": "kcp",
                  "security": "none",
                  "kcpSettings": { "seed": "legacy" }
                }
              }]
            }
        """.trimIndent()

        val withFinalmask = ProfileConfigCodec.mergeVlessProfileIntoJson(
            sourceJson,
            VlessProfile(
                uuid = "bbbb", host = "b.com", port = 443, flow = "",
                security = "none", publicKey = null, shortId = null,
                fingerprint = "chrome", serverName = "b.com", network = "kcp",
                kcpSeed = "seed1",
                finalmaskJson = """{"udp":[{"type":"mkcp-original","settings":{}}]}"""
            )
        )

        val withFinalmaskSs = JSONObject(withFinalmask).getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")
        assertEquals(
            "mkcp-original",
            withFinalmaskSs.getJSONObject("finalmask")
                .getJSONArray("udp")
                .getJSONObject(0)
                .getString("type")
        )

        val withoutFinalmask = ProfileConfigCodec.mergeVlessProfileIntoJson(
            withFinalmask,
            VlessProfile(
                uuid = "bbbb", host = "b.com", port = 443, flow = "",
                security = "none", publicKey = null, shortId = null,
                fingerprint = "chrome", serverName = "b.com", network = "kcp",
                kcpSeed = "seed1",
                finalmaskJson = ""
            )
        )

        val withoutFinalmaskSs = JSONObject(withoutFinalmask).getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")
        assertFalse(withoutFinalmaskSs.has("finalmask"))
    }

    @Test
    fun parseVlessProfileFromJson_readsNonDefaultEncryption() {
        val json = """
            {
              "outbounds": [{
                "protocol": "vless",
                "settings": { "vnext": [{ "address": "a.com", "port": 443,
                  "users": [{"id":"aaaa","encryption":"mlkem768x25519"}] }] },
                "streamSettings": { "network": "tcp", "security": "none" }
              }]
            }
        """.trimIndent()
        val profile = ProfileConfigCodec.parseVlessProfileFromJson(json)
        assertEquals("mlkem768x25519", profile.encryption)
    }

    @Test(expected = IllegalArgumentException::class)
    fun realityConfigWithoutPublicKey_stillFailsValidation() {
        ConfigBuilder.buildRuntimeConfig(
            "vless://11111111-1111-1111-1111-111111111111@demo.example:443" +
                "?type=tcp&security=reality&sid=1a2b3c&sni=cdn.example.com&fp=chrome"
        )
    }

    @Test
    fun vlessUri_roundTrip_preservesEncryption() {
        val original = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?type=tcp&security=none&encryption=mlkem768x25519"

        val parsed = ProfileConfigCodec.parseVlessUri(original)
        assertEquals("mlkem768x25519", parsed.encryption)

        val rebuilt = ProfileConfigCodec.toVlessUri(parsed)
        assertTrue(
            "rebuilt URI should contain encryption=mlkem768x25519, was: $rebuilt",
            rebuilt.contains("encryption=mlkem768x25519")
        )

        val reparsed = ProfileConfigCodec.parseVlessUri(rebuilt)
        assertEquals("mlkem768x25519", reparsed.encryption)
    }

    @Test
    fun vlessUri_roundTrip_omitsEncryptionWhenDefault() {
        val original = "vless://11111111-1111-1111-1111-111111111111@example.com:443?type=tcp&security=none"

        val parsed = ProfileConfigCodec.parseVlessUri(original)
        assertEquals("none", parsed.encryption)

        val rebuilt = ProfileConfigCodec.toVlessUri(parsed)
        assertFalse(
            "rebuilt URI should not include encryption=none, was: $rebuilt",
            rebuilt.contains("encryption=")
        )
    }
}
