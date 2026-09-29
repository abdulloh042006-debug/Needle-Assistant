# Android app launching and voice input

The launcher-app `<queries>` declaration in `app/src/main/AndroidManifest.xml` is required because the app targets Android 11 (API 30) and newer. It lets `PackageManager.getLaunchIntentForPackage()` discover apps with launcher activities. App commands first try known package IDs and then match the installed launcher label, so vendor variants need not be added as package IDs. Keep this declaration if changing the target SDK or app-launch implementation.

Voice recognition requests Uzbek as a language preference rather than a strict language requirement. Recognition availability and supported languages depend on the speech service installed on the device; if recognition returns no text, the app asks the user to check Uzbek language support or type the command instead.

Speech output selects an installed Uzbek text-to-speech voice (preferring Uzbekistan Uzbek and an offline voice). It does not silently fall back to another language. If the device has no Uzbek TTS voice, the app offers the system's TTS voice-data installer and refreshes voice availability when the user returns.
