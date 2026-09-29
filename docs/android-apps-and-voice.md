# Android app launching and voice input

The launcher-app `<queries>` declaration in `app/src/main/AndroidManifest.xml` is required because the app targets Android 11 (API 30) and newer. It lets `PackageManager.getLaunchIntentForPackage()` discover apps with launcher activities. App commands first try known package IDs and then match the installed launcher label, so other installed launcher apps can be opened by name without adding package IDs. Use the app's displayed launcher name in commands such as `Maps ilovasini och`.

Voice recognition requests Uzbek as a language preference rather than a strict language requirement. Recognition availability and supported languages depend on the speech service installed on the device; if recognition returns no text, the app asks the user to check Uzbek language support or type the command instead.

Speech output selects an installed Uzbek text-to-speech voice (preferring Uzbekistan Uzbek and an offline voice). It does not silently fall back to another language. If the device has no Uzbek TTS voice, the app offers the system's TTS voice-data installer and refreshes voice availability when the user returns.

To open Needle Assistant while using another app, add the `Needle yordamchi` tile from the Android Quick Settings tile editor (swipe down twice, tap Edit, and drag the tile into the active area). Android requires a user gesture to open the tile; it opens the assistant screen and does not listen in the background.

## Assistant permission setup

The first launch shows an opt-in checklist; it is also available from the checklist icon in the chat toolbar. The app rechecks permission and role state when returning from Android Settings. Overlay and microphone enable the currently implemented floating control and speech recognition. The notification listener provides active media-session discovery for play, pause, next, and previous commands; the target media app must expose those controls. Accessibility is registered for setup visibility, but intentionally does not inspect, click, or type into other app UIs; enabling it is optional and does not make UI automation work. The Default Assistant, Dialer, and SMS roles require complete `VoiceInteractionService`, `InCallService`/dialer UI, and SMS app/provider implementations respectively, so the screen explains that limitation. Contacts, call, and SMS runtime permissions are not requested until a corresponding feature exists. Device Admin is not requested; Device Owner requires managed-device provisioning and is not a general-purpose permission.

Media commands include `musiqani qo'y`, `musiqani to'xtat`, `keyingi qo'shiq`, and `oldingi trek`. Enable Needle under Android's Notification Access settings and start playback first. Sessions that do not expose a requested transport action cannot be controlled; accessibility scrolling and arbitrary in-app buttons are not implemented by this media integration.

App names are accent-insensitive and recognize common Uzbek spellings such as `Yandeks muzik`. Entering a launcher app's name by itself also opens it when there is a unique label match. The floating Needle logo can be dragged to either edge; after a short idle delay, it tucks away with a small part visible. Tap the tucked logo once to expand, then tap again to open voice command. Its edge and vertical position are saved on the device.

Telegram commands can open a public username with `telegram @username chatni och` and prepare a draft with `telegram @username ga yoz Salom`. Telegram must be installed and the account must be resolvable; the user sends the prepared text inside Telegram. `sms +998 90 123 45 67 Salom` opens the default SMS app with a draft, and `qo'ng'iroq +998 90 123 45 67` opens the dialer. Needle never sends the SMS or starts the call automatically. Contact-name lookup and Telegram private-contact discovery are not implemented.

When Accessibility is explicitly enabled, `pastga scroll` / `yuqoriga scroll` ask the active screen's first scrollable accessibility node to scroll. The service does not read or store screen text, tap controls, or type into another app. Some screens may not expose a scrollable node or respond to the action.

## Groq AI

Set a Groq API key with the gear button in the chat. Unknown questions are sent to Groq over HTTPS using the OpenAI-compatible chat completions endpoint; device commands handled locally do not need the network. The key is stored encrypted with an Android Keystore AES-GCM key, and can be removed from the same dialog. The configured `openai/gpt-oss-120b` model is a Groq-hosted model ID; requests consume the account's Groq limits. Never commit an API key or ship one inside the APK. A mobile app cannot keep a provider key secret from a device owner or a modified client; use a backend proxy for a public production app. If a key is accidentally pasted into chat or source control, revoke it and create a replacement.
