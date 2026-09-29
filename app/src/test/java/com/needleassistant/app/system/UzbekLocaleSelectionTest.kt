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
        assertTrue(isMatchingLauncherLabel("Google Maps", "Maps"))
        assertTrue(isMatchingLauncherLabel("Yandex Music", "Yandeks muzik"))
        assertTrue(isMatchingLauncherLabel("Yandex Music", "Yandeks aymi"))
    }

    @Test
    fun extractsUzbekAndEnglishAppOpenCommands() {
        assertEquals("telegram", extractAppNameToOpen("telegramni och"))
        assertEquals("instagram", extractAppNameToOpen("Instagram ilovasini oching"))
        assertEquals("google maps", extractAppNameToOpen("open Google Maps"))
        assertNull(extractAppNameToOpen("fonarni yoq"))
    }

    @Test
    fun parsesUzbekMediaCommands() {
        assertEquals(MediaAction.PLAY, parseMediaAction("musiqani qo'y"))
        assertEquals(MediaAction.PLAY, parseMediaAction("qo'shiqni qo'y"))
        assertEquals(MediaAction.PAUSE, parseMediaAction("musiqani to'xtat"))
        assertEquals(MediaAction.NEXT, parseMediaAction("keyingi qo'shiq"))
        assertEquals(MediaAction.PREVIOUS, parseMediaAction("oldingi trek"))
        assertNull(parseMediaAction("Yandex Musicni och"))
    }

    @Test
    fun parsesTelegramUsernameAndDraftWithoutAutoSending() {
        assertEquals(
            TelegramChatCommand("needle_user", null),
            parseTelegramCommand("telegram @needle_user chatni och")
        )
        assertEquals(
            TelegramChatCommand("needle_user", "Salom, yaxshimisiz?"),
            parseTelegramCommand("telegram @needle_user ga yoz Salom, yaxshimisiz?")
        )
        assertNull(parseTelegramCommand("telegram 998901234567 ga yoz salom"))
    }

    @Test
    fun parsesSmsAndCallDraftsWithoutStartingCallsOrSendingMessages() {
        assertEquals(
            MessageDraftCommand("+998901234567", "Salom"),
            parseMessageDraftCommand("sms +998 90 123 45 67 Salom")
        )
        assertEquals("+998901234567", parseDialCommand("qo'ng'iroq +998 90 123 45 67"))
        assertNull(parseDialCommand("qo'ng'iroq yordamga"))
    }

    @Test
    fun parsesOnlyExplicitScrollCommands() {
        assertEquals(true, parseScrollCommand("pastga scroll"))
        assertEquals(false, parseScrollCommand("yuqoriga aylantir"))
        assertNull(parseScrollCommand("video och"))
    }
}
