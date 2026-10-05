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
   setting. Use the up/down arrows beside each preset to reorder them, then Save.
   This order is also used by the chat's preset chips. Your last preset choice is
   remembered automatically.

Tap the floating button or the notification's **Ask AI** action. TouchAI captures
once, then opens the send panel with the full screenshot attached by default.
Turn off **Settings → Quick access → Attach screenshots automatically** to start
with the screenshot preview unselected. Its top-right **+** adds the image; the
highlighted **×** excludes it without hiding the preview or resetting its crop.
The button and preview border are highlighted only while the image is attached.
This toggle also works for gallery images. An unselected image is never included
in a request. Tap the preview to crop it. The crop selection
starts at the full image. Your last selected preset fills the message box; edit
that text and press **Send**. No image is uploaded before Send. Selecting **No
preset** is also remembered.

Drag the floating button to move it; it snaps to an edge and remembers its position.
Adjust **Settings → Quick access → Button size** from 32–96 dp, then Save.
Drag the quick chat panel's handle upward to continue the same conversation in the
main app, including its draft, attachment, and running response. Drag downward to
collapse the panel temporarily. A small restore handle stays above other apps;
tap it or swipe it upward to restore the same chat without taking another screenshot.
While collapsed, you can tap, scroll, and use the underlying apps normally. The
draft, attachment, and running response stay alive. Opening TouchAI also resumes
the collapsed chat. Starting a new capture opens a new chat; an existing request
continues in the background and remains available in history.
The notification works with the floating button disabled. Android may allow ongoing
notifications to be dismissed; reopening TouchAI restores an enabled notification.

## Images and conversations

Responses image-generation results and Chat Completions `delta.images` data URLs
appear directly in the assistant's message, including image-only replies.
Tap a generated image to open its larger preview.
Use the download button on a sent/generated picture or in its viewer to save it to
**Pictures/TouchAI** in the gallery, preserving its original encoded bytes and format.
Generated images are retained in chat history and history exports, and are included
as image context for follow-up messages. PNG, JPEG, and WebP outputs are supported.

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
conversation. Sent conversations are saved locally, including their attached images,
answers, citations, and AI configuration. Use **Chat history** in the chat toolbar to
reopen and continue a conversation or delete it. **New chat** keeps previous chats in
history. Images appear inside sent messages; tap one to open a larger preview.
Unsent drafts and attachments remain in memory only.

In **Chat history → ⋮**, use **Export history** to save all chats and attached images
as a JSON file through Android's file picker. **Import history** reads that file and
adds independent copies without replacing existing chats. API keys are excluded.
Retrying an imported turn uses a key from current settings only when the endpoint
and API protocol match; configure that endpoint first if no matching key is available.

History is saved when a request starts, at recovery checkpoints, periodically while
receiving output, and when it completes or stops. Retry uses the turn's saved AI
configuration; new follow-ups use the currently selected preset and current settings.

## Background requests and recovery

Requests run independently of chat windows. Closing a window, returning Home,
collapsing the panel, opening another chat, or starting another capture does not
cancel an active request. A foreground-service notification keeps the work visible;
tap it to reopen the request, or use **Stop** in the chat or notification to cancel.
Active chats cannot be deleted before their requests finish or are stopped.

For providers that support Responses background jobs, enable **Recover interrupted
responses** in that AI configuration. This sends `background: true` and `store: true`,
saves the response ID, retrieves the same response after a disconnection, and resumes
pending responses when the app reopens or its service restarts. Stop calls the
provider's cancellation endpoint; if offline, the stop request is saved and retried
until acknowledged. A stop arriving after completion preserves the completed answer.
Portable history imports do not resume or cancel remote jobs.

Recovery is off by default because some compatible providers reject `background`.
Without that API support, the foreground service can keep the existing connection
alive, but it cannot recover output after a broken connection or process death.
An interruption before receiving a response ID cannot be recovered safely either.
These cases are marked **Interrupted**, not **Stopped**, and are never automatically
resubmitted as new generations. Retry resumes a known recoverable ID; otherwise it
starts a new generation only when explicitly pressed.

Android can still force-stop the app or limit foreground-service time. Stored
server-side responses can be recovered after reopening, subject to the provider's
retention period. See the [Responses background API](https://developers.openai.com/api/docs/guides/background)
and [Android foreground-service limits](https://developer.android.com/develop/background-work/services/fgs/timeout).

## AI configuration and web search

Provide an HTTPS base URL including any version prefix, without the endpoint:

- **Chat Completions** appends `/chat/completions` and sends `messages`, image content
  parts, and optional `reasoning_effort`.
- **Responses** appends `/responses` and sends `input`, optional `reasoning.effort`,
  and `store: false` by default. Recovery mode enables background execution and storage.

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
until capture finishes. Notification captures request shade dismissal on Android 12
and later, then wait for covering system windows to stay absent while the closing
animation and blur settle. The delay defaults to 200 ms and can be adjusted from
0–1000 ms in **Settings → Quick access → Notification capture delay**.
The service reads window types and bounds for that readiness check;
it does not inspect app view hierarchies or perform actions inside other apps.
Protected screens can block or blank screenshots; text-only conversation remains
available. Capture stops safely when access is unavailable or revoked.

Settings and presets use DataStore. All API keys, including preset-specific keys,
are encrypted with AES-GCM using Android Keystore. App files and the log database
are excluded from cloud backup and device transfer. Sent images are stored with
their chats in private app files; deleting a chat removes its saved images and
messages. API keys in saved chat configurations are encrypted with the same Keystore
protection. Unsent screenshots and prepared images are not written to disk.

**Settings → Request logs** records every dispatched chat request, retry, and
connection test. Logs include sent message text (including conversation context),
general instructions, preset, endpoint, model, protocol, reasoning effort, web
search, image counts, timestamps, first-text latency, total duration, output
character counts, and completion/failure/cancellation status. HTTP failures include
the status code and error type. Detailed logs now retain full answer text, generated
images, received response-event payloads, the final response object, and partial
output when a request is stopped or fails. API keys and credential fields are
redacted; outgoing image payloads and request headers are not logged.

Log summaries persist in a private on-device database, with full output in separate
private files so large generated images do not exceed database cursor limits.
Tap a record to inspect its summary or use **Export log** for the complete JSON,
including image data. The displayed/copyable summary shortens large values; the
stored output and export retain them in full. Older logs have no retroactive output.
**Clear logs** removes both summaries and output files. **Load older** reveals earlier records. Requests
interrupted by process termination are marked **Interrupted** on the next launch.
Storage failures appear in the log viewer without retrying or failing an AI request.

## Build and verify

Use JDK 17 and Android SDK 36, configured through Android Studio, `ANDROID_HOME`, or
an untracked `local.properties` file.

```sh
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
```

## GitHub Actions and release signing

The Android workflow runs unit tests, lint, and debug builds on pushes and pull
requests. Pushes to the default branch, `v*` tags, and manual runs also produce a
signed release APK after the checks pass. A `v*` tag also publishes a GitHub Release
with generated notes and the signed `touchai-<tag>.apk` attached. Branch builds stay
available in the run's artifacts.
Debug builds use the red icon, the **TouchAI Debug** label, and package
`app.touchai.android.debug`. Release builds retain the purple icon and package
`app.touchai.android`. Both can be installed together and have separate settings,
history, and permissions.

Release signing uses these repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64`: the Base64-encoded release keystore.
- `ANDROID_KEYSTORE_PASSWORD`: the keystore password.
- `ANDROID_KEY_ALIAS`: the signing key alias.
- `ANDROID_KEY_PASSWORD`: the signing key password.

The signing key is restored only for the release job, never for pull-request checks.
Signing builds disable the Gradle configuration cache, and the temporary keystore
is removed after the job. Keep the ignored `.signing` directory backed up securely;
its key is required to sign future updates.

To build a signed release locally from the repository root:

```sh
. .signing/release.env
./gradlew --no-configuration-cache :app:assembleRelease
```

## Device checks

For device tests without the Gradle runner's install/uninstall cleanup, install the
APKs and invoke instrumentation directly on a test device:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w app.touchai.android.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Tests cover both API schemas, web-search options, citations, fragmented Unicode SSE,
completion/error handling, cancellation, request context and retry, per-preset AI
selection, encrypted persistence, crop geometry, actual cropped image encoding,
Android Keystore, remembered/editable presets, direct attachment → crop → streaming,
request log sanitization and outcomes, durable log storage, the log viewer, chat history
round-trips and deletion, portable history export/import, continued image context,
and sent-image previews. They use local
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
