package com.justme.xtls_core_proxy

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.justme.xtls_core_proxy.i18n.LocalizedComponentActivity
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.add.AddFabMenu
import com.justme.xtls_core_proxy.add.ClipboardAddRouter
import com.justme.xtls_core_proxy.add.ClipboardKind
import com.justme.xtls_core_proxy.add.PasteAddDialog
import com.justme.xtls_core_proxy.add.PasteKind
import com.justme.xtls_core_proxy.add.readClipboardText
import com.justme.xtls_core_proxy.add.subscriptionNameFromUrl
import com.justme.xtls_core_proxy.db.Profile
import com.justme.xtls_core_proxy.db.Subscription
import com.justme.xtls_core_proxy.log.LogRepository
import com.justme.xtls_core_proxy.log.VpnConnectionState
import com.justme.xtls_core_proxy.settings.ServerSettingsActivity
import com.justme.xtls_core_proxy.settings.SettingsHubActivity
import com.justme.xtls_core_proxy.state.SubGroup
import com.justme.xtls_core_proxy.state.VpnViewModel
import com.justme.xtls_core_proxy.subs.SubscriptionBodyParser
import com.justme.xtls_core_proxy.subs.SubscriptionFormatting
import com.justme.xtls_core_proxy.subs.SubscriptionsActivity
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme

class MainActivity : LocalizedComponentActivity() {

    companion object {
        const val EXTRA_TILE_AUTOCONNECT = "extra_tile_autoconnect"
        const val EXTRA_TILE_PROFILE_ID = "extra_tile_profile_id"
    }

    private val viewModel: VpnViewModel by viewModels()
    private var pendingProfileId: Long = -1L

    private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var serverSettingsLauncher: ActivityResultLauncher<Intent>

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
        serverSettingsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val name = data.getStringExtra(ServerSettingsActivity.EXTRA_RESULT_NAME)?.trim().orEmpty()
            val config = data.getStringExtra(ServerSettingsActivity.EXTRA_RESULT_CONFIG)?.trim().orEmpty()
            if (name.isBlank() || config.isBlank()) return@registerForActivityResult

            val profileId = data.getLongExtra(ServerSettingsActivity.EXTRA_PROFILE_ID, -1L)
            if (profileId == -1L) {
                viewModel.addProfile(name, config)
            } else {
                val existing = viewModel.profiles.value.firstOrNull { it.id == profileId }
                if (existing != null) {
                    viewModel.updateProfile(existing.copy(name = name, config = config))
                }
            }
        }
        enableEdgeToEdge()
        setContent {
            XTLS_CORE_PROXYTheme {
                var pasteKind by rememberSaveable { mutableStateOf<PasteKind?>(null) }

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
                    onOpenSettings = {
                        startActivity(Intent(this, SettingsHubActivity::class.java))
                    },
                    onOpenSubscriptions = {
                        startActivity(Intent(this, SubscriptionsActivity::class.java))
                    },
                    onClipboardAdd = { handleClipboardAdd() },
                    onPickPasteSubscription = { pasteKind = PasteKind.SUBSCRIPTION_URL },
                    onPickPasteVless = { pasteKind = PasteKind.VLESS_LINK },
                    onPickPasteJson = { pasteKind = PasteKind.JSON_CONFIG },
                    onEditProfile = { profile ->
                        serverSettingsLauncher.launch(
                            ServerSettingsActivity.createIntent(
                                context = this,
                                profileId = profile.id,
                                initialName = profile.name,
                                initialConfig = profile.config
                            )
                        )
                    }
                )

                pasteKind?.let { kind ->
                    PasteAddDialog(
                        kind = kind,
                        onDismiss = { pasteKind = null },
                        onSubmit = { text ->
                            handlePasteSubmit(kind, text)
                            pasteKind = null
                        }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refreshAllStaleSubscriptions(this)
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

    private fun handleClipboardAdd() {
        val text = readClipboardText(this)
        routeAdd(ClipboardAddRouter.classify(text))
    }

    private fun handlePasteSubmit(kind: PasteKind, raw: String) {
        val classified = ClipboardAddRouter.classify(raw)
        val matchesKind = when (kind) {
            PasteKind.SUBSCRIPTION_URL -> classified is ClipboardKind.Subscription
            PasteKind.VLESS_LINK -> classified is ClipboardKind.Vless
            PasteKind.JSON_CONFIG -> classified is ClipboardKind.Json
        }
        if (matchesKind) {
            routeAdd(classified)
        } else if (classified is ClipboardKind.UnsupportedScheme) {
            routeAdd(classified)
        } else {
            toast(getString(R.string.add_toast_invalid_format))
        }
    }

    private fun routeAdd(kind: ClipboardKind) {
        when (kind) {
            ClipboardKind.Empty -> toast(getString(R.string.add_toast_clipboard_empty))
            is ClipboardKind.Subscription -> {
                val name = subscriptionNameFromUrl(kind.url)
                viewModel.addSubscription(
                    name = name,
                    url = kind.url,
                    refreshAfterInsert = true
                )
                toast(getString(R.string.add_toast_subscription_added))
            }
            is ClipboardKind.Vless -> {
                val name = SubscriptionBodyParser.deriveVlessDisplayName(kind.uri, 0)
                viewModel.addProfile(name, kind.uri)
                toast(getString(R.string.add_toast_server_added))
            }
            is ClipboardKind.UnsupportedScheme -> {
                toast(getString(R.string.add_toast_unsupported_scheme, kind.scheme))
            }
            is ClipboardKind.Json -> {
                val name = SubscriptionBodyParser.deriveJsonDisplayName(kind.text, 0)
                viewModel.addProfile(name, kind.text)
                toast(getString(R.string.add_toast_server_added))
            }
            ClipboardKind.Invalid -> toast(getString(R.string.add_toast_invalid_format))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MainScreen(
    viewModel: VpnViewModel,
    onConnect: (Long) -> Unit,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onClipboardAdd: () -> Unit,
    onPickPasteSubscription: () -> Unit,
    onPickPasteVless: () -> Unit,
    onPickPasteJson: () -> Unit,
    onEditProfile: (Profile) -> Unit
) {
    val mainContext = LocalContext.current
    val view by viewModel.groupedProfiles.collectAsState()
    val activeId by viewModel.activeProfileId.collectAsState()
    val state by viewModel.connectionState.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val error by viewModel.error.collectAsState()

    var bottomSheetProfile by remember { mutableStateOf<Profile?>(null) }
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.main_title)) },
                actions = {
                    IconButton(onClick = onOpenSubscriptions) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = stringResource(R.string.main_cd_subscriptions)
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.main_cd_settings)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            AddFabMenu(
                onPickClipboard = onClipboardAdd,
                onPickSubscription = onPickPasteSubscription,
                onPickVless = onPickPasteVless,
                onPickJson = onPickPasteJson
            )
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
                Text(
                    text = stringResource(
                        R.string.main_state_label,
                        vpnConnectionStateLabel(state)
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (state == VpnConnectionState.CONNECTED ||
                    state == VpnConnectionState.CONNECTING ||
                    state == VpnConnectionState.PAUSED
                ) {
                    OutlinedButton(onClick = onDisconnect) {
                        Text(stringResource(R.string.main_button_disconnect))
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

            val isEmpty = view.manual.isEmpty() && view.groups.all { it.profiles.isEmpty() }
            if (isEmpty) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.main_empty_profiles),
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
                    if (view.manual.isNotEmpty()) {
                        item(key = "h-manual") {
                            SectionHeader(stringResource(R.string.main_section_my_profiles))
                        }
                        items(view.manual, key = { "p-${it.id}" }) { profile ->
                            ProfileRow(
                                profile = profile,
                                isActive = isActive(profile, activeId, state),
                                isConnecting = isActive(profile, activeId, state) &&
                                    state == VpnConnectionState.CONNECTING,
                                canConnect = canConnect(state),
                                onConnect = { onConnect(profile.id) },
                                onLongPress = { bottomSheetProfile = profile }
                            )
                        }
                    }
                    view.groups.forEach { group ->
                        val isExpanded = expanded[group.subscription.id] ?: true
                        item(key = "h-${group.subscription.id}") {
                            SubscriptionGroupHeader(
                                group = group,
                                isExpanded = isExpanded,
                                onToggle = { expanded[group.subscription.id] = !isExpanded },
                                onRefresh = { viewModel.refreshSubscription(mainContext, group.subscription.id) }
                            )
                        }
                        if (isExpanded) {
                            items(group.profiles, key = { "p-${it.id}" }) { profile ->
                                ProfileRow(
                                    profile = profile,
                                    isActive = isActive(profile, activeId, state),
                                    isConnecting = isActive(profile, activeId, state) &&
                                        state == VpnConnectionState.CONNECTING,
                                    canConnect = canConnect(state),
                                    onConnect = { onConnect(profile.id) },
                                    onLongPress = { bottomSheetProfile = profile }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.main_logs_title),
                style = MaterialTheme.typography.titleMedium
            )
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
                        onEditProfile(profile)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.main_action_edit))
                }
                TextButton(
                    onClick = {
                        viewModel.deleteProfile(profile)
                        bottomSheetProfile = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.main_action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubscriptionGroupHeader(
    group: SubGroup,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onToggle, onLongClick = onToggle),
        tonalElevation = 0.dp,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown
                else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(
                    if (isExpanded) R.string.main_cd_collapse else R.string.main_cd_expand
                )
            )
            Spacer(modifier = Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = group.subscription.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.main_subscription_profile_count, group.profiles.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (group.subscription.lastError != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        ErrorPill(group.subscription.lastError)
                    }
                }
                Text(
                    text = SubscriptionFormatting.lastSeenSummary(context, group.subscription),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.main_cd_refresh_subscription)
                )
            }
        }
    }
}

@Composable
private fun ErrorPill(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
                Text(
                    stringResource(
                        if (isConnecting) R.string.main_button_connecting
                        else R.string.main_button_connect
                    )
                )
            }
        }
    }
}

@Composable
private fun vpnConnectionStateLabel(state: VpnConnectionState): String {
    return stringResource(
        when (state) {
            VpnConnectionState.DISCONNECTED -> R.string.main_state_disconnected
            VpnConnectionState.CONNECTING -> R.string.main_state_connecting
            VpnConnectionState.CONNECTED -> R.string.main_state_connected
            VpnConnectionState.PAUSED -> R.string.main_state_paused
            VpnConnectionState.ERROR -> R.string.main_state_error
        }
    )
}

private fun isActive(profile: Profile, activeId: Long?, state: VpnConnectionState): Boolean {
    return profile.id == activeId &&
        (state == VpnConnectionState.CONNECTED ||
            state == VpnConnectionState.CONNECTING ||
            state == VpnConnectionState.PAUSED)
}

private fun canConnect(state: VpnConnectionState): Boolean {
    return state != VpnConnectionState.CONNECTED &&
        state != VpnConnectionState.CONNECTING &&
        state != VpnConnectionState.PAUSED
}
