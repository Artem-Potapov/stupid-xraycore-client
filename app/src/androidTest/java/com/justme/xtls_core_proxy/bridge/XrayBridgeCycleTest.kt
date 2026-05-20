package com.justme.xtls_core_proxy.bridge

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.justme.xtls_core_proxy.geo.GeoAssetPreparer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class XrayBridgeCycleTest {

    private val minimalConfig = """
        {
          "log": { "loglevel": "warning" },
          "inbounds": [{
            "tag": "tun-in",
            "protocol": "tun",
            "settings": {
              "name": "xray_tun",
              "network": "tcp,udp",
              "MTU": 1500
            }
          }],
          "outbounds": [{ "protocol": "freedom" }]
        }
    """.trimIndent()

    @Test
    fun startStop_repeated_tenTimes_allSucceed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val geoDir = GeoAssetPreparer.prepare(context).getOrThrow()

        // Bridge rejects fd <= 0; /dev/null gives a valid fd without needing VpnService.
        val placeholder = ParcelFileDescriptor.open(
            File("/dev/null"),
            ParcelFileDescriptor.MODE_READ_ONLY
        )

        try {
            repeat(10) { iteration ->
                val startResult = XrayBridge.startXray(minimalConfig, placeholder.fd, geoDir.absolutePath)
                assertTrue("startXray failed on iteration $iteration: ${startResult.exceptionOrNull()?.message}", startResult.isSuccess)

                val stopResult = XrayBridge.stopXray()
                assertTrue("stopXray failed on iteration $iteration: ${stopResult.exceptionOrNull()?.message}", stopResult.isSuccess)
            }
        } finally {
            placeholder.close()
        }
    }
}
