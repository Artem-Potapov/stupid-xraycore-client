package com.justme.xtls_core_proxy.add

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.justme.xtls_core_proxy.R

@Composable
fun PasteAddDialog(
    kind: PasteKind,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    val titleRes = when (kind) {
        PasteKind.SUBSCRIPTION_URL -> R.string.add_dialog_subscription_title
        PasteKind.VLESS_LINK -> R.string.add_dialog_vless_title
        PasteKind.JSON_CONFIG -> R.string.add_dialog_json_title
    }
    val hintRes = when (kind) {
        PasteKind.SUBSCRIPTION_URL -> R.string.add_dialog_subscription_hint
        PasteKind.VLESS_LINK -> R.string.add_dialog_vless_hint
        PasteKind.JSON_CONFIG -> R.string.add_dialog_json_hint
    }

    var text by rememberSaveable(kind) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column {
                Text(
                    text = stringResource(hintRes),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    placeholder = { Text(stringResource(R.string.add_dialog_input_placeholder)) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(text) }) {
                Text(stringResource(R.string.add_dialog_button_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.add_dialog_button_cancel))
            }
        }
    )
}
