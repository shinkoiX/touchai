package app.touchai.core.openui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class OpenUiFormController {
    private val validators = linkedMapOf<String, () -> String?>()
    val errors = mutableStateMapOf<String, String?>()

    fun register(key: String, validate: () -> String?) {
        validators[key] = validate
    }

    fun unregister(key: String) {
        validators.remove(key)
        errors.remove(key)
    }

    fun validateAll(): Boolean {
        var allValid = true
        for ((key, validator) in validators) {
            val err = validator()
            errors[key] = err
            if (err != null) allValid = false
        }
        return allValid
    }
}

internal val LocalOpenUiForm = compositionLocalOf<OpenUiFormController?> { null }

internal class OpenUiRenderContext(
    val state: JsonObject,
    val isStreaming: Boolean,
    val onSetState: (varName: String, value: JsonElement) -> Unit,
    val onDispatchAction: (action: JsonObject) -> Unit,
    val onOpenUrl: (url: String) -> Unit,
    val onToAssistant: (message: String, context: JsonElement?) -> Unit,
)

/**
 * During streaming, openui-lang statements are line-oriented; slicing to the last newline avoids
 * feeding half-typed strings/SVG/expressions that cause the AST to collapse or jitter mid-line.
 */
internal fun stableOpenUiProgram(program: String, isStreaming: Boolean): String {
    if (!isStreaming) return program
    val lastNewline = program.lastIndexOf('\n')
    if (lastNewline <= 0) return ""
    return program.substring(0, lastNewline)
}

@Composable
fun OpenUiMessage(
    sessionId: String,
    program: String,
    isStreaming: Boolean,
    engine: OpenUiEngine,
    onOpenUrl: (String) -> Unit,
    onToAssistant: (message: String, context: JsonElement?) -> Unit,
    modifier: Modifier = Modifier,
    fallbackContent: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var renderState by remember(sessionId) { mutableStateOf<OpenUiRenderState?>(null) }
    val latestProgram by rememberUpdatedState(program)
    val latestStreaming by rememberUpdatedState(isStreaming)

    fun handleEffects(result: OpenUiRenderState) {
        for (effect in result.effects) {
            when (effect) {
                is OpenUiEffect.OpenUrl -> onOpenUrl(effect.url)
                is OpenUiEffect.ToAssistant -> onToAssistant(effect.message, effect.context)
            }
        }
    }

    // Single conflated collector per session: never cancels mid-delay on rapid token bursts,
    // only parses complete lines while streaming, and never replaces a valid rendered tree with null.
    LaunchedEffect(sessionId) {
        var lastEvaluatedText: String? = null
        var lastEvaluatedStreaming: Boolean? = null
        snapshotFlow { latestProgram to latestStreaming }
            .conflate()
            .collect { (raw, streaming) ->
                val candidate = stableOpenUiProgram(raw, streaming)
                if (candidate.isBlank()) {
                    if (!streaming) {
                        renderState = OpenUiRenderState(root = null)
                    }
                    return@collect
                }
                if (candidate == lastEvaluatedText && streaming == lastEvaluatedStreaming) {
                    return@collect
                }
                val next = engine.pushText(sessionId, candidate)
                lastEvaluatedText = candidate
                lastEvaluatedStreaming = streaming
                val prevChildren = renderState?.root?.props?.elementListProp("children").orEmpty().size
                val nextChildren = next.root?.props?.elementListProp("children").orEmpty().size
                val keepPreviousDuringStream = streaming && prevChildren > 0 && (next.root == null || nextChildren < prevChildren)
                if (!keepPreviousDuringStream) {
                    renderState = next
                }
                if (streaming) {
                    // Pace streaming UI updates smoothly so reading stays calm and steady.
                    delay(100)
                }
            }
    }

    DisposableEffect(sessionId) {
        onDispose {
            scope.launch { engine.disposeSession(sessionId) }
        }
    }

    val current = renderState
    val root = current?.root
    val rootChildrenCount = root?.props?.elementListProp("children").orEmpty().size

    if (root == null || (isStreaming && root.typeName == "Col" && rootChildrenCount == 0)) {
        if (isStreaming) {
            Box(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        } else {
            fallbackContent()
        }
        return
    }

    val ctx = remember(current.state, isStreaming, sessionId) {
        OpenUiRenderContext(
            state = current.state,
            isStreaming = isStreaming,
            onSetState = { varName, value ->
                scope.launch {
                    val next = engine.setState(sessionId, varName, value)
                    renderState = next
                    handleEffects(next)
                }
            },
            onDispatchAction = { action ->
                scope.launch {
                    val next = engine.dispatchAction(sessionId, action)
                    renderState = next
                    handleEffects(next)
                }
            },
            onOpenUrl = onOpenUrl,
            onToAssistant = onToAssistant,
        )
    }

    Box(modifier = modifier.fillMaxWidth()) {
        RenderOpenUiElement(
            element = root,
            ctx = ctx,
            isRoot = true,
        )
    }
}

internal fun OpenUiRenderContext.triggerAction(
    action: JsonObject?,
    fallbackLabel: String?,
    form: OpenUiFormController?,
) {
    val hasToAssistant = action?.arrayProp("steps")?.any {
        (it as? JsonObject)?.stringProp("type") == "continue_conversation"
    } == true || (action == null && !fallbackLabel.isNullOrBlank())

    if (hasToAssistant && form != null && !form.validateAll()) {
        return
    }

    if (action != null) {
        if (hasToAssistant && form != null && state.isNotEmpty()) {
            // Attach current form/page $state snapshot to @ToAssistant context when not already set.
            val enrichedSteps = action.arrayProp("steps")?.map { stepEl ->
                val step = stepEl as? JsonObject ?: return@map stepEl
                if (step.stringProp("type") == "continue_conversation" && step["context"] == null) {
                    buildJsonObject {
                        step.forEach { (k, v) -> put(k, v) }
                        put("context", state)
                    }
                } else step
            }
            if (enrichedSteps != null) {
                onDispatchAction(buildJsonObject {
                    put("steps", kotlinx.serialization.json.JsonArray(enrichedSteps))
                })
                return
            }
        }
        onDispatchAction(action)
    } else if (!fallbackLabel.isNullOrBlank()) {
        onToAssistant(fallbackLabel, state.takeIf { it.isNotEmpty() })
    }
}

@Composable
internal fun RenderOpenUiElement(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
    isRoot: Boolean = false,
) {
    when (element.typeName) {
        // Layout
        "Col" -> OpenUiCol(element, ctx, modifier, isRoot)
        "Row" -> OpenUiRow(element, ctx, modifier)
        "Box" -> OpenUiBox(element, ctx, modifier)
        "Grid" -> OpenUiGrid(element, ctx, modifier)
        "Card" -> OpenUiCard(element, ctx, modifier)
        "Carousel" -> OpenUiCarousel(element, ctx, modifier)
        "Popover" -> OpenUiPopover(element, ctx, modifier)
        "Divider" -> OpenUiDivider(modifier)
        "Spacer" -> OpenUiSpacer(modifier)

        // Text
        "Title" -> OpenUiTitle(element, modifier)
        "Text" -> OpenUiText(element, ctx, modifier)
        "Caption" -> OpenUiCaption(element, modifier)
        "Label" -> OpenUiLabel(element, modifier)
        "Badge" -> OpenUiBadge(element, modifier)
        "Link" -> OpenUiLink(element, ctx, modifier)
        "List" -> OpenUiList(element, ctx, modifier)
        "ListItem" -> OpenUiListItemStandalone(element, ctx, modifier)
        "Markdown" -> OpenUiMarkdown(element, ctx, modifier)
        "Quote" -> OpenUiQuote(element, modifier)
        "Code" -> OpenUiCode(element, modifier)
        "Math" -> OpenUiMath(element, modifier)

        // Inputs
        "Button" -> OpenUiButton(element, ctx, modifier)
        "IconButton" -> OpenUiIconButton(element, ctx, modifier)
        "Pressable" -> OpenUiPressable(element, ctx, modifier)
        "Input" -> OpenUiInput(element, ctx, modifier)
        "TextArea" -> OpenUiTextArea(element, ctx, modifier)
        "Select" -> OpenUiSelect(element, ctx, modifier)
        "SegmentedControl" -> OpenUiSegmentedControl(element, ctx, modifier)
        "Slider" -> OpenUiSlider(element, ctx, modifier)
        "Checkbox" -> OpenUiCheckbox(element, ctx, modifier)
        "RadioGroup" -> OpenUiRadioGroup(element, ctx, modifier)
        "DatePicker" -> OpenUiDatePicker(element, ctx, modifier)
        "Timer" -> OpenUiTimer(element, ctx, modifier)
        "Form" -> OpenUiForm(element, ctx, modifier)

        // Media & Data
        "Image" -> OpenUiImage(element, modifier)
        "Icon" -> OpenUiIcon(element, modifier)
        "Table" -> OpenUiTable(element, ctx, modifier)
        "Chart" -> OpenUiChart(element, modifier)
        "YouTube" -> OpenUiYouTube(element, ctx, modifier)

        // Play
        "Flashcard" -> OpenUiFlashcard(element, ctx, modifier)
        "Celebration" -> OpenUiCelebration(element, modifier)

        // Drawing & Web
        "Svg" -> OpenUiSvg(element, modifier)
        "HtmlView" -> OpenUiHtmlView(element, modifier)

        else -> {
            val children = element.props.elementListProp("children")
            if (children.isNotEmpty()) {
                OpenUiCol(element, ctx, modifier, isRoot = false)
            } else {
                val text = element.props.stringProp("text") ?: element.props.stringProp("label")
                if (!text.isNullOrBlank()) {
                    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
                }
            }
        }
    }
}
