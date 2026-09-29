# Android app launching and voice input

The launcher-app `<queries>` declaration in `app/src/main/AndroidManifest.xml` is required because the app targets Android 11 (API 30) and newer. It lets `PackageManager.getLaunchIntentForPackage()` discover apps with launcher activities. App commands first try known package IDs and then match the installed launcher label, so other installed launcher apps can be opened by name without adding package IDs. Use the app's displayed launcher name in commands such as `Maps ilovasini och`.

Voice recognition requests Uzbek as a language preference rather than a strict language requirement. Recognition availability and supported languages depend on the speech service installed on the device; if recognition returns no text, the app asks the user to check Uzbek language support or type the command instead.

Speech output selects an installed Uzbek text-to-speech voice (preferring Uzbekistan Uzbek and an offline voice). It does not silently fall back to another language. If the device has no Uzbek TTS voice, the app offers the system's TTS voice-data installer and refreshes voice availability when the user returns.

To open Needle Assistant while using another app, add the `Needle yordamchi` tile from the Android Quick Settings tile editor (swipe down twice, tap Edit, and drag the tile into the active area). Android requires a user gesture to open the tile; it opens the assistant screen and does not listen in the background.

## Assistant permission setup

The first launch shows an opt-in checklist; it is also available from the checklist icon in the chat toolbar. The app rechecks permission and role state when returning from Android Settings. Overlay and microphone enable the currently implemented floating control and speech recognition. Accessibility and notification listener entries are registered for setup visibility, but their listeners intentionally do not inspect, store, click, or send anything yet; enabling them is optional and does not make app control work. The Default Assistant, Dialer, and SMS roles require complete `VoiceInteractionService`, `InCallService`/dialer UI, and SMS app/provider implementations respectively, so the screen explains that limitation. Contacts, call, and SMS runtime permissions are not requested until a corresponding feature exists. Device Admin is not requested; Device Owner requires managed-device provisioning and is not a general-purpose permission.

## Groq AI

Set a Groq API key with the gear button in the chat. Unknown questions are sent to Groq over HTTPS using the OpenAI-compatible chat completions endpoint; device commands handled locally do not need the network. The key is stored encrypted with an Android Keystore AES-GCM key, and can be removed from the same dialog. The configured `openai/gpt-oss-120b` model is a Groq-hosted model ID; requests consume the account's Groq limits. Never commit an API key or ship one inside the APK. A mobile app cannot keep a provider key secret from a device owner or a modified client; use a backend proxy for a public production app. If a key is accidentally pasted into chat or source control, revoke it and create a replacement.
