package com.justme.xtls_core_proxy.killswitch

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.apps.AppPickerActivity
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme

class KillSwitchSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XTLS_CORE_PROXYTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    KillSwitchSettingsScreen()
                }
            }
        }
    }
}

@Composable
private fun KillSwitchSettingsScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var prefs by remember { mutableStateOf(KillSwitchRepository.load(context)) }
    var permissionGranted by remember { mutableStateOf(hasUsageAccess(context)) }

    // Re-check permission on every ON_RESUME so returning from system Settings
    // immediately unlocks the toggle. (LaunchedEffect(Unit) only runs once.)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = hasUsageAccess(context)
                prefs = KillSwitchRepository.load(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val newSelection = result.data
                ?.getStringArrayExtra(AppPickerActivity.EXTRA_RESULT_SELECTION)
                ?.toSet() ?: emptySet()
            KillSwitchRepository.save(context, enabled = prefs.enabled, packages = newSelection)
            prefs = KillSwitchRepository.load(context)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.kill_switch_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(text = stringResource(R.string.kill_switch_description))

        if (!permissionGranted) {
            Text(
                text = stringResource(R.string.kill_switch_permission_required),
                color = MaterialTheme.colorScheme.error
            )
            Button(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) {
                Text(stringResource(R.string.kill_switch_open_settings))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.kill_switch_enabled),
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = prefs.enabled,
                onCheckedChange = { newValue ->
                    KillSwitchRepository.save(context, enabled = newValue, packages = prefs.packages)
                    prefs = KillSwitchRepository.load(context)
                },
                enabled = permissionGranted
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "Selected apps: ${prefs.packages.size}")
        Button(onClick = {
            val initial = prefs.packages.toTypedArray()
            val intent = Intent(context, AppPickerActivity::class.java)
                .putExtra(AppPickerActivity.EXTRA_TITLE, "Kill-on-foreground apps")
                .putExtra(AppPickerActivity.EXTRA_INITIAL_SELECTION, initial)
            pickerLauncher.launch(intent)
        }) {
            Text("Choose apps →")
        }
    }
}

private fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}
