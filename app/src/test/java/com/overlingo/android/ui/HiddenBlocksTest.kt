package com.overlingo.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HiddenBlocksTest {
    private val translations = listOf("Здравей", "Изход", "Цена: 5 лв.")

    @Test
    fun hiddenBlocksBecomeEmpty() {
        assertEquals(listOf("Здравей", "", "Цена: 5 лв."), withoutHidden(translations, setOf(1)))
        assertEquals(listOf("", "", ""), withoutHidden(translations, setOf(0, 1, 2)))
    }

    @Test
    fun nothingHiddenKeepsTheSameList() {
        assertSame(translations, withoutHidden(translations, emptySet()))
    }

    @Test
    fun unknownIndexIsIgnored() {
        assertEquals(translations, withoutHidden(translations, setOf(7)))
    }

    @Test
    fun stillTranslating() {
        assertNull(withoutHidden(null, setOf(0)))
    }
}
