package com.justme.xtls_core_proxy

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.justme.xtls_core_proxy.db.Profile
import com.justme.xtls_core_proxy.log.VpnConnectionState
import com.justme.xtls_core_proxy.log.LogRepository
import com.justme.xtls_core_proxy.split.SplitTunnelSettingsActivity
import com.justme.xtls_core_proxy.state.VpnViewModel
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme

class MainActivity : ComponentActivity() {
    private val viewModel: VpnViewModel by viewModels()
    private var pendingProfileId: Long = -1L

    private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vpnPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && pendingProfileId != -1L) {
                viewModel.connect(this, pendingProfileId)
                pendingProfileId = -1L
            } else {
                LogRepository.append("VPN permission request was denied")
                pendingProfileId = -1L
            }
        }
        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted && pendingProfileId != -1L) {
                requestVpnPermissionAndConnect()
            } else {
                pendingProfileId = -1L
            }
        }
        enableEdgeToEdge()
        setContent {
            XTLS_CORE_PROXYTheme {
                MainScreen(
                    viewModel = viewModel,
                    onConnect = { profileId ->
                        pendingProfileId = profileId
                        if (needsNotificationPermission()) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            requestVpnPermissionAndConnect()
                        }
                    },
                    onDisconnect = { viewModel.disconnect(this) },
                    onOpenSplitTunnelSettings = {
                        startActivity(Intent(this, SplitTunnelSettingsActivity::class.java))
                    }
                )
            }
        }
    }

    private fun requestVpnPermissionAndConnect() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            viewModel.connect(this, pendingProfileId)
            pendingProfileId = -1L
        }
    }

    private fun needsNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MainScreen(
    viewModel: VpnViewModel,
    onConnect: (Long) -> Unit,
    onDisconnect: () -> Unit,
    onOpenSplitTunnelSettings: () -> Unit
) {
    val profiles by viewModel.profiles.collectAsState()
    val activeId by viewModel.activeProfileId.collectAsState()
    val state by viewModel.connectionState.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val error by viewModel.error.collectAsState()

    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<Profile?>(null) }
    var bottomSheetProfile by remember { mutableStateOf<Profile?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Xray Tun") },
                actions = {
                    IconButton(onClick = onOpenSplitTunnelSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Split tunnel settings"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add profile")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "State: ${state.name}", style = MaterialTheme.typography.bodyMedium)
                if (state == VpnConnectionState.CONNECTED || state == VpnConnectionState.CONNECTING) {
                    OutlinedButton(onClick = onDisconnect) {
                        Text("Disconnect")
                    }
                }
            }

            if (error != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (profiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No profiles yet.\nTap + to add a server.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(profiles, key = { it.id }) { profile ->
                        val isActive = profile.id == activeId &&
                            (state == VpnConnectionState.CONNECTED || state == VpnConnectionState.CONNECTING)

                        ProfileRow(
                            profile = profile,
                            isActive = isActive,
                            isConnecting = isActive && state == VpnConnectionState.CONNECTING,
                            canConnect = state != VpnConnectionState.CONNECTED && state != VpnConnectionState.CONNECTING,
                            onConnect = { onConnect(profile.id) },
                            onLongPress = { bottomSheetProfile = profile }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Logs", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                tonalElevation = 1.dp
            ) {
                LazyColumn(modifier = Modifier.padding(8.dp)) {
                    items(logs) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        ProfileDialog(
            title = "Add Profile",
            onDismiss = { showAddDialog = false },
            onSave = { name, config ->
                viewModel.addProfile(name, config)
                showAddDialog = false
            }
        )
    }

    if (editingProfile != null) {
        val profile = editingProfile!!
        ProfileDialog(
            title = "Edit Profile",
            initialName = profile.name,
            initialConfig = profile.config,
            onDismiss = { editingProfile = null },
            onSave = { name, config ->
                viewModel.updateProfile(profile.copy(name = name, config = config))
                editingProfile = null
            }
        )
    }

    if (bottomSheetProfile != null) {
        val profile = bottomSheetProfile!!
        ModalBottomSheet(
            onDismissRequest = { bottomSheetProfile = null },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                TextButton(
                    onClick = {
                        bottomSheetProfile = null
                        editingProfile = profile
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Edit")
                }
                TextButton(
                    onClick = {
                        viewModel.deleteProfile(profile)
                        bottomSheetProfile = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileRow(
    profile: Profile,
    isActive: Boolean,
    isConnecting: Boolean,
    canConnect: Boolean,
    onConnect: () -> Unit,
    onLongPress: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress
            ),
        tonalElevation = if (isActive) 3.dp else 0.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isActive) {
                val dotColor = if (isConnecting)
                    MaterialTheme.colorScheme.tertiary
                else
                    MaterialTheme.colorScheme.primary
                Surface(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape),
                    color = dotColor
                ) {}
                Spacer(modifier = Modifier.width(8.dp))
            }

            Text(
                text = profile.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Button(
                onClick = onConnect,
                enabled = canConnect,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(if (isConnecting) "Connecting…" else "Connect")
            }
        }
    }
}

@Composable
private fun ProfileDialog(
    title: String,
    initialName: String = "",
    initialConfig: String = "",
    onDismiss: () -> Unit,
    onSave: (name: String, config: String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var config by rememberSaveable { mutableStateOf(initialConfig) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = config,
                    onValueChange = { config = it },
                    label = { Text("vless:// URI or Xray JSON") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    maxLines = 8
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name.trim(), config.trim()) },
                enabled = name.isNotBlank() && config.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
