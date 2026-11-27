package app.touchai.core.openui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt

private data class SelectOption(val value: String, val label: String, val description: String? = null)

private fun parseOptions(raw: JsonArray?): List<SelectOption> =
    raw?.mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> item.contentOrNull?.let { SelectOption(it, it) }
            is JsonObject -> {
                val value = item.stringProp("value") ?: return@mapNotNull null
                val label = item.stringProp("label") ?: value
                SelectOption(value, label, item.stringProp("description"))
            }
            else -> null
        }
    }.orEmpty()

private fun validateRules(value: String, numeric: Boolean, rules: JsonObject?): String? {
    if (rules == null) return null
    val trimmed = value.trim()
    if (rules.boolProp("required") == true && trimmed.isEmpty()) return "Required"
    if (trimmed.isEmpty()) return null
    rules.intProp("minLength")?.let { if (trimmed.length < it) return "At least $it characters" }
    rules.intProp("maxLength")?.let { if (trimmed.length > it) return "At most $it characters" }
    if (rules.boolProp("email") == true && !trimmed.matches(Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$"""))) {
        return "Enter a valid email"
    }
    if (rules.boolProp("url") == true && !trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        return "Enter a valid URL"
    }
    if (numeric || rules.boolProp("numeric") == true) {
        val num = trimmed.toDoubleOrNull() ?: return "Enter a number"
        rules.doubleProp("min")?.let { if (num < it) return "Must be at least $it" }
        rules.doubleProp("max")?.let { if (num > it) return "Must be at most $it" }
    }
    rules.stringProp("pattern")?.let { pat ->
        runCatching { Regex(pat) }.getOrNull()?.let { regex ->
            if (!regex.containsMatchIn(trimmed)) return "Invalid format"
        }
    }
    return null
}

@Composable
internal fun OpenUiForm(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val controller = remember(element.componentId) { OpenUiFormController() }
    val children = element.props.elementListProp("children")
    CompositionLocalProvider(LocalOpenUiForm provides controller) {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            children.forEach { RenderOpenUiElement(it, ctx) }
        }
    }
}

@Composable
internal fun OpenUiButton(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val form = LocalOpenUiForm.current
    val label = element.props.stringProp("label").orEmpty()
    val action = element.props.objectProp("action")
    val variant = element.props.stringProp("variant") ?: "solid"
    val colorKey = element.props.stringProp("color") ?: "primary"
    val size = element.props.stringProp("size") ?: "md"
    val icon = element.props.stringProp("icon")
    val disabled = element.props.boolProp("disabled") == true
    val block = element.props.boolProp("block") == true
    val scheme = MaterialTheme.colorScheme

    val accent = openUiColor(colorKey, scheme) ?: scheme.primary
    val contentPad = when (size) {
        "sm" -> PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        "lg" -> PaddingValues(horizontal = 20.dp, vertical = 12.dp)
        else -> PaddingValues(horizontal = 16.dp, vertical = 9.dp)
    }
    val mod = if (block) modifier.fillMaxWidth() else modifier
    val onClick = { ctx.triggerAction(action, fallbackLabel = label, form = form) }

    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!icon.isNullOrBlank()) {
                OpenUiGlyph(
                    name = icon,
                    sizeDp = if (size == "sm") 14.dp else 16.dp,
                    tint = if (variant == "solid") scheme.onPrimary else accent,
                )
            }
            Text(
                text = label,
                style = if (size == "sm") MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            )
        }
    }

    val buttonShape = RoundedCornerShape(10.dp)
    when (variant) {
        "outline" -> OutlinedButton(
            onClick = onClick,
            enabled = !disabled,
            modifier = mod,
            shape = buttonShape,
            contentPadding = contentPad,
            border = BorderStroke(0.75.dp, scheme.outlineVariant),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (colorKey == "primary") scheme.onSurface else accent),
        ) { content() }
        "soft" -> FilledTonalButton(
            onClick = onClick,
            enabled = !disabled,
            modifier = mod,
            shape = buttonShape,
            contentPadding = contentPad,
        ) { content() }
        "ghost" -> TextButton(
            onClick = onClick,
            enabled = !disabled,
            modifier = mod,
            shape = buttonShape,
            contentPadding = contentPad,
            colors = ButtonDefaults.textButtonColors(contentColor = accent),
        ) { content() }
        else -> Button(
            onClick = onClick,
            enabled = !disabled,
            modifier = mod,
            shape = buttonShape,
            contentPadding = contentPad,
            colors = ButtonDefaults.buttonColors(containerColor = accent),
        ) { content() }
    }
}

@Composable
internal fun OpenUiIconButton(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val form = LocalOpenUiForm.current
    val icon = element.props.stringProp("icon").orEmpty()
    val action = element.props.objectProp("action")
    val variant = element.props.stringProp("variant") ?: "outline"
    val scheme = MaterialTheme.colorScheme

    val bg = when (variant) {
        "solid" -> scheme.primary
        "ghost" -> Color.Transparent
        else -> scheme.surface
    }
    val fg = if (variant == "solid") scheme.onPrimary else scheme.onSurface
    val border = if (variant == "outline") BorderStroke(1.dp, scheme.outlineVariant) else null

    Surface(
        modifier = modifier.size(36.dp),
        shape = CircleShape,
        color = bg,
        border = border,
        onClick = { ctx.triggerAction(action, fallbackLabel = element.props.stringProp("label"), form = form) },
    ) {
        Box(contentAlignment = Alignment.Center) {
            OpenUiGlyph(name = icon, sizeDp = 17.dp, tint = fg)
        }
    }
}

@Composable
internal fun OpenUiPressable(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val form = LocalOpenUiForm.current
    val children = element.props.elementListProp("children")
    val action = element.props.objectProp("action")
    val selected = element.props.boolProp("selected") == true
    val pad = openUiStepDp(element.props.intProp("padding"), 8.dp)
    val radius = openUiRadiusDp(element.props["radius"], 12.dp)
    val scheme = MaterialTheme.colorScheme
    val explicitBg = openUiColor(element.props.stringProp("background"), scheme, asBackground = true)
    val bg = explicitBg ?: if (selected) scheme.primary.copy(alpha = 0.08f) else scheme.surfaceContainerLowest
    val borderColor = if (selected) scheme.primary else scheme.outlineVariant.copy(alpha = 0.5f)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(radius),
        color = bg,
        border = BorderStroke(if (selected) 1.5.dp else 0.75.dp, borderColor),
        onClick = { ctx.triggerAction(action, fallbackLabel = null, form = form) },
    ) {
        Column(modifier = Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            children.forEach { RenderOpenUiElement(it, ctx) }
        }
    }
}

@Composable
internal fun OpenUiInput(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val form = LocalOpenUiForm.current
    val target = element.props.bindingTarget("value")
    val bound = (element.props.boundValue("value", ctx.state) as? JsonPrimitive)?.contentOrNull.orEmpty()
    val label = element.props.stringProp("label")
    val type = element.props.stringProp("type") ?: "text"
    val placeholder = element.props.stringProp("placeholder")
    val rules = element.props.objectProp("rules")
    val fieldKey = target ?: (element.componentId ?: "input_${label.orEmpty()}")

    var text by remember(fieldKey) { mutableStateOf(bound) }
    LaunchedEffect(bound) {
        if (bound != text && !(type == "number" && bound.toDoubleOrNull() == text.toDoubleOrNull())) {
            text = bound
        }
    }

    DisposableEffect(form, fieldKey, text, rules) {
        form?.register(fieldKey) { validateRules(text, numeric = type == "number", rules = rules) }
        onDispose { form?.unregister(fieldKey) }
    }
    val err = form?.errors?.get(fieldKey)

    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            text = next
            form?.errors?.remove(fieldKey)
            if (target != null) {
                val jsonVal = if (type == "number") {
                    val trimmed = next.trim()
                    if (trimmed.isEmpty()) JsonNull else trimmed.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(next)
                } else {
                    JsonPrimitive(next)
                }
                ctx.onSetState(target, jsonVal)
            }
        },
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        isError = err != null,
        supportingText = err?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (type == "number") KeyboardType.Decimal else KeyboardType.Text,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
internal fun OpenUiTextArea(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val form = LocalOpenUiForm.current
    val target = element.props.bindingTarget("value")
    val bound = (element.props.boundValue("value", ctx.state) as? JsonPrimitive)?.contentOrNull.orEmpty()
    val label = element.props.stringProp("label")
    val placeholder = element.props.stringProp("placeholder")
    val rows = (element.props.intProp("rows") ?: 3).coerceIn(2, 12)
    val rules = element.props.objectProp("rules")
    val fieldKey = target ?: (element.componentId ?: "textarea_${label.orEmpty()}")

    var text by remember(fieldKey) { mutableStateOf(bound) }
    LaunchedEffect(bound) { if (bound != text) text = bound }
    DisposableEffect(form, fieldKey, text, rules) {
        form?.register(fieldKey) { validateRules(text, numeric = false, rules = rules) }
        onDispose { form?.unregister(fieldKey) }
    }
    val err = form?.errors?.get(fieldKey)

    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            text = next
            form?.errors?.remove(fieldKey)
            if (target != null) ctx.onSetState(target, JsonPrimitive(next))
        },
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        minLines = rows,
        isError = err != null,
        supportingText = err?.let { { Text(it) } },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
internal fun OpenUiSelect(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val form = LocalOpenUiForm.current
    val target = element.props.bindingTarget("value")
    val current = (element.props.boundValue("value", ctx.state) as? JsonPrimitive)?.contentOrNull.orEmpty()
    val options = remember(element.props["options"]) { parseOptions(element.props.arrayProp("options")) }
    val label = element.props.stringProp("label")
    val placeholder = element.props.stringProp("placeholder") ?: "Select…"
    val rules = element.props.objectProp("rules")
    val fieldKey = target ?: (element.componentId ?: "select_${label.orEmpty()}")
    var expanded by remember { mutableStateOf(false) }

    DisposableEffect(form, fieldKey, current, rules) {
        form?.register(fieldKey) { validateRules(current, numeric = false, rules = rules) }
        onDispose { form?.unregister(fieldKey) }
    }
    val err = form?.errors?.get(fieldKey)
    val currentLabel = options.firstOrNull { it.value == current }?.label ?: current.ifBlank { placeholder }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!label.isNullOrBlank()) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { expanded = true },
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, if (err != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = currentLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (current.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            expanded = false
                            form?.errors?.remove(fieldKey)
                            if (target != null) ctx.onSetState(target, JsonPrimitive(option.value))
                        },
                    )
                }
            }
        }
        if (err != null) {
            Text(err, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OpenUiSegmentedControl(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val target = element.props.bindingTarget("value")
    val current = (element.props.boundValue("value", ctx.state) as? JsonPrimitive)?.contentOrNull
    val options = remember(element.props["options"]) { parseOptions(element.props.arrayProp("options")) }
    if (options.isEmpty()) return

    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { idx, option ->
            SegmentedButton(
                selected = option.value == current,
                onClick = {
                    if (target != null) ctx.onSetState(target, JsonPrimitive(option.value))
                },
                shape = SegmentedButtonDefaults.itemShape(index = idx, count = options.size),
                label = { Text(option.label, maxLines = 1) },
            )
        }
    }
}

@Composable
internal fun OpenUiSlider(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val target = element.props.bindingTarget("value")
    val minVal = (element.props.doubleProp("min") ?: 0.0).toFloat()
    val maxVal = (element.props.doubleProp("max") ?: 100.0).toFloat().coerceAtLeast(minVal + 0.001f)
    val step = element.props.doubleProp("step")?.toFloat()?.takeIf { it > 0f }
    val label = element.props.stringProp("label")
    val unit = element.props.stringProp("unit").orEmpty()
    val bound = (element.props.boundValue("value", ctx.state) as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: minVal

    var localValue by remember(target) { mutableStateOf(bound.coerceIn(minVal, maxVal)) }
    LaunchedEffect(bound) { localValue = bound.coerceIn(minVal, maxVal) }

    val formattedVal = remember(localValue, unit) {
        val numStr = if (localValue % 1f == 0f) localValue.roundToInt().toString() else "%.1f".format(localValue)
        if (unit == "$") "$$numStr" else "$numStr$unit"
    }
    val stepsCount = if (step != null && step > 0f) {
        (((maxVal - minVal) / step).roundToInt() - 1).coerceIn(0, 200)
    } else 0

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (!label.isNullOrBlank()) {
                Text(label, style = MaterialTheme.typography.labelLarge)
            } else {
                Spacer(Modifier.width(1.dp))
            }
            Text(formattedVal, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = localValue,
            onValueChange = { raw ->
                val snapped = if (step != null) {
                    minVal + ((raw - minVal) / step).roundToInt() * step
                } else raw
                val clamped = snapped.coerceIn(minVal, maxVal)
                localValue = clamped
                if (target != null) {
                    val number = if (clamped % 1f == 0f) JsonPrimitive(clamped.roundToInt()) else JsonPrimitive(clamped.toDouble())
                    ctx.onSetState(target, number)
                }
            },
            valueRange = minVal..maxVal,
            steps = stepsCount,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun OpenUiCheckbox(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val form = LocalOpenUiForm.current
    val target = element.props.bindingTarget("checked")
    val checked = (element.props.boundValue("checked", ctx.state) as? JsonPrimitive)?.booleanOrNull == true
    val label = element.props.stringProp("label").orEmpty()
    val lineThrough = element.props.boolProp("lineThrough") == true
    val rules = element.props.objectProp("rules")
    val fieldKey = target ?: (element.componentId ?: "cb_$label")

    DisposableEffect(form, fieldKey, checked, rules) {
        form?.register(fieldKey) { if (rules?.boolProp("required") == true && !checked) "Required" else null }
        onDispose { form?.unregister(fieldKey) }
    }
    val err = form?.errors?.get(fieldKey)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                form?.errors?.remove(fieldKey)
                if (target != null) ctx.onSetState(target, JsonPrimitive(!checked))
            }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { next ->
                    form?.errors?.remove(fieldKey)
                    if (target != null) ctx.onSetState(target, JsonPrimitive(next))
                },
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = if (lineThrough && checked) TextDecoration.LineThrough else TextDecoration.None,
                color = if (lineThrough && checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (err != null) {
            Text(err, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OpenUiRadioGroup(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val form = LocalOpenUiForm.current
    val target = element.props.bindingTarget("value")
    val current = (element.props.boundValue("value", ctx.state) as? JsonPrimitive)?.contentOrNull.orEmpty()
    val options = remember(element.props["options"]) { parseOptions(element.props.arrayProp("options")) }
    val label = element.props.stringProp("label")
    val variant = element.props.stringProp("variant") ?: "list"
    val rules = element.props.objectProp("rules")
    val fieldKey = target ?: (element.componentId ?: "radio_${label.orEmpty()}")

    DisposableEffect(form, fieldKey, current, rules) {
        form?.register(fieldKey) { validateRules(current, numeric = false, rules = rules) }
        onDispose { form?.unregister(fieldKey) }
    }
    val err = form?.errors?.get(fieldKey)
    val scheme = MaterialTheme.colorScheme

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!label.isNullOrBlank()) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        }
        when (variant) {
            "pills" -> FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                options.forEach { option ->
                    FilterChip(
                        selected = option.value == current,
                        onClick = {
                            form?.errors?.remove(fieldKey)
                            if (target != null) ctx.onSetState(target, JsonPrimitive(option.value))
                        },
                        label = { Text(option.label) },
                    )
                }
            }
            "cards" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    val sel = option.value == current
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = if (sel) scheme.primaryContainer.copy(alpha = 0.4f) else scheme.surface,
                        border = BorderStroke(if (sel) 1.5.dp else 1.dp, if (sel) scheme.primary else scheme.outlineVariant),
                        onClick = {
                            form?.errors?.remove(fieldKey)
                            if (target != null) ctx.onSetState(target, JsonPrimitive(option.value))
                        },
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            RadioButton(selected = sel, onClick = null)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(option.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                if (!option.description.isNullOrBlank()) {
                                    Text(option.description, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                options.forEach { option ->
                    val sel = option.value == current
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                            form?.errors?.remove(fieldKey)
                            if (target != null) ctx.onSetState(target, JsonPrimitive(option.value))
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = sel, onClick = {
                            form?.errors?.remove(fieldKey)
                            if (target != null) ctx.onSetState(target, JsonPrimitive(option.value))
                        })
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (err != null) {
            Text(err, style = MaterialTheme.typography.labelSmall, color = scheme.error)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OpenUiDatePicker(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val form = LocalOpenUiForm.current
    val target = element.props.bindingTarget("value")
    val current = (element.props.boundValue("value", ctx.state) as? JsonPrimitive)?.contentOrNull.orEmpty()
    val label = element.props.stringProp("label") ?: "Date"
    val rules = element.props.objectProp("rules")
    val fieldKey = target ?: (element.componentId ?: "date_$label")
    var showDialog by remember { mutableStateOf(false) }

    DisposableEffect(form, fieldKey, current, rules) {
        form?.register(fieldKey) { validateRules(current, numeric = false, rules = rules) }
        onDispose { form?.unregister(fieldKey) }
    }
    val err = form?.errors?.get(fieldKey)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { showDialog = true },
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, if (err != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(current.ifBlank { "YYYY-MM-DD" }, style = MaterialTheme.typography.bodyMedium)
                OpenUiGlyph("calendar", sizeDp = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (err != null) {
            Text(err, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }

    if (showDialog) {
        val initialMillis = remember(current) {
            runCatching { LocalDate.parse(current).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
        }
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    pickerState.selectedDateMillis?.let { millis ->
                        val formatted = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                        form?.errors?.remove(fieldKey)
                        if (target != null) ctx.onSetState(target, JsonPrimitive(formatted))
                    }
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
internal fun OpenUiTimer(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val target = element.props.bindingTarget("seconds")
    val boundSec = (element.props.boundValue("seconds", ctx.state) as? JsonPrimitive)?.doubleOrNull?.roundToInt() ?: 0
    val label = element.props.stringProp("label")
    val initialSec = remember(target) { boundSec }
    var running by remember(target) { mutableStateOf(false) }

    LaunchedEffect(running, boundSec) {
        if (running && boundSec > 0) {
            delay(1000)
            val next = (boundSec - 1).coerceAtLeast(0)
            if (next == 0) running = false
            if (target != null) ctx.onSetState(target, JsonPrimitive(next))
        } else if (boundSec <= 0) {
            running = false
        }
    }

    val mins = boundSec / 60
    val secs = boundSec % 60
    val formatted = "%02d:%02d".format(mins, secs)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                if (!label.isNullOrBlank()) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(formatted, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { if (boundSec > 0) running = !running }) {
                    Text(if (running) "Pause" else "Start")
                }
                OutlinedButton(onClick = {
                    running = false
                    if (target != null) ctx.onSetState(target, JsonPrimitive(initialSec))
                }) {
                    Text("Reset")
                }
            }
        }
    }
}
