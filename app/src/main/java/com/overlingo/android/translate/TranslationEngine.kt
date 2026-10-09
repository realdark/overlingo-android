package com.overlingo.android.translate

import com.overlingo.android.data.AppSettings
import com.overlingo.android.data.Language
import com.overlingo.android.data.ServiceType
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient

/**
 * Вече преведен текст -> превод, за да не се праща същата заявка пак.
 * Ключ: текстът (без разлика в интервалите и главните букви) + целевият език.
 * Пази най-много maxItems записа; при препълване отпада най-отдавна ползваният.
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
 * Резултат от превода на няколко текста.
 * usedFallback - платената услуга не сработи и е ползван Google;
 * usedOffline - нямаше интернет и е преведено на телефона (със свалени модели).
 */
data class TranslationOutcome(
    val translations: List<String>,
    val usedFallback: Boolean = false,
    val usedOffline: Boolean = false,
)

/** Офлайн преводачът като обикновен Translator. */
class OfflineAdapter(private val offline: OfflineTranslator, private val allowDownload: Boolean) : Translator {
    override suspend fun translate(texts: List<String>, target: Language): List<String> =
        offline.translate(texts, target, allowDownload)
}

/**
 * Избира преводача според настройките и ползва кеша. Резервни варианти:
 *  - платена услуга (DeepL/Microsoft) даде грешка -> Google;
 *  - няма интернет -> превод на телефона, ако моделите за езиците вече са свалени.
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
        // В кеша влиза и услугата: след смяна на Google -> DeepL искаме превода на DeepL, не стария.
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
                            throw e // моделите не са свалени - показваме "няма интернет"
                        }
                    }
                    else -> throw e
                }
            }
            missing.forEachIndexed { i, index ->
                val text = translated.getOrElse(i) { "" }
                result[index] = text
                // Резервните преводи не се кешират - при следващ опит да пробва основната услуга.
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
