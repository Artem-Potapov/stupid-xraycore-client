package com.justme.xtls_core_proxy.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.justme.xtls_core_proxy.config.ConfigBuilder
import com.justme.xtls_core_proxy.config.ConfigKind
import com.justme.xtls_core_proxy.config.JsonFormatter
import com.justme.xtls_core_proxy.config.ProfileConfigCodec
import com.justme.xtls_core_proxy.config.SimpleServerFields
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme

class ServerSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val profileId = intent.getLongExtra(EXTRA_PROFILE_ID, -1L)
        val initialName = intent.getStringExtra(EXTRA_INITIAL_NAME).orEmpty()
        val initialConfig = intent.getStringExtra(EXTRA_INITIAL_CONFIG).orEmpty()

        setContent {
            XTLS_CORE_PROXYTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ServerSettingsScreen(
                        initialName = initialName,
                        initialConfig = initialConfig,
                        isEdit = profileId != -1L,
                        onBack = { finish() },
                        onSave = { savedName, savedConfig ->
                            val resultIntent = Intent().apply {
                                putExtra(EXTRA_PROFILE_ID, profileId)
                                putExtra(EXTRA_RESULT_NAME, savedName)
                                putExtra(EXTRA_RESULT_CONFIG, savedConfig)
                            }
                            setResult(Activity.RESULT_OK, resultIntent)
                            finish()
                        }
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        const val EXTRA_INITIAL_NAME = "extra_initial_name"
        const val EXTRA_INITIAL_CONFIG = "extra_initial_config"
        const val EXTRA_RESULT_NAME = "extra_result_name"
        const val EXTRA_RESULT_CONFIG = "extra_result_config"

        fun createIntent(
            context: Context,
            profileId: Long,
            initialName: String,
            initialConfig: String
        ): Intent {
            return Intent(context, ServerSettingsActivity::class.java).apply {
                putExtra(EXTRA_PROFILE_ID, profileId)
                putExtra(EXTRA_INITIAL_NAME, initialName)
                putExtra(EXTRA_INITIAL_CONFIG, initialConfig)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSettingsScreen(
    initialName: String,
    initialConfig: String,
    isEdit: Boolean,
    onBack: () -> Unit,
    onSave: (name: String, config: String) -> Unit
) {
    val configKind = remember(initialConfig) {
        if (initialConfig.isBlank()) ConfigKind.VLESS_URI else ProfileConfigCodec.detectKind(initialConfig)
    }

    val initialSimpleFields = remember(initialConfig) {
        if (initialConfig.isBlank()) {
            defaultSimpleServerFields()
        } else {
            runCatching {
                SimpleServerFields.fromVlessProfile(ProfileConfigCodec.extractVlessProfile(initialConfig))
            }.getOrElse { defaultSimpleServerFields() }
        }
    }

    var name by rememberSaveable { mutableStateOf(initialName) }
    var configText by rememberSaveable { mutableStateOf(initialConfig) }
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var simpleFields by remember { mutableStateOf(initialSimpleFields) }
    var parseMessage by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }

    fun buildConfigFromSimple(): Result<String> {
        return runCatching {
            val vlessProfile = simpleFields.toVlessProfile()
            if (configKind == ConfigKind.JSON && initialConfig.isNotBlank()) {
                ProfileConfigCodec.mergeVlessProfileIntoJson(initialConfig, vlessProfile)
            } else {
                ProfileConfigCodec.toVlessUri(vlessProfile)
            }
        }
    }

    fun syncBasicToAdvanced() {
        buildConfigFromSimple()
            .onSuccess {
                // Format JSON with 2-space indentation if it's valid JSON
                val formatted = JsonFormatter.formatJsonIfValid(it)
                configText = formatted
                parseMessage = null
            }
            .onFailure {
                parseMessage = it.message ?: "Unable to build config from Simple fields"
            }
    }

    fun syncAdvancedToBasic() {
        runCatching {
            SimpleServerFields.fromVlessProfile(ProfileConfigCodec.extractVlessProfile(configText))
        }.onSuccess {
            simpleFields = it
            parseMessage = null
        }.onFailure {
            parseMessage = it.message ?: "Unable to parse Advanced config into Simple fields"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "Edit server" else "Add server") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            saveError = null
                            val trimmedName = name.trim()
                            if (trimmedName.isBlank()) {
                                saveError = "Name is required"
                                return@TextButton
                            }
                            val candidateConfig = if (tabIndex == 0) {
                                buildConfigFromSimple().getOrElse { error ->
                                    saveError = error.message ?: "Simple fields are invalid"
                                    return@TextButton
                                }
                            } else {
                                configText.trim()
                            }
                            if (candidateConfig.isBlank()) {
                                saveError = "Config is required"
                                return@TextButton
                            }
                            runCatching { ConfigBuilder.buildRuntimeConfig(candidateConfig) }
                                .onFailure { error ->
                                    saveError = error.message ?: "Config validation failed"
                                }
                                .onSuccess {
                                    onSave(trimmedName, candidateConfig)
                                }
                        }
                    ) {
                        Text("Save")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Server Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))
            val tabTitles = listOf("Simple", "Advanced")
            TabRow(selectedTabIndex = tabIndex) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = tabIndex == index,
                        onClick = {
                            if (index == tabIndex) return@Tab
                            if (index == 1) {
                                syncBasicToAdvanced()
                            } else {
                                syncAdvancedToBasic()
                            }
                            tabIndex = index
                        },
                        text = { Text(title) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            if (tabIndex == 0) {
                SimpleEditor(
                    fields = simpleFields,
                    onFieldsChange = { simpleFields = it }
                )
            } else {
                AdvancedEditor(
                    configText = configText,
                    onConfigChange = { configText = it }
                )
            }

            if (!parseMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = parseMessage.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (!saveError.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = saveError.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

private val NETWORK_OPTIONS = listOf(
    "tcp" to "Raw (TCP)",
    "kcp" to "mKCP",
    "ws" to "WebSocket",
    "httpupgrade" to "HTTPUpgrade",
    "xhttp" to "XHTTP",
    "h2" to "HTTP/2",
    "quic" to "QUIC",
    "grpc" to "gRPC"
)

private val FLOW_OPTIONS = listOf(
    "" to "None",
    "xtls-rprx-vision" to "xtls-rprx-vision",
    "xtls-rprx-vision-udp443" to "xtls-rprx-vision-udp443"
)

private val SECURITY_OPTIONS = listOf(
    "none" to "None",
    "reality" to "REALITY",
    "tls" to "TLS"
)

private val FINGERPRINT_OPTIONS = listOf(
    "chrome", "firefox", "safari", "ios", "android",
    "edge", "360", "qq", "random", "randomized"
)

private val ALPN_OPTIONS = listOf(
    "" to "None",
    "h3" to "h3",
    "h2" to "h2",
    "http/1.1" to "http/1.1",
    "h3,h2,http/1.1" to "h3,h2,http/1.1",
    "h3,h2" to "h3,h2",
    "h2,http/1.1" to "h2,http/1.1"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    options: List<Pair<String, String>>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val displayText = options.firstOrNull { it.first == value }?.second ?: value

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, display) ->
                DropdownMenuItem(
                    text = { Text(display) },
                    onClick = {
                        onValueChange(key)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleEditor(
    fields: SimpleServerFields,
    onFieldsChange: (SimpleServerFields) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = fields.host,
            onValueChange = { onFieldsChange(fields.copy(host = it)) },
            label = { Text("Server IP / Host") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = fields.port,
                onValueChange = { onFieldsChange(fields.copy(port = it)) },
                label = { Text("Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            DropdownField(
                value = fields.network,
                onValueChange = { onFieldsChange(fields.copy(network = it)) },
                label = "Network",
                options = NETWORK_OPTIONS,
                modifier = Modifier.weight(1f)
            )
        }

        TransportFields(fields, onFieldsChange)

        OutlinedTextField(
            value = fields.uuid,
            onValueChange = { onFieldsChange(fields.copy(uuid = it)) },
            label = { Text("UUID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DropdownField(
                value = fields.security,
                onValueChange = { onFieldsChange(fields.copy(security = it)) },
                label = "Security",
                options = SECURITY_OPTIONS,
                modifier = Modifier.weight(1f)
            )
            DropdownField(
                value = fields.flow,
                onValueChange = { onFieldsChange(fields.copy(flow = it)) },
                label = "Flow",
                options = FLOW_OPTIONS,
                modifier = Modifier.weight(1f)
            )
        }

        val secLower = fields.security.lowercase()
        if (secLower == "reality") {
            RealityFields(fields, onFieldsChange)
        } else if (secLower == "tls") {
            TlsFields(fields, onFieldsChange)
        }
    }
}

@Composable
private fun TransportFields(
    fields: SimpleServerFields,
    onFieldsChange: (SimpleServerFields) -> Unit
) {
    when (fields.network.lowercase()) {
        "ws", "httpupgrade", "h2", "xhttp" -> {
            OutlinedTextField(
                value = fields.transportPath,
                onValueChange = { onFieldsChange(fields.copy(transportPath = it)) },
                label = { Text(fields.network + " Path") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = fields.transportHost,
                onValueChange = { onFieldsChange(fields.copy(transportHost = it)) },
                label = { Text(fields.network + " Host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        "grpc" -> {
            OutlinedTextField(
                value = fields.grpcServiceName,
                onValueChange = { onFieldsChange(fields.copy(grpcServiceName = it)) },
                label = { Text("gRPC Service Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = fields.grpcAuthority,
                onValueChange = { onFieldsChange(fields.copy(grpcAuthority = it)) },
                label = { Text("gRPC Authority") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        "kcp" -> {
            OutlinedTextField(
                value = fields.kcpSeed,
                onValueChange = { onFieldsChange(fields.copy(kcpSeed = it)) },
                label = { Text("mKCP Seed") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        "quic" -> {
            OutlinedTextField(
                value = fields.quicKey,
                onValueChange = { onFieldsChange(fields.copy(quicKey = it)) },
                label = { Text("QUIC Key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RealityFields(
    fields: SimpleServerFields,
    onFieldsChange: (SimpleServerFields) -> Unit
) {
    OutlinedTextField(
        value = fields.serverName,
        onValueChange = { onFieldsChange(fields.copy(serverName = it)) },
        label = { Text("SNI / Server Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DropdownField(
            value = fields.fingerprint,
            onValueChange = { onFieldsChange(fields.copy(fingerprint = it)) },
            label = "Fingerprint",
            options = FINGERPRINT_OPTIONS.map { it to it },
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = fields.shortId,
            onValueChange = { onFieldsChange(fields.copy(shortId = it)) },
            label = { Text("Short ID") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }
    OutlinedTextField(
        value = fields.publicKey,
        onValueChange = { onFieldsChange(fields.copy(publicKey = it)) },
        label = { Text("Public Key") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DropdownField(
            value = fields.alpn,
            onValueChange = { onFieldsChange(fields.copy(alpn = it)) },
            label = "ALPN",
            options = ALPN_OPTIONS,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = fields.spiderX,
            onValueChange = { onFieldsChange(fields.copy(spiderX = it)) },
            label = { Text("SpiderX") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TlsFields(
    fields: SimpleServerFields,
    onFieldsChange: (SimpleServerFields) -> Unit
) {
    OutlinedTextField(
        value = fields.serverName,
        onValueChange = { onFieldsChange(fields.copy(serverName = it)) },
        label = { Text("SNI / Server Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DropdownField(
            value = fields.fingerprint,
            onValueChange = { onFieldsChange(fields.copy(fingerprint = it)) },
            label = "Fingerprint",
            options = FINGERPRINT_OPTIONS.map { it to it },
            modifier = Modifier.weight(1f)
        )
        DropdownField(
            value = fields.alpn,
            onValueChange = { onFieldsChange(fields.copy(alpn = it)) },
            label = "ALPN",
            options = ALPN_OPTIONS,
            modifier = Modifier.weight(1f)
        )
    }
    DropdownField(
        value = fields.allowInsecure,
        onValueChange = { onFieldsChange(fields.copy(allowInsecure = it)) },
        label = "Allow Insecure",
        options = listOf("false" to "No", "true" to "Yes"),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun AdvancedEditor(
    configText: String,
    onConfigChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "vless:// URI or Xray JSON",
                style = MaterialTheme.typography.bodyMedium
            )
            IconButton(
                onClick = {
                    val formatted = JsonFormatter.formatJsonIfValid(configText)
                    if (formatted != configText) {
                        onConfigChange(formatted)
                    }
                },
                enabled = JsonFormatter.isValidJson(configText)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Format JSON with 2-space indentation"
                )
            }
        }
        OutlinedTextField(
            value = configText,
            onValueChange = onConfigChange,
            label = { Text("") },
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Advanced changes are saved exactly as typed after validation. Click the format button to beautify JSON with 2-space indentation.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun defaultSimpleServerFields(): SimpleServerFields {
    return SimpleServerFields(
        uuid = "",
        host = "",
        port = "",
        flow = "",
        security = "none",
        publicKey = "",
        shortId = "",
        fingerprint = "chrome",
        serverName = "",
        network = "tcp"
    )
}
