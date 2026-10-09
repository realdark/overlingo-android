package com.overlingo.android.ui

import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.overlingo.android.BuildConfig
import com.overlingo.android.R
import com.overlingo.android.data.AppSettings
import com.overlingo.android.data.ServiceType
import com.overlingo.android.tts.VoiceEngine
import com.overlingo.android.tts.VoiceGender
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    offlineModels: Set<String>,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onRefreshOfflineModels: () -> Unit,
    onDeleteOfflineModel: (String) -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefreshOfflineModels() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            TranslationSection(settings, onUpdate)
            OfflineModels(offlineModels, onDeleteOfflineModel)
            SectionDivider()
            OverlaySection(settings, onUpdate)
            SectionDivider()
            SectionTitle(stringResource(R.string.section_ocr))
            Hint(stringResource(R.string.ocr_info))
            SectionDivider()
            VoiceSection(settings, onUpdate)
            SectionDivider()
            InterfaceSection()
            SectionDivider()
            Text(
                stringResource(R.string.about_text, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

// ----------------------------------------------------------------------
// Превод
// ----------------------------------------------------------------------

@Composable
private fun TranslationSection(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
    SectionTitle(stringResource(R.string.section_translation))

    var pickTarget by rememberSaveable { mutableStateOf(false) }
    val locale = uiLocale()
    SettingRow(
        title = stringResource(R.string.target_language),
        value = settings.target.displayName(locale),
        onClick = { pickTarget = true },
    )
    if (pickTarget) {
        LanguagePickerDialog(
            title = stringResource(R.string.target_language),
            selected = settings.target.code,
            onSelect = { code ->
                pickTarget = false
                onUpdate { it.copy(targetLang = code) }
            },
            onDismiss = { pickTarget = false },
        )
    }

    Text(
        stringResource(R.string.translation_service),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    val services = listOf(
        ServiceType.GOOGLE to R.string.service_google,
        ServiceType.DEEPL to R.string.service_deepl,
        ServiceType.MICROSOFT to R.string.service_microsoft,
        ServiceType.OFFLINE to R.string.service_offline,
    )
    services.forEach { (type, label) ->
        RadioRow(stringResource(label), settings.service == type) { onUpdate { it.copy(service = type) } }
    }

    when (settings.service) {
        ServiceType.GOOGLE -> Hint(stringResource(R.string.service_google_hint))
        ServiceType.DEEPL -> {
            SecretField(
                label = stringResource(R.string.api_key),
                initial = settings.deeplKey,
                onChange = { value -> onUpdate { it.copy(deeplKey = value) } },
            )
            Hint(stringResource(R.string.deepl_hint))
        }
        ServiceType.MICROSOFT -> {
            SecretField(
                label = stringResource(R.string.api_key),
                initial = settings.microsoftKey,
                onChange = { value -> onUpdate { it.copy(microsoftKey = value) } },
            )
            PlainField(
                label = stringResource(R.string.microsoft_region),
                initial = settings.microsoftRegion,
                placeholder = "westeurope",
                onChange = { value -> onUpdate { it.copy(microsoftRegion = value) } },
            )
            Hint(stringResource(R.string.microsoft_hint))
        }
        ServiceType.OFFLINE -> Hint(stringResource(R.string.service_offline_hint))
    }

    if (settings.service == ServiceType.DEEPL || settings.service == ServiceType.MICROSOFT) {
        SwitchRow(
            title = stringResource(R.string.fallback_title),
            subtitle = stringResource(R.string.fallback_subtitle),
            checked = settings.fallback,
            onChange = { value -> onUpdate { it.copy(fallback = value) } },
        )
    }
}

/** Свалените модели за офлайн превод - с възможност за изтриване (всеки е ~30 MB). */
@Composable
private fun OfflineModels(models: Set<String>, onDelete: (String) -> Unit) {
    if (models.isEmpty()) return
    val locale = uiLocale()
    Text(
        stringResource(R.string.offline_models_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    models.map { it to Locale.forLanguageTag(it).getDisplayName(locale).replaceFirstChar { c -> c.titlecase(locale) } }
        .sortedBy { it.second }
        .forEach { (code, name) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(name, modifier = Modifier.weight(1f))
                // Английският е вграден в ML Kit и не може да се изтрие.
                if (code != "en") {
                    IconButton(onClick = { onDelete(code) }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.offline_model_delete))
                    }
                }
            }
        }
}

// ----------------------------------------------------------------------
// Прозорци с превод
// ----------------------------------------------------------------------

@Composable
private fun OverlaySection(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
    SectionTitle(stringResource(R.string.section_overlay))

    var scale by remember(settings.overlayTextScale) { mutableFloatStateOf(settings.overlayTextScale) }
    Text(stringResource(R.string.overlay_text_size, "${(scale * 100).roundToInt()}%"))
    Slider(
        value = scale,
        onValueChange = { scale = it },
        onValueChangeFinished = { onUpdate { it.copy(overlayTextScale = scale) } },
        valueRange = 0.7f..1.5f,
        steps = 7,
    )

    var opacity by remember(settings.overlayOpacity) { mutableFloatStateOf(settings.overlayOpacity) }
    Text(stringResource(R.string.overlay_opacity, "${(opacity * 100).roundToInt()}%"))
    Slider(
        value = opacity,
        onValueChange = { opacity = it },
        onValueChangeFinished = { onUpdate { it.copy(overlayOpacity = opacity) } },
        valueRange = 0.5f..1f,
        steps = 9,
    )

    SwitchRow(
        title = stringResource(R.string.overlay_light_title),
        subtitle = stringResource(R.string.overlay_light_subtitle),
        checked = settings.overlayLight,
        onChange = { value -> onUpdate { it.copy(overlayLight = value) } },
    )
    Hint(stringResource(R.string.overlay_hint))
}

// ----------------------------------------------------------------------
// Глас
// ----------------------------------------------------------------------

@Composable
private fun VoiceSection(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
    val context = LocalContext.current
    SectionTitle(stringResource(R.string.section_voice))

    Text(
        stringResource(R.string.voice_engine_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
    RadioRow(stringResource(R.string.voice_engine_phone), settings.voiceEngine == VoiceEngine.PHONE) {
        onUpdate { it.copy(voiceEngine = VoiceEngine.PHONE) }
    }
    RadioRow(stringResource(R.string.voice_engine_microsoft), settings.voiceEngine == VoiceEngine.MICROSOFT) {
        onUpdate { it.copy(voiceEngine = VoiceEngine.MICROSOFT) }
    }
    if (settings.voiceEngine == VoiceEngine.MICROSOFT) {
        Row {
            listOf(VoiceGender.FEMALE to R.string.voice_gender_female, VoiceGender.MALE to R.string.voice_gender_male)
                .forEach { (gender, label) ->
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .selectable(
                                selected = settings.voiceGender == gender,
                                onClick = { onUpdate { it.copy(voiceGender = gender) } },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = settings.voiceGender == gender, onClick = null)
                        Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
                    }
                }
        }
        Hint(stringResource(R.string.voice_microsoft_hint))
    }

    var rate by remember(settings.speechRate) { mutableFloatStateOf(settings.speechRate) }
    Text(stringResource(R.string.speech_rate, String.format(Locale.US, "%.2f", rate).trimEnd('0').trimEnd('.')))
    Slider(
        value = rate,
        onValueChange = { rate = it },
        onValueChangeFinished = { onUpdate { it.copy(speechRate = rate) } },
        valueRange = 0.5f..2f,
        steps = 5,
    )
    if (settings.voiceEngine == VoiceEngine.PHONE) {
        Hint(stringResource(R.string.voice_hint))
    }
    // Гласът на телефона е и резервата за Microsoft (без интернет) - бутонът остава винаги.
    OutlinedButton(
        onClick = {
            openActivity(context, Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Text(stringResource(R.string.voice_settings_button))
    }
}

// ----------------------------------------------------------------------
// Интерфейс
// ----------------------------------------------------------------------

@Composable
private fun InterfaceSection() {
    SectionTitle(stringResource(R.string.section_interface))
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val options = listOf(
        "" to stringResource(R.string.language_system),
        "en" to "English",
        "bg" to "Български",
    )
    options.forEach { (tag, label) ->
        val selected = current.substringBefore(',').substringBefore('-') == tag
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = {
                        // Смяната на езика пресъздава екрана - състоянието се пази във ViewModel-а.
                        val locales = if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                        else LocaleListCompat.forLanguageTags(tag)
                        AppCompatDelegate.setApplicationLocales(locales)
                    },
                )
                .padding(vertical = 2.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Text(label, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

// ----------------------------------------------------------------------
// Общи елементи
// ----------------------------------------------------------------------

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 2.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SectionDivider() = HorizontalDivider(Modifier.padding(vertical = 12.dp))

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(value, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Текстово поле с локално състояние: пише се веднага, а записът в
 * настройките става на заден план (иначе курсорът "скача" при бързо писане).
 */
@Composable
private fun PlainField(
    label: String,
    initial: String,
    placeholder: String,
    onChange: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    OutlinedTextField(
        value = value,
        onValueChange = {
            value = it
            onChange(it.trim())
        },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun SecretField(label: String, initial: String, onChange: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = {
            value = it
            onChange(it.trim())
        },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(if (visible) R.string.hide_key else R.string.show_key),
                )
            }
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
