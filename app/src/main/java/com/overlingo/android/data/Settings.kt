package com.overlingo.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.overlingo.android.tts.VoiceEngine
import com.overlingo.android.tts.VoiceGender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class ServiceType(val key: String) {
    GOOGLE("google"), DEEPL("deepl"), MICROSOFT("microsoft"), OFFLINE("offline");

    companion object {
        fun fromKey(key: String?): ServiceType = entries.firstOrNull { it.key == key } ?: GOOGLE
    }
}

data class AppSettings(
    val targetLang: String = "",
    val service: ServiceType = ServiceType.GOOGLE,
    val deeplKey: String = "",
    val microsoftKey: String = "",
    val microsoftRegion: String = "",
    val fallback: Boolean = true,
    val speechRate: Float = 1.0f,
    /** Which voice reads aloud: the phone's (default) or Microsoft's. */
    val voiceEngine: VoiceEngine = VoiceEngine.PHONE,
    val voiceGender: VoiceGender = VoiceGender.FEMALE,
    /** The translation boxes over the photo. */
    val overlayTextScale: Float = 1.0f,
    val overlayOpacity: Float = 0.9f,
    val overlayLight: Boolean = false,
) {
    /** Empty = not chosen yet -> the phone's language (or English). */
    val target: Language get() = Languages.byCode(targetLang) ?: Languages.defaultTarget()
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Settings are stored in DataStore (a file in the app's private directory). */
class SettingsRepository(context: Context) {

    private val store = context.applicationContext.dataStore

    private object Keys {
        val TARGET = stringPreferencesKey("target_lang")
        val SERVICE = stringPreferencesKey("service")
        val DEEPL_KEY = stringPreferencesKey("deepl_key")
        val MS_KEY = stringPreferencesKey("microsoft_key")
        val MS_REGION = stringPreferencesKey("microsoft_region")
        val FALLBACK = booleanPreferencesKey("fallback")
        val RATE = floatPreferencesKey("speech_rate")
        val VOICE_ENGINE = stringPreferencesKey("voice_engine")
        val VOICE_GENDER = stringPreferencesKey("voice_gender")
        val TEXT_SCALE = floatPreferencesKey("overlay_text_scale")
        val OPACITY = floatPreferencesKey("overlay_opacity")
        val LIGHT = booleanPreferencesKey("overlay_light")
    }

    val settings: Flow<AppSettings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map(::read)

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    private fun read(p: Preferences): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            targetLang = p[Keys.TARGET] ?: defaults.targetLang,
            service = ServiceType.fromKey(p[Keys.SERVICE]),
            deeplKey = p[Keys.DEEPL_KEY] ?: "",
            microsoftKey = p[Keys.MS_KEY] ?: "",
            microsoftRegion = p[Keys.MS_REGION] ?: "",
            fallback = p[Keys.FALLBACK] ?: defaults.fallback,
            speechRate = p[Keys.RATE] ?: defaults.speechRate,
            voiceEngine = VoiceEngine.fromKey(p[Keys.VOICE_ENGINE]),
            voiceGender = VoiceGender.fromKey(p[Keys.VOICE_GENDER]),
            overlayTextScale = p[Keys.TEXT_SCALE] ?: defaults.overlayTextScale,
            overlayOpacity = p[Keys.OPACITY] ?: defaults.overlayOpacity,
            overlayLight = p[Keys.LIGHT] ?: defaults.overlayLight,
        )
    }

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[Keys.TARGET] = s.targetLang
        p[Keys.SERVICE] = s.service.key
        p[Keys.DEEPL_KEY] = s.deeplKey
        p[Keys.MS_KEY] = s.microsoftKey
        p[Keys.MS_REGION] = s.microsoftRegion
        p[Keys.FALLBACK] = s.fallback
        p[Keys.RATE] = s.speechRate
        p[Keys.VOICE_ENGINE] = s.voiceEngine.key
        p[Keys.VOICE_GENDER] = s.voiceGender.key
        p[Keys.TEXT_SCALE] = s.overlayTextScale
        p[Keys.OPACITY] = s.overlayOpacity
        p[Keys.LIGHT] = s.overlayLight
    }
}
