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

/** Съобщение към потребителя: текст от ресурсите + технически детайл (по желание). */
data class UiMessage(@StringRes val res: Int, val detail: String = "")

sealed interface Phase {
    data object Recognizing : Phase
    data object Translating : Phase
    data object Done : Phase
    data class Failed(val message: UiMessage) : Phase
}

/** Снимката, намерените блокове и техните преводи (null - още се превеждат). */
data class PhotoState(
    val bitmap: Bitmap,
    val blocks: List<TextBlock> = emptyList(),
    val translations: List<String>? = null,
    val phase: Phase = Phase.Recognizing,
    val showOriginal: Boolean = false,
    /** Дали разпознаването е минало - тогава при смяна на езика само превеждаме наново. */
    val recognized: Boolean = false,
    /** На какъв език е преводът - при смяна превеждаме наново (без ново OCR). */
    val translatedTo: String = "",
    val translatedWith: String = "",
    /** Скрити прозорци с превод (по номер на блок) - не се рисуват, четат и запазват. */
    val hidden: Set<Int> = emptySet(),
) {
    /** Преводите без скритите (на тяхно място "" - пропускат се навсякъде). */
    val visibleTranslations: List<String>?
        get() = withoutHidden(translations, hidden)
}

/** Преводите, в които скритите блокове са заменени с "" (празният превод не се рисува, чете и запазва). */
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
    /** Свалените модели за офлайн превод (кодове на ML Kit) - за Настройки. */
    val offlineModels: Set<String> = emptySet(),
    /** Готова за споделяне снимка с преводите - UI отваря менюто "Сподели" и я нулира. */
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

    /** Езикът (код на ML Kit), чийто модел за офлайн превод се сваля в момента. */
    val offlineDownloading: StateFlow<String?> = offline.downloading

    private var photoJob: Job? = null
    private var textJob: Job? = null
    private var settingsJob: Job? = null

    // ------------------------------------------------------------------
    // Снимки
    // ------------------------------------------------------------------

    /** Вика се от нишката на камерата - завъртането и смаляването стават във фонов режим. */
    fun onCameraPhoto(raw: Bitmap, rotationDegrees: Int) {
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) { ImageLoader.fromCamera(raw, rotationDegrees) }
            // Ако междувременно потребителят е отворил Настройки или "Aa", не го дърпаме оттам.
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

    /** Разпознава (ако трябва) и превежда текущата снимка. */
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
            // Абзаците се превеждат поотделно (иначе празният ред между тях се губи)
            // и после се събират обратно по прозорци.
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

    /** Скрива прозореца с превода от снимката (или го показва отново). */
    fun toggleHidden(index: Int) = updatePhoto {
        it.copy(hidden = if (index in it.hidden) it.hidden - index else it.hidden + index)
    }

    /** Показва отново всички скрити прозорци. */
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
    // Превод на текст (бутон "Aa" и "Сподели" с текст)
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
    // Навигация
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

    /** След промяна на настройките: превежда наново, ако е сменен езикът или услугата. */
    private fun refreshAfterSettings() {
        viewModelScope.launch {
            settingsJob?.join() // последната промяна (напр. току-що избран DeepL) трябва да е записана
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
    // Настройки
    // ------------------------------------------------------------------

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val previous = settingsJob
        settingsJob = viewModelScope.launch {
            previous?.join() // записите - един след друг, в реда на промените
            repository.update(transform)
        }
    }

    /** Смяна на целевия език (от чипа горе) - преводът се обновява веднага. */
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

    /** Свалените модели за офлайн превод - обновява се при отваряне на Настройки. */
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
    // Запазване / споделяне на снимката с преводите
    // ------------------------------------------------------------------

    /** share = true: подготвя файл за "Сподели"; false: записва в галерията. */
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
                // Копието с преводите вече е записано - освобождаваме го (снимката на екрана остава).
                image?.takeIf { it !== photo.bitmap }?.recycle()
                _state.update { it.copy(exporting = false) }
            }
        }
    }

    fun consumeShareUri() = _state.update { it.copy(shareUri = null) }

    // ------------------------------------------------------------------
    // Глас
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

    /** Гласът на Microsoft не е проработил - чете телефонът; казваме защо. */
    private fun onSpeakerEvent(event: Speaker.Event) {
        when (event) {
            Speaker.Event.MicrosoftNoVoice -> showToast(UiMessage(R.string.tts_ms_no_voice))
            is Speaker.Event.MicrosoftFailed -> showToast(UiMessage(R.string.tts_ms_failed, event.detail))
            is Speaker.Event.PhoneResult -> handleSpeakResult(event.result)
        }
    }

    fun dismissTtsProblem() = _state.update { it.copy(ttsProblem = null) }

    // ------------------------------------------------------------------
    // Съобщения
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
