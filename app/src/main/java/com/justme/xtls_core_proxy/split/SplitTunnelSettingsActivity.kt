package com.justme.xtls_core_proxy.split

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.justme.xtls_core_proxy.i18n.LocalizedComponentActivity
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.apps.AppPickerActivity
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme

class SplitTunnelSettingsActivity : LocalizedComponentActivity() {
    private var mode by mutableStateOf<SplitTunnelMode>(SplitTunnelMode.BLOCK_ALL_EXCEPT_SELECTED)
    private var selectedPackageCount by mutableIntStateOf(0)

    private lateinit var appPickerLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val newSelection = result.data
                    ?.getStringArrayExtra(AppPickerActivity.EXTRA_RESULT_SELECTION)
                    ?.toSet() ?: emptySet()
                val currentMode = SplitTunnelRepository.load(this).mode
                SplitTunnelRepository.save(this, currentMode, newSelection)
            }
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
                            val initial = SplitTunnelRepository.load(this).packages.toTypedArray()
                            val intent = Intent(this, AppPickerActivity::class.java)
                                .putExtra(
                                    AppPickerActivity.EXTRA_TITLE,
                                    getString(R.string.split_picker_title)
                                )
                                .putExtra(AppPickerActivity.EXTRA_INITIAL_SELECTION, initial)
                            appPickerLauncher.launch(intent)
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
        SplitTunnelMode.ALLOW_ONLY to stringResource(R.string.split_mode_allow_only),
        SplitTunnelMode.BLOCK_ALL_EXCEPT_SELECTED to stringResource(R.string.split_mode_block_except)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.split_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.split_mode_label),
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
            text = stringResource(R.string.split_selected_count, selectedPackageCount),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onChooseApps) {
            Text(stringResource(R.string.split_choose_apps))
        }
    }
}
