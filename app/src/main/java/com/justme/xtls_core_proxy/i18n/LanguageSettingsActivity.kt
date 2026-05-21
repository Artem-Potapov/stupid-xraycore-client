package com.justme.xtls_core_proxy.i18n

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme

class LanguageSettingsActivity : LocalizedComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XTLS_CORE_PROXYTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LanguageSettingsScreen()
                }
            }
        }
    }
}

@Composable
private fun LanguageSettingsScreen() {
    var selected by remember { mutableStateOf(SupportedLanguage.current()) }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_language_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(16.dp))

        LanguageRow(
            label = stringResource(R.string.lang_auto),
            selected = selected == SupportedLanguage.AUTO,
            onClick = {
                selected = SupportedLanguage.AUTO
                SupportedLanguage.apply(SupportedLanguage.AUTO)
            },
        )
        HorizontalDivider()
        LanguageRow(
            label = stringResource(R.string.lang_english),
            selected = selected == SupportedLanguage.ENGLISH,
            onClick = {
                selected = SupportedLanguage.ENGLISH
                SupportedLanguage.apply(SupportedLanguage.ENGLISH)
            },
        )
        HorizontalDivider()
        LanguageRow(
            label = stringResource(R.string.lang_russian),
            selected = selected == SupportedLanguage.RUSSIAN,
            onClick = {
                selected = SupportedLanguage.RUSSIAN
                SupportedLanguage.apply(SupportedLanguage.RUSSIAN)
            },
        )
    }
}

@Composable
private fun LanguageRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
