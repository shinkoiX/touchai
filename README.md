# TouchAI

A native Android app for quick conversations about the current screen. Requires
Android 11 or later and is intended for personal use as a sideloaded APK.

## Start using it

1. Install `app/build/outputs/apk/debug/app-debug.apk` and open TouchAI.
2. In **Settings → Quick access**, select **Enable** next to Screen capture, then enable
   **TouchAI screen capture** in Android Accessibility settings. Android may first
   require **App info → Allow restricted settings** for a sideloaded installation.
3. Allow notifications. Enable the floating button, the quick-access notification,
   or both, then Save. The floating button appears when you leave TouchAI.
4. Configure the default AI endpoint, API key, model ID, reasoning effort, general
   instructions, and web search. Use Test connection to check the configuration.
5. Edit the presets. Each preset can use the default AI configuration or its own
   endpoint, API key, protocol, model, reasoning effort, instructions, and search
   setting. Your last preset choice is remembered automatically.

Tap the floating button or the notification's **Ask AI** action. TouchAI captures
once, then opens the send panel with the full screenshot attached. Tap the image
to crop it, or use the **×** button at its top right to remove it. The crop selection
starts at the full image. Your last selected preset fills the message box; edit
that text and press **Send**. No image is uploaded before Send. Selecting **No
preset** is also remembered.

Drag the floating button to move it; it snaps to an edge and remembers its position.
The notification works with the floating button disabled. Android may allow ongoing
notifications to be dismissed; reopening TouchAI restores an enabled notification.

## Images and conversations

The crop editor supports resizing, moving the selection, pan/zoom, and reset. Crop
coordinates map to the original pixels regardless of zoom or screen orientation.
Reopening crop keeps the applied selection and retains the original image, so
**Reset** can restore the full screenshot. **Cancel** leaves the attachment unchanged.
Cropping occurs before resizing. **Original** preserves the selected screenshot
pixels as PNG; **Balanced** and **Small** resize to at most 2048 or 1280 pixels on
the longest edge and encode JPEG. Gallery images are initially decoded at a bounded
resolution of at most 4096 pixels on the longest edge.

Answers stream as Markdown. Stop keeps already received text; Copy copies the
answer; Retry reuses the latest request and its original AI configuration without
adding a duplicate turn. Completed turns, including their image inputs, become
follow-up context. Failed, stopped, and incomplete turns stay visible but are
excluded from later context. Switching presets changes the AI for the next request
and replaces the editable message text with that preset’s prompt. Send uses the
textbox exactly once; it does not append a hidden copy of the preset.

Each invocation starts a fresh conversation. Settings changes also start a new
conversation. Conversations and screenshots stay in memory, survive activity
rotation, and are released when the conversation is cleared or the process ends.
There is no persistent chat archive, but outgoing text is retained in local request
logs as described below.

## AI configuration and web search

Provide an HTTPS base URL including any version prefix, without the endpoint:

- **Chat Completions** appends `/chat/completions` and sends `messages`, image content
  parts, and optional `reasoning_effort`.
- **Responses** appends `/responses` and sends `input`, optional `reasoning.effort`,
  and `store: false`.

Web search defaults to **on** in a new configuration. Responses uses
`tools: [{"type": "web_search"}]`; Chat Completions uses `web_search_options: {}`.
Search requires support from the configured provider and model. Disabling the
switch omits the search option; dedicated Chat Completions search models may still
search. Responses may decide whether a search is needed. Search status and clickable
source citations appear when provided by the API.

Provider default reasoning effort omits the parameter. Select a supported effort
or enter a custom value. Images require a vision-capable model. The app neither
guesses protocols nor silently removes unsupported options or retries paid requests.
API errors and incomplete responses are shown explicitly. Test connection sends a
small real request using the configuration being tested.

## Capture and storage

The accessibility service hosts the button and uses `takeScreenshot()` only after
an explicit invocation. A transparent entry activity preserves the underlying app
until capture finishes. Notification captures wait for the covering system window
to disappear. The service reads window types and bounds for that readiness check;
it does not inspect app view hierarchies or perform actions inside other apps.
Protected screens can block or blank screenshots; text-only conversation remains
available. Capture stops safely when access is unavailable or revoked.

Settings and presets use DataStore. All API keys, including preset-specific keys,
are encrypted with AES-GCM using Android Keystore. App files and the log database
are excluded from cloud backup and device transfer. The app does not write
screenshots or prepared images to disk.

**Settings → Request logs** records every dispatched chat request, retry, and
connection test. Logs include sent message text (including conversation context),
general instructions, preset, endpoint, model, protocol, reasoning effort, web
search, image counts, timestamps, first-text latency, total duration, output
character counts, and completion/failure/cancellation status. HTTP failures include
the status code and error type. API keys, authorization headers, image data/URLs,
raw provider errors, and response bodies are excluded. A previous assistant answer
can appear as text in a later request’s conversation context.

Logs persist in a private on-device database until **Clear logs** is used. Tap a
record to inspect or copy its JSON; **Load older** reveals earlier records. Requests
interrupted by process termination are marked **Interrupted** on the next launch.
Storage failures appear in the log viewer without retrying or failing an AI request.

## Build and verify

Use JDK 17 and Android SDK 36, configured through Android Studio, `ANDROID_HOME`, or
an untracked `local.properties` file.

```sh
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
```

For device tests without the Gradle runner's install/uninstall cleanup, install the
APKs and invoke instrumentation directly on a test device:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w app.touchai.android.test/androidx.test.runner.AndroidJUnitRunner
```

Tests cover both API schemas, web-search options, citations, fragmented Unicode SSE,
completion/error handling, cancellation, request context and retry, per-preset AI
selection, encrypted persistence, crop geometry, actual cropped image encoding,
Android Keystore, remembered/editable presets, direct attachment → crop → streaming,
request log sanitization and outcomes, durable log storage, and the log viewer. They use local
mock responses and do not call paid AI endpoints.

Manual device checks cover the floating button, dragging, clean captures from both
entry points, repeat notification invocation, notification-only use, cropping, and
portrait/landscape transitions. Android's legacy `uiautomator dump` can temporarily
suspend accessibility services; avoid using it during capture verification.
Instrumentation can also leave a previously enabled accessibility service awaiting
reconnection; toggle it off and on in Android settings after testing if necessary.

`core/openai` and `core/markdown` were adapted from the Android components in
`ai-using`. The imported toolchain versions are retained; lint may report available
SDK and dependency updates. A specific provider's image, reasoning, and search
capabilities still need to be verified with that provider's configuration.
