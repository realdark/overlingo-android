package com.overlingo.android.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.overlingo.android.R
import com.overlingo.android.data.AppSettings
import com.overlingo.android.data.SettingsRepository
import com.overlingo.android.export.ImageExporter
import com.overlingo.android.export.OverlayRenderer
import com.overlingo.android.export.OverlayStyle
import com.overlingo.android.ocr.ImageLoader
import com.overlingo.android.ocr.OcrEngine
import com.overlingo.android.ocr.OcrException
import com.overlingo.android.ocr.TextBlock
import com.overlingo.android.translate.OfflineTranslator
import com.overlingo.android.translate.TranslationEngine
import com.overlingo.android.translate.TranslationOutcome
import com.overlingo.android.translate.TranslationException
import com.overlingo.android.translate.TranslationException.Kind
import com.overlingo.android.tts.Speaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

enum class Screen { CAMERA, RESULT, TEXT, SETTINGS }

/** Message to the user: text from resources + an (optional) technical detail. */
data class UiMessage(@StringRes val res: Int, val detail: String = "")

sealed interface Phase {
    data object Recognizing : Phase
    data object Translating : Phase
    data object Done : Phase
    data class Failed(val message: UiMessage) : Phase
}

/** The photo, the detected blocks and their translations (null - still being translated). */
data class PhotoState(
    val bitmap: Bitmap,
    val blocks: List<TextBlock> = emptyList(),
    val translations: List<String>? = null,
    val phase: Phase = Phase.Recognizing,
    val showOriginal: Boolean = false,
    /** Whether recognition is done - then on a language change we only re-translate. */
    val recognized: Boolean = false,
    /** The language of the translation - on change we re-translate (without a new OCR). */
    val translatedTo: String = "",
    val translatedWith: String = "",
    /** Hidden translation windows (by block index) - not drawn, read aloud or saved. */
    val hidden: Set<Int> = emptySet(),
) {
    /** The translations without the hidden ones ("" in their place - skipped everywhere). */
    val visibleTranslations: List<String>?
        get() = withoutHidden(translations, hidden)
}

/** The translations with hidden blocks replaced by "" (an empty translation isn't drawn, read aloud or saved). */
fun withoutHidden(translations: List<String>?, hidden: Set<Int>): List<String>? =
    if (hidden.isEmpty()) translations
    else translations?.mapIndexed { index, text -> if (index in hidden) "" else text }

data class TextState(
    val input: String = "",
    val output: String = "",
    val busy: Boolean = false,
    val error: UiMessage? = null,
)

enum class TtsProblem { MISSING_DATA, NOT_SUPPORTED }

data class UiState(
    val screen: Screen = Screen.CAMERA,
    val previous: Screen = Screen.CAMERA,
    val photo: PhotoState? = null,
    val text: TextState = TextState(),
    val toast: UiMessage? = null,
    val ttsProblem: TtsProblem? = null,
    /** Downloaded offline translation models (ML Kit codes) - for Settings. */
    val offlineModels: Set<String> = emptySet(),
    /** Photo with translations ready to share - the UI opens the "Share" sheet and clears it. */
    val shareUri: Uri? = null,
    val exporting: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val repository = SettingsRepository(application)
    private val ocr = OcrEngine()
    private val offline = OfflineTranslator(application)
    private val engine = TranslationEngine(http, offline)
    private val speaker = Speaker(application, http) { event -> onSpeakerEvent(event) }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val settings: StateFlow<AppSettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val speakingText: StateFlow<String?> = speaker.speakingText

    /** The language (ML Kit code) whose offline translation model is currently downloading. */
    val offlineDownloading: StateFlow<String?> = offline.downloading

    private var photoJob: Job? = null
    private var textJob: Job? = null
    private var settingsJob: Job? = null

    // ------------------------------------------------------------------
    // Photos
    // ------------------------------------------------------------------

    /** Called from the camera thread - rotation and downscaling happen in the background. */
    fun onCameraPhoto(raw: Bitmap, rotationDegrees: Int) {
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) { ImageLoader.fromCamera(raw, rotationDegrees) }
            // If the user has meanwhile opened Settings or "Aa", don't pull them away from there.
            if (_state.value.screen == Screen.CAMERA) startPhoto(bitmap)
        }
    }

    fun onImageUri(uri: Uri) {
        viewModelScope.launch {
            val bitmap = try {
                withContext(Dispatchers.IO) { ImageLoader.fromUri(getApplication(), uri) }
            } catch (e: Exception) {
                showToast(UiMessage(R.string.error_open_image, e.message.orEmpty()))
                return@launch
            }
            startPhoto(bitmap)
        }
    }

    private fun startPhoto(bitmap: Bitmap) {
        photoJob?.cancel()
        speaker.stop()
        _state.update { it.copy(screen = Screen.RESULT, photo = PhotoState(bitmap)) }
        photoJob = viewModelScope.launch { runPhotoPipeline(reuseBlocks = false) }
    }

    /** Recognizes (if needed) and translates the current photo. */
    private suspend fun runPhotoPipeline(reuseBlocks: Boolean) {
        val photo = _state.value.photo ?: return
        val settings = repository.settings.first()
        try {
            val blocks = if (reuseBlocks && photo.recognized) {
                photo.blocks
            } else {
                updatePhoto { it.copy(phase = Phase.Recognizing, translations = null) }
                val found = ocr.recognize(photo.bitmap)
                updatePhoto { it.copy(blocks = found, recognized = true, hidden = emptySet()) }
                found
            }
            if (blocks.isEmpty()) {
                updatePhoto { it.copy(phase = Phase.Done, translations = emptyList()) }
                return
            }

            updatePhoto { it.copy(phase = Phase.Translating, translations = null) }
            // Paragraphs are translated separately (otherwise the blank line between them is lost)
            // and then regrouped back into windows.
            val outcome = engine.translate(blocks.flatMap { it.paragraphTexts }, settings)
            val translated = outcome.translations.iterator()
            val perBlock = blocks.map { block ->
                block.paragraphTexts.joinToString(TextBlock.PARAGRAPH_SEPARATOR) { translated.next() }
            }
            updatePhoto {
                it.copy(
                    phase = Phase.Done,
                    translations = perBlock,
                    translatedTo = settings.target.code,
                    translatedWith = settings.service.key,
                )
            }
            notifyFallback(outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updatePhoto { it.copy(phase = Phase.Failed(messageFor(e))) }
        }
    }

    fun retryPhoto() {
        photoJob?.cancel()
        photoJob = viewModelScope.launch { runPhotoPipeline(reuseBlocks = true) }
    }

    fun toggleOriginal() = updatePhoto { it.copy(showOriginal = !it.showOriginal) }

    /** Hides the translation window from the photo (or shows it again). */
    fun toggleHidden(index: Int) = updatePhoto {
        it.copy(hidden = if (index in it.hidden) it.hidden - index else it.hidden + index)
    }

    /** Shows all hidden windows again. */
    fun showAllHidden() = updatePhoto { it.copy(hidden = emptySet()) }

    fun backToCamera() {
        photoJob?.cancel()
        speaker.stop()
        _state.update { it.copy(screen = Screen.CAMERA, photo = null) }
    }

    private fun updatePhoto(transform: (PhotoState) -> PhotoState) {
        _state.update { s -> s.photo?.let { s.copy(photo = transform(it)) } ?: s }
    }

    // ------------------------------------------------------------------
    // Text translation ("Aa" button and "Share" with text)
    // ------------------------------------------------------------------

    fun openTextScreen(initial: String? = null) {
        _state.update {
            val text = if (initial != null) TextState(input = initial) else it.text
            it.copy(screen = Screen.TEXT, text = text)
        }
        if (!initial.isNullOrBlank()) translateText()
    }

    fun onSharedText(text: String) = openTextScreen(text)

    fun setTextInput(value: String) = _state.update { it.copy(text = it.text.copy(input = value)) }

    fun translateText() {
        val input = _state.value.text.input.trim()
        if (input.isEmpty()) return
        textJob?.cancel()
        _state.update { it.copy(text = it.text.copy(busy = true, error = null)) }
        textJob = viewModelScope.launch {
            try {
                val outcome = engine.translate(listOf(input), repository.settings.first())
                _state.update { it.copy(text = it.text.copy(output = outcome.translations.first(), busy = false)) }
                notifyFallback(outcome)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(text = it.text.copy(busy = false, error = messageFor(e))) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    fun openSettings() = _state.update { it.copy(screen = Screen.SETTINGS, previous = it.screen) }

    fun back() {
        when (_state.value.screen) {
            Screen.SETTINGS -> {
                _state.update { it.copy(screen = it.previous) }
                refreshAfterSettings()
            }
            Screen.RESULT -> backToCamera()
            Screen.TEXT -> {
                textJob?.cancel()
                speaker.stop()
                _state.update { it.copy(screen = Screen.CAMERA, text = it.text.copy(busy = false)) }
            }
            Screen.CAMERA -> Unit
        }
    }

    /** After a settings change: re-translates if the language or the service changed. */
    private fun refreshAfterSettings() {
        viewModelScope.launch {
            settingsJob?.join() // the last change (e.g. DeepL just selected) must be saved first
            val settings = repository.settings.first()
            val photo = _state.value.photo
            val screen = _state.value.screen
            if (screen == Screen.RESULT && photo != null) {
                val changed = photo.translatedTo != settings.target.code ||
                    photo.translatedWith != settings.service.key
                if (changed || photo.phase is Phase.Failed) retryPhoto()
            }
            if (screen == Screen.TEXT && _state.value.text.output.isNotEmpty()) translateText()
        }
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val previous = settingsJob
        settingsJob = viewModelScope.launch {
            previous?.join() // writes happen one after another, in the order of the changes
            repository.update(transform)
        }
    }

    /** Change of target language (from the chip at the top) - the translation updates immediately. */
    fun setTargetLanguage(code: String) {
        viewModelScope.launch {
            repository.update { it.copy(targetLang = code) }
            when (_state.value.screen) {
                Screen.RESULT -> retryPhoto()
                Screen.TEXT -> if (_state.value.text.input.isNotBlank()) translateText()
                else -> Unit
            }
        }
    }

    /** Downloaded offline translation models - refreshed when Settings is opened. */
    fun refreshOfflineModels() {
        viewModelScope.launch {
            val models = try {
                offline.downloadedLanguages()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptySet()
            }
            _state.update { it.copy(offlineModels = models) }
        }
    }

    fun deleteOfflineModel(code: String) {
        viewModelScope.launch {
            try {
                offline.deleteLanguage(code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast(UiMessage(R.string.error_generic, e.message.orEmpty()))
            }
            refreshOfflineModels()
        }
    }

    // ------------------------------------------------------------------
    // Saving / sharing the photo with translations
    // ------------------------------------------------------------------

    /** share = true: prepares a file for "Share"; false: saves to the gallery. */
    fun exportPhoto(share: Boolean) {
        val photo = _state.value.photo ?: return
        if (_state.value.exporting) return
        val translations = if (photo.showOriginal) null else photo.visibleTranslations
        val style = OverlayStyle.from(settings.value)
        _state.update { it.copy(exporting = true) }
        viewModelScope.launch {
            var image: Bitmap? = null
            try {
                val rendered = withContext(Dispatchers.Default) {
                    OverlayRenderer.render(photo.bitmap, photo.blocks, translations, style)
                }
                image = rendered
                val app = getApplication<Application>()
                if (share) {
                    val uri = withContext(Dispatchers.IO) { ImageExporter.shareableUri(app, rendered) }
                    _state.update { it.copy(shareUri = uri) }
                } else {
                    withContext(Dispatchers.IO) { ImageExporter.saveToGallery(app, rendered) }
                    showToast(UiMessage(R.string.saved_to_gallery))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast(UiMessage(R.string.error_save_image, e.message.orEmpty()))
            } finally {
                // The copy with translations is already written - free it (the on-screen photo stays).
                image?.takeIf { it !== photo.bitmap }?.recycle()
                _state.update { it.copy(exporting = false) }
            }
        }
    }

    fun consumeShareUri() = _state.update { it.copy(shareUri = null) }

    // ------------------------------------------------------------------
    // Voice
    // ------------------------------------------------------------------

    fun toggleSpeak(text: String) {
        if (speakingText.value == text) {
            speaker.stop()
            return
        }
        if (text.isBlank()) return
        val s = settings.value
        val target = s.target
        handleSpeakResult(speaker.speak(text, target.tag, target.locale, s.speechRate, s.voiceEngine, s.voiceGender))
    }

    private fun handleSpeakResult(result: Speaker.Result) {
        when (result) {
            Speaker.Result.STARTED -> Unit
            Speaker.Result.NOT_READY -> showToast(UiMessage(R.string.tts_not_ready))
            Speaker.Result.ERROR -> showToast(UiMessage(R.string.tts_error))
            Speaker.Result.MISSING_DATA -> _state.update { it.copy(ttsProblem = TtsProblem.MISSING_DATA) }
            Speaker.Result.NOT_SUPPORTED -> _state.update { it.copy(ttsProblem = TtsProblem.NOT_SUPPORTED) }
        }
    }

    /** The Microsoft voice didn't work - the phone reads instead; we tell the user why. */
    private fun onSpeakerEvent(event: Speaker.Event) {
        when (event) {
            Speaker.Event.MicrosoftNoVoice -> showToast(UiMessage(R.string.tts_ms_no_voice))
            is Speaker.Event.MicrosoftFailed -> showToast(UiMessage(R.string.tts_ms_failed, event.detail))
            Speaker.Event.MicrosoftOffline -> showToast(UiMessage(R.string.tts_ms_offline))
            is Speaker.Event.PhoneResult -> handleSpeakResult(event.result)
        }
    }

    fun dismissTtsProblem() = _state.update { it.copy(ttsProblem = null) }

    // ------------------------------------------------------------------
    // Messages
    // ------------------------------------------------------------------

    fun showToast(message: UiMessage) = _state.update { it.copy(toast = message) }

    private fun notifyFallback(outcome: TranslationOutcome) {
        when {
            outcome.usedOffline -> showToast(UiMessage(R.string.offline_notice))
            outcome.usedFallback -> showToast(UiMessage(R.string.fallback_notice))
        }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }

    private fun messageFor(e: Throwable): UiMessage = when (e) {
        is TranslationException -> when (e.kind) {
            Kind.NO_INTERNET -> UiMessage(R.string.error_no_internet)
            Kind.MISSING_KEY -> UiMessage(R.string.error_missing_key, e.message.orEmpty())
            Kind.INVALID_KEY -> UiMessage(R.string.error_invalid_key, e.message.orEmpty())
            Kind.RATE_LIMIT -> UiMessage(R.string.error_rate_limit, e.message.orEmpty())
            Kind.OFFLINE_UNSUPPORTED -> UiMessage(R.string.error_offline_unsupported, e.message.orEmpty())
            Kind.OFFLINE_MODEL_MISSING -> UiMessage(R.string.error_offline_model_missing, e.message.orEmpty())
            Kind.OFFLINE_DOWNLOAD_FAILED -> UiMessage(R.string.error_offline_download, e.message.orEmpty())
            Kind.OTHER -> UiMessage(R.string.error_translation, e.message.orEmpty())
        }
        is OcrException -> UiMessage(R.string.error_ocr, e.message.orEmpty())
        else -> UiMessage(R.string.error_generic, e.message ?: e.javaClass.simpleName)
    }

    override fun onCleared() {
        speaker.shutdown()
        ocr.close()
        offline.close()
        super.onCleared()
    }
}
