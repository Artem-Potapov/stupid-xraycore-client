package com.justme.xtls_core_proxy.subs

import android.app.Activity
import android.content.Intent
import android.os.Bundle
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.db.Subscription
import com.justme.xtls_core_proxy.state.VpnViewModel
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme

class SubscriptionsActivity : LocalizedComponentActivity() {

    private val viewModel: VpnViewModel by viewModels()
    private lateinit var editLauncher: ActivityResultLauncher<Intent>
    private var pendingEditId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            handleEditResult(data)
        }
        enableEdgeToEdge()
        setContent {
            XTLS_CORE_PROXYTheme {
                SubscriptionsScreen(
                    viewModel = viewModel,
                    onBack = { finish() },
                    onAdd = {
                        pendingEditId = -1L
                        editLauncher.launch(
                            SubscriptionEditActivity.createIntent(
                                context = this,
                                subscriptionId = -1L,
                                initialName = "",
                                initialUrl = "",
                                initialUserAgent = null,
                                initialAllowInsecure = false,
                                initialIntervalHours = null
                            )
                        )
                    },
                    onEdit = { sub ->
                        pendingEditId = sub.id
                        editLauncher.launch(
                            SubscriptionEditActivity.createIntent(
                                context = this,
                                subscriptionId = sub.id,
                                initialName = sub.name,
                                initialUrl = sub.url,
                                initialUserAgent = sub.userAgentOverride,
                                initialAllowInsecure = sub.allowInsecureTls,
                                initialIntervalHours = sub.userIntervalHours
                            )
                        )
                    },
                    onRefresh = { sub -> viewModel.refreshSubscription(this, sub.id) },
                    onDelete = { sub -> viewModel.deleteSubscription(this, sub) }
                )
            }
        }
    }

    private fun handleEditResult(data: Intent) {
        val subscriptionId = data.getLongExtra(SubscriptionEditActivity.EXTRA_SUBSCRIPTION_ID, -1L)
        val name = data.getStringExtra(SubscriptionEditActivity.EXTRA_RESULT_NAME).orEmpty()
        val url = data.getStringExtra(SubscriptionEditActivity.EXTRA_RESULT_URL).orEmpty()
        val userAgent = data.getStringExtra(SubscriptionEditActivity.EXTRA_RESULT_USER_AGENT)
            ?.takeIf { it.isNotBlank() }
        val allowInsecure = data.getBooleanExtra(SubscriptionEditActivity.EXTRA_RESULT_ALLOW_INSECURE, false)
        val intervalRaw = data.getIntExtra(SubscriptionEditActivity.EXTRA_RESULT_INTERVAL_HOURS, -1)
        val intervalHours = intervalRaw.takeIf { it > 0 }
        val refreshNow = data.getBooleanExtra(SubscriptionEditActivity.EXTRA_RESULT_REFRESH_NOW, false)

        if (name.isBlank() || url.isBlank()) return

        if (subscriptionId == -1L) {
            viewModel.addSubscription(
                name = name,
                url = url,
                userAgentOverride = userAgent,
                allowInsecureTls = allowInsecure,
                userIntervalHours = intervalHours,
                refreshAfterInsert = refreshNow
            )
        } else {
            val existing = viewModel.subscriptions.value.firstOrNull { it.id == subscriptionId }
            if (existing != null) {
                viewModel.updateSubscription(
                    sub = existing.copy(
                        name = name,
                        url = url,
                        userAgentOverride = userAgent,
                        allowInsecureTls = allowInsecure,
                        userIntervalHours = intervalHours
                    ),
                    refreshAfterUpdate = refreshNow
                )
            }
        }
        pendingEditId = -1L
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SubscriptionsScreen(
    viewModel: VpnViewModel,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Subscription) -> Unit,
    onRefresh: (Subscription) -> Unit,
    onDelete: (Subscription) -> Unit
) {
    val subscriptions by viewModel.subscriptions.collectAsState()
    var pendingDelete by remember { mutableStateOf<Subscription?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.subs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.subs_cd_back)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.subs_cd_add))
            }
        }
    ) { padding ->
        if (subscriptions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.subs_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(subscriptions, key = { it.id }) { sub ->
                    SubscriptionRow(
                        subscription = sub,
                        onClick = { onEdit(sub) },
                        onLongPress = { pendingDelete = sub },
                        onRefresh = { onRefresh(sub) }
                    )
                }
            }
        }
    }

    pendingDelete?.let { sub ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.subs_delete_title)) },
            text = {
                Text(stringResource(R.string.subs_delete_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(sub)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.subs_button_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.subs_button_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubscriptionRow(
    subscription: Subscription,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = subscription.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subscription.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = SubscriptionFormatting.lastSeenSummary(context, subscription),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (subscription.lastError != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
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
