package com.needleassistant.app.system

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UzbekLocaleSelectionTest {
    @Test
    fun prefersUzbekUzbekistanWhenAvailable() {
        val uzbekistan = Locale.forLanguageTag("uz-UZ")
        val selected = chooseUzbekLocale(
            listOf(Locale.forLanguageTag("uz"), Locale.forLanguageTag("uz-Cyrl"), uzbekistan)
        )

        assertEquals(uzbekistan, selected)
    }

    @Test
    fun returnsNullWhenUzbekIsUnavailableInsteadOfSelectingAnotherLanguage() {
        val selected = chooseUzbekLocale(listOf(Locale.forLanguageTag("ru-RU"), Locale.US))

        assertNull(selected)
    }

    @Test
    fun matchesAppByInstalledLauncherLabel() {
        assertTrue(isMatchingLauncherLabel("Telegram X", "Telegram"))
        assertFalse(isMatchingLauncherLabel("YouTube Music", "YouTube"))
    }
}
