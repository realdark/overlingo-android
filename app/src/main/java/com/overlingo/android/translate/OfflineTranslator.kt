package com.overlingo.android.translate

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator as MlKitTranslator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.overlingo.android.data.Language
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * On-device translation (Google ML Kit) - works without internet once the language
 * models (~30 MB per language) have been downloaded. The source language is detected
 * automatically (ML Kit Language ID, bundled in the APK).
 *
 * allowDownload = false: only with already downloaded models (for fallback translation
 * when there's no internet) - otherwise throws OFFLINE_MODEL_MISSING.
 */
class OfflineTranslator(context: Context) {

    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val identifier = LanguageIdentification.getClient()
    private val modelManager = RemoteModelManager.getInstance()

    /** Open translators per language pair ("en>bg") - created only once. */
    private val clients = mutableMapOf<String, MlKitTranslator>()

    /** Translating and deleting a model never run concurrently (otherwise deletion would close a translator in use). */
    private val lock = Mutex()

    private val _downloading = MutableStateFlow<String?>(null)

    /** ML Kit code of the language whose model is currently downloading - for a message in the UI. */
    val downloading: StateFlow<String?> = _downloading.asStateFlow()

    suspend fun translate(texts: List<String>, target: Language, allowDownload: Boolean): List<String> {
        val targetCode = mlKitCode(target.tag)
            ?: throw TranslationException(TranslationException.Kind.OFFLINE_UNSUPPORTED, target.tag)
        // The language is detected once for all the text in the photo: short blocks on
        // their own are often "guessed" wrong (which would download unneeded models).
        val source = detectLanguage(texts.filter { it.isNotBlank() }.joinToString(" "))
        if (source == targetCode) return texts
        return lock.withLock {
            ensureModel(source, allowDownload)
            ensureModel(targetCode, allowDownload)
            val client = clients.getOrPut("$source>$targetCode") {
                Translation.getClient(
                    TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(targetCode).build()
                )
            }
            texts.map { text -> if (text.isBlank()) text else translateOne(client, text) }
        }
    }

    private suspend fun translateOne(client: MlKitTranslator, text: String): String = try {
        client.translate(text).await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw TranslationException(TranslationException.Kind.OTHER, "ML Kit: ${e.message}", e)
    }

    /** Downloads the language model if missing (first time only; requires internet). */
    private suspend fun ensureModel(code: String, allowDownload: Boolean) {
        val model = TranslateRemoteModel.Builder(code).build()
        val downloaded = try {
            modelManager.isModelDownloaded(model).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException(TranslationException.Kind.OTHER, "ML Kit: ${e.message}", e)
        }
        if (downloaded) return
        if (!allowDownload) throw TranslationException(TranslationException.Kind.OFFLINE_MODEL_MISSING, code)
        // Without a network the ML Kit download can "hang" forever - so we check beforehand.
        if (!hasInternet()) throw TranslationException(TranslationException.Kind.OFFLINE_DOWNLOAD_FAILED, code)
        _downloading.value = code
        try {
            modelManager.download(model, DownloadConditions.Builder().build()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException(TranslationException.Kind.OFFLINE_DOWNLOAD_FAILED, code, e)
        } finally {
            _downloading.value = null
        }
    }

    /** The language of the text; if uncertain - English (the most common case for photos). */
    private suspend fun detectLanguage(text: String): String {
        val tag = try {
            identifier.identifyLanguage(text).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UNDETERMINED
        }
        // "ru-Latn", "bg-Latn"... = Latin transliteration; the Cyrillic model can't translate it.
        if (tag.endsWith("-Latn")) return TranslateLanguage.ENGLISH
        return mlKitCode(tag) ?: TranslateLanguage.ENGLISH
    }

    private fun hasInternet(): Boolean {
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** The downloaded language models (ML Kit codes, e.g. "en", "bg"). English is always built in. */
    suspend fun downloadedLanguages(): Set<String> =
        modelManager.getDownloadedModels(TranslateRemoteModel::class.java).await()
            .map { it.language }
            .toSet()

    suspend fun deleteLanguage(code: String) {
        lock.withLock {
            clients.keys.filter { key -> key.split('>').contains(code) }.forEach { key -> clients.remove(key)?.close() }
            modelManager.deleteDownloadedModel(TranslateRemoteModel.Builder(code).build()).await()
        }
    }

    fun close() {
        identifier.close()
        clients.values.forEach { it.close() }
        clients.clear()
    }

    companion object {
        private const val UNDETERMINED = "und"

        /**
         * BCP-47 tag -> ML Kit code, or null if ML Kit doesn't support the language.
         * "nb" (Norwegian) is "no" in ML Kit; "zh-CN" -> "zh".
         */
        fun mlKitCode(tag: String): String? {
            val base = tag.substringBefore('-').lowercase()
            val code = if (base == "nb") "no" else base
            return TranslateLanguage.fromLanguageTag(code)
        }
    }
}
