package com.justme.xtls_core_proxy.add

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.justme.xtls_core_proxy.R

@Composable
internal fun AddFabMenu(
    onPickClipboard: () -> Unit,
    onPickSubscription: () -> Unit,
    onPickVless: () -> Unit,
    onPickJson: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var clipboardHintRes by remember { mutableIntStateOf(R.string.add_clipboard_hint_empty) }
    val context = LocalContext.current

    val clipboardLabel = if (expanded && clipboardHintRes != R.string.add_clipboard_hint_empty) {
        stringResource(R.string.add_menu_from_clipboard_with_hint, stringResource(clipboardHintRes))
    } else {
        stringResource(R.string.add_menu_from_clipboard)
    }

    Box {
        FloatingActionButton(
            onClick = {
                if (!expanded) {
                    clipboardHintRes = clipboardHintResFor(context)
                    expanded = true
                }
            }
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = stringResource(R.string.main_cd_add_profile)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(clipboardLabel) },
                onClick = {
                    expanded = false
                    onPickClipboard()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_menu_subscription)) },
                onClick = {
                    expanded = false
                    onPickSubscription()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_menu_vless)) },
                onClick = {
                    expanded = false
                    onPickVless()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_menu_json)) },
                onClick = {
                    expanded = false
                    onPickJson()
                }
            )
        }
    }
}

private fun clipboardHintResFor(context: Context): Int {
    val text = readClipboardText(context)
    return when (ClipboardAddRouter.classify(text)) {
        ClipboardKind.Empty -> R.string.add_clipboard_hint_empty
        is ClipboardKind.Subscription -> R.string.add_clipboard_hint_subscription
        is ClipboardKind.Vless -> R.string.add_clipboard_hint_vless
        is ClipboardKind.UnsupportedScheme -> R.string.add_clipboard_hint_unsupported
        is ClipboardKind.Json -> R.string.add_clipboard_hint_json
        ClipboardKind.Invalid -> R.string.add_clipboard_hint_invalid
    }
}

internal fun readClipboardText(context: Context): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return ""
    val clip = clipboard.primaryClip ?: return ""
    val desc = clip.description
    if (!desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
        !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
    ) {
        return ""
    }
    if (clip.itemCount == 0) return ""
    return clip.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
}
