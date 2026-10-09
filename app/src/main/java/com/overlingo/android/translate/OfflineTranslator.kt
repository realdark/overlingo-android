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
 * Превод на самия телефон (Google ML Kit) - без интернет, след като езиковите
 * модели (~30 MB на език) са свалени веднъж. Езикът на оригинала се разпознава
 * автоматично (ML Kit Language ID, вграден в APK-то).
 *
 * allowDownload = false: само с вече свалени модели (за резервен превод,
 * когато няма интернет) - иначе хвърля OFFLINE_MODEL_MISSING.
 */
class OfflineTranslator(context: Context) {

    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val identifier = LanguageIdentification.getClient()
    private val modelManager = RemoteModelManager.getInstance()

    /** Отворени преводачи по двойка езици ("en>bg") - създават се веднъж. */
    private val clients = mutableMapOf<String, MlKitTranslator>()

    /** Превод и изтриване на модел не вървят едновременно (иначе изтриването затваря ползван преводач). */
    private val lock = Mutex()

    private val _downloading = MutableStateFlow<String?>(null)

    /** Код на езика (ML Kit), чийто модел се сваля в момента - за съобщение в интерфейса. */
    val downloading: StateFlow<String?> = _downloading.asStateFlow()

    suspend fun translate(texts: List<String>, target: Language, allowDownload: Boolean): List<String> {
        val targetCode = mlKitCode(target.tag)
            ?: throw TranslationException(TranslationException.Kind.OFFLINE_UNSUPPORTED, target.tag)
        // Езикът се разпознава веднъж за целия текст от снимката: кратките блокове
        // поотделно често се "познават" грешно (и това би свалило излишни модели).
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

    /** Сваля модела за езика, ако го няма (само първия път; нужен е интернет). */
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
        // Без мрежа свалянето на ML Kit може да "виси" безкрайно - проверяваме предварително.
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

    /** Езикът на текста; при несигурност - английски (най-честият случай при снимки). */
    private suspend fun detectLanguage(text: String): String {
        val tag = try {
            identifier.identifyLanguage(text).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UNDETERMINED
        }
        // "ru-Latn", "bg-Latn"... = транскрипция на латиница; моделът за кирилица не я превежда.
        if (tag.endsWith("-Latn")) return TranslateLanguage.ENGLISH
        return mlKitCode(tag) ?: TranslateLanguage.ENGLISH
    }

    private fun hasInternet(): Boolean {
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Свалените езикови модели (кодове на ML Kit, напр. "en", "bg"). Английският е вграден винаги. */
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
         * BCP-47 етикет -> код на ML Kit, или null ако ML Kit не поддържа езика.
         * "nb" (норвежки) при ML Kit е "no"; "zh-CN" -> "zh".
         */
        fun mlKitCode(tag: String): String? {
            val base = tag.substringBefore('-').lowercase()
            val code = if (base == "nb") "no" else base
            return TranslateLanguage.fromLanguageTag(code)
        }
    }
}
