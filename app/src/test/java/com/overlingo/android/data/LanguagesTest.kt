package com.overlingo.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LanguagesTest {

    @Test
    fun codesAreUnique() {
        val all = Languages.all
        assertEquals(all.size, all.map { it.code }.toSet().size)
    }

    @Test
    fun lookupIgnoresCase() {
        assertEquals("BG", Languages.byCode("bg")?.code)
    }

    @Test
    fun serviceSpecificCodes() {
        val zh = Languages.byCode("ZH")!!
        assertEquals("zh-CN", zh.google)
        assertEquals("zh-Hans", zh.microsoft)
        assertEquals("EN-US", Languages.byCode("EN")!!.deepl)
    }

    @Test
    fun namesAreLocalized() {
        val german = Languages.byCode("DE")!!
        assertEquals("German", german.displayName(Locale.ENGLISH))
        assertTrue(german.displayName(Locale.forLanguageTag("bg")).first().isUpperCase())
    }

    @Test
    fun defaultTargetFollowsPhoneLanguage() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("bg-BG"))
            assertEquals("BG", Languages.defaultTarget().code)
            Locale.setDefault(Locale.forLanguageTag("sw"))
            assertEquals("EN", Languages.defaultTarget().code)
        } finally {
            Locale.setDefault(saved)
        }
    }
}
