package com.overlingo.android.data

import java.util.Locale

/**
 * A language and its codes for the various services.
 *
 * code  - as in the desktop version ("BG", "EN"...), stored in the settings
 * tag   - BCP-47 tag: language name and text-to-speech voice ("bg", "zh-CN")
 */
data class Language(
    val code: String,
    val tag: String,
    val google: String = tag,
    val deepl: String = code,
    val microsoft: String = tag,
) {
    val locale: Locale get() = Locale.forLanguageTag(tag)

    /** The language's name in the UI language, e.g. "German" / "Allemand" (French UI). */
    fun displayName(uiLocale: Locale): String =
        locale.getDisplayName(uiLocale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(uiLocale) else it.toString() }
}

object Languages {

    val all: List<Language> = listOf(
        Language("BG", "bg"),
        Language("EN", "en", deepl = "EN-US"),
        Language("DE", "de"),
        Language("FR", "fr"),
        Language("ES", "es"),
        Language("IT", "it"),
        Language("PT", "pt", deepl = "PT-PT"),
        Language("NL", "nl"),
        Language("PL", "pl"),
        Language("CS", "cs"),
        Language("SK", "sk"),
        Language("SL", "sl"),
        Language("HR", "hr"),
        Language("SR", "sr", microsoft = "sr-Cyrl"),
        Language("MK", "mk"),
        Language("RO", "ro"),
        Language("HU", "hu"),
        Language("EL", "el"),
        Language("TR", "tr"),
        Language("RU", "ru"),
        Language("UK", "uk"),
        Language("SV", "sv"),
        Language("DA", "da"),
        Language("NO", "nb", google = "no", deepl = "NB"),
        Language("FI", "fi"),
        Language("ET", "et"),
        Language("LV", "lv"),
        Language("LT", "lt"),
        Language("AR", "ar"),
        Language("HE", "he", google = "iw"),
        Language("HI", "hi"),
        Language("ZH", "zh-CN", deepl = "ZH", microsoft = "zh-Hans"),
        Language("JA", "ja"),
        Language("KO", "ko"),
    )

    fun byCode(code: String?): Language? = all.firstOrNull { it.code.equals(code, ignoreCase = true) }

    /** Default target language: the phone's language if it is in the list, otherwise English. */
    fun defaultTarget(): Language {
        val system = Locale.getDefault().language
        return all.firstOrNull { it.locale.language == system } ?: byCode("EN")!!
    }

    fun sortedFor(uiLocale: Locale): List<Language> = all.sortedBy { it.displayName(uiLocale) }
}
