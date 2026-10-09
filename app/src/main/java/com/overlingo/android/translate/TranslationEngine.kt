package com.overlingo.android.translate

import com.overlingo.android.data.AppSettings
import com.overlingo.android.data.Language
import com.overlingo.android.data.ServiceType
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient

/**
 * Already translated text -> translation, so the same request isn't sent again.
 * Key: the text (ignoring whitespace and case differences) + the target language.
 * Keeps at most maxItems entries; when full, the least recently used one is dropped.
 */
class TranslationCache(private val maxItems: Int = 500) {

    private val map = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > maxItems
    }

    @Synchronized
    fun get(text: String, targetCode: String): String? = map[key(text, targetCode)]

    @Synchronized
    fun put(text: String, translation: String, targetCode: String) {
        map[key(text, targetCode)] = translation
    }

    @Synchronized
    fun size() = map.size

    private fun key(text: String, targetCode: String) =
        text.trim().replace(WHITESPACE, " ").lowercase() + "|" + targetCode.uppercase()

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

/**
 * Result of translating several texts.
 * usedFallback - the paid service failed and Google was used instead;
 * usedOffline - there was no internet and it was translated on the device (with downloaded models).
 */
data class TranslationOutcome(
    val translations: List<String>,
    val usedFallback: Boolean = false,
    val usedOffline: Boolean = false,
)

/** The offline translator as a regular Translator. */
class OfflineAdapter(private val offline: OfflineTranslator, private val allowDownload: Boolean) : Translator {
    override suspend fun translate(texts: List<String>, target: Language): List<String> =
        offline.translate(texts, target, allowDownload)
}

/**
 * Picks the translator based on the settings and uses the cache. Fallbacks:
 *  - paid service (DeepL/Microsoft) returned an error -> Google;
 *  - no internet -> on-device translation, if the language models are already downloaded.
 */
class TranslationEngine(
    private val http: OkHttpClient,
    private val offline: OfflineTranslator? = null,
    private val cache: TranslationCache = TranslationCache(),
    private val factory: (AppSettings, OkHttpClient) -> Translator = { s, h -> createTranslator(s, h, offline) },
    private val fallbackFactory: (OkHttpClient) -> Translator = { h -> GoogleTranslator(h) },
    private val offlineFallback: Translator? = offline?.let { OfflineAdapter(it, allowDownload = false) },
) {

    suspend fun translate(texts: List<String>, settings: AppSettings): TranslationOutcome {
        val target = settings.target
        // The service is part of the cache key: after switching Google -> DeepL we want DeepL's translation, not the old one.
        val cacheKey = "${target.code}/${settings.service.key}"
        val result = arrayOfNulls<String>(texts.size)
        val missing = mutableListOf<Int>()
        texts.forEachIndexed { i, text ->
            val cached = cache.get(text, cacheKey)
            if (cached != null) result[i] = cached else missing += i
        }

        var usedFallback = false
        var usedOffline = false
        if (missing.isNotEmpty()) {
            val toTranslate = missing.map { texts[it] }
            val translated = try {
                factory(settings, http).translate(toTranslate, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranslationException) {
                when {
                    canFallbackToGoogle(settings, e) -> {
                        usedFallback = true
                        fallbackFactory(http).translate(toTranslate, target)
                    }
                    e.kind == TranslationException.Kind.NO_INTERNET && offlineFallback != null -> {
                        usedOffline = true
                        try {
                            offlineFallback.translate(toTranslate, target)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (offlineError: Exception) {
                            throw e // models not downloaded - show "no internet"
                        }
                    }
                    else -> throw e
                }
            }
            missing.forEachIndexed { i, index ->
                val text = translated.getOrElse(i) { "" }
                result[index] = text
                // Fallback translations aren't cached - so the next attempt tries the primary service again.
                if (text.isNotBlank() && !usedFallback && !usedOffline) cache.put(texts[index], text, cacheKey)
            }
        }
        return TranslationOutcome(result.map { it.orEmpty() }, usedFallback, usedOffline)
    }

    private fun canFallbackToGoogle(settings: AppSettings, e: TranslationException): Boolean =
        settings.fallback &&
            (settings.service == ServiceType.DEEPL || settings.service == ServiceType.MICROSOFT) &&
            e.kind != TranslationException.Kind.MISSING_KEY &&
            e.kind != TranslationException.Kind.NO_INTERNET

    companion object {
        fun createTranslator(settings: AppSettings, http: OkHttpClient, offline: OfflineTranslator?): Translator =
            when (settings.service) {
                ServiceType.GOOGLE -> GoogleTranslator(http)
                ServiceType.DEEPL -> DeepLTranslator(http, settings.deeplKey)
                ServiceType.MICROSOFT -> MicrosoftTranslator(http, settings.microsoftKey, settings.microsoftRegion)
                ServiceType.OFFLINE -> OfflineAdapter(
                    offline ?: throw TranslationException(TranslationException.Kind.OFFLINE_UNSUPPORTED),
                    allowDownload = true,
                )
            }
    }
}
