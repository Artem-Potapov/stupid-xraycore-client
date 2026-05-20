package com.justme.xtls_core_proxy.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.justme.xtls_core_proxy.killswitch.KillSwitchSettingsActivity
import com.justme.xtls_core_proxy.split.SplitTunnelSettingsActivity
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme

/**
 * Top-level settings hub. Single entry point from MainActivity; lists each
 * sub-settings screen (Split tunneling, Kill on foreground, ServerSettings if
 * it shouldn't have its own entry — see Task 5).
 */
class SettingsHubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XTLS_CORE_PROXYTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsHubScreen()
                }
            }
        }
    }
}

@Composable
private fun SettingsHubScreen() {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        SettingsRow(
            title = "Split tunneling",
            subtitle = "Choose which apps go through the VPN",
            onClick = {
                context.startActivity(Intent(context, SplitTunnelSettingsActivity::class.java))
            }
        )
        HorizontalDivider()
        SettingsRow(
            title = "Kill on foreground",
            subtitle = "Fully disconnect the VPN when selected apps are open",
            onClick = {
                context.startActivity(Intent(context, KillSwitchSettingsActivity::class.java))
            }
        )
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
    }
}
