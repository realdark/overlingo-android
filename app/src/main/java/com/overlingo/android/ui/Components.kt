package com.overlingo.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.overlingo.android.R
import com.overlingo.android.data.Language
import com.overlingo.android.data.Languages

/** "→ Bulgarian" chip: shows the target language and opens the language picker on tap. */
@Composable
fun TargetLanguageChip(
    target: Language,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDark: Boolean = false,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val locale = uiLocale()
    AssistChip(
        onClick = { open = true },
        label = { Text(target.displayName(locale)) },
        leadingIcon = {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
        },
        colors = if (onDark) {
            AssistChipDefaults.assistChipColors(
                containerColor = Color(0x99000000),
                labelColor = Color.White,
                leadingIconContentColor = Color.White,
            )
        } else {
            AssistChipDefaults.assistChipColors()
        },
        border = null,
        modifier = modifier,
    )
    if (open) {
        LanguagePickerDialog(
            title = stringResource(R.string.target_language),
            selected = target.code,
            onSelect = { code ->
                open = false
                onSelected(code)
            },
            onDismiss = { open = false },
        )
    }
}

/** List of all languages with search by name. */
@Composable
fun LanguagePickerDialog(
    title: String,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = uiLocale()
    var query by remember { mutableStateOf("") }
    val languages = remember(locale) { Languages.sortedFor(locale) }
    val filtered = languages.filter {
        query.isBlank() || it.displayName(locale).contains(query.trim(), ignoreCase = true) ||
            it.code.equals(query.trim(), ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.search_language)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp).padding(top = 8.dp)) {
                    items(filtered, key = { it.code }) { language ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(language.code) }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                        ) {
                            Text(language.displayName(locale), modifier = Modifier.weight(1f))
                            if (language.code == selected) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** "Copy" and "Read aloud" buttons for a single text. */
@Composable
fun CopySpeakButtons(
    text: String,
    speakingText: String?,
    onSpeak: (String) -> Unit,
    onCopied: () -> Unit,
    speakable: Boolean = true,
) {
    val clipboard = LocalClipboardManager.current
    val enabled = text.isNotBlank()
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(
            enabled = enabled,
            onClick = {
                clipboard.setText(AnnotatedString(text))
                onCopied()
            },
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy))
        }
        if (speakable) {
            val speaking = speakingText == text && enabled
            IconButton(enabled = enabled, onClick = { onSpeak(text) }) {
                Icon(
                    if (speaking) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = stringResource(if (speaking) R.string.stop_reading else R.string.read_aloud),
                )
            }
        }
    }
}
