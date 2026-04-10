package com.justme.xtls_core_proxy.split

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme

class SplitTunnelSettingsActivity : ComponentActivity() {
    private var mode by mutableStateOf<SplitTunnelMode>(SplitTunnelMode.BLOCK_ALL_EXCEPT_SELECTED)
    private var selectedPackageCount by mutableStateOf(0)

    private lateinit var appPickerLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshFromPreferences()
        }

        enableEdgeToEdge()
        refreshFromPreferences()

        setContent {
            XTLS_CORE_PROXYTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SplitTunnelSettingsScreen(
                        selectedMode = mode,
                        selectedPackageCount = selectedPackageCount,
                        onModeChange = { newMode ->
                            val currentPackages = SplitTunnelRepository.load(this).packages
                            SplitTunnelRepository.save(this, newMode, currentPackages)
                            mode = newMode
                        },
                        onChooseApps = {
                            appPickerLauncher.launch(Intent(this, SplitTunnelActivity::class.java))
                        }
                    )
                }
            }
        }
    }

    private fun refreshFromPreferences() {
        val preferences = SplitTunnelRepository.load(this)
        mode = preferences.mode
        selectedPackageCount = preferences.packages.size
    }
}

@Composable
private fun SplitTunnelSettingsScreen(
    selectedMode: SplitTunnelMode,
    selectedPackageCount: Int,
    onModeChange: (SplitTunnelMode) -> Unit,
    onChooseApps: () -> Unit
) {
    val modeValues: List<Pair<SplitTunnelMode, String>> = listOf(
        SplitTunnelMode.ALLOW_ONLY to "Proxy selected apps only",
        SplitTunnelMode.BLOCK_ALL_EXCEPT_SELECTED to "Proxy all except selected"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Split tunneling",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Mode",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))

        modeValues.forEach { (value, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selectedMode == value,
                        onClick = { onModeChange(value) }
                    )
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedMode == value,
                    onClick = { onModeChange(value) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = label)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Selected apps: $selectedPackageCount",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onChooseApps) {
            Text("Choose apps →")
        }
    }
}
