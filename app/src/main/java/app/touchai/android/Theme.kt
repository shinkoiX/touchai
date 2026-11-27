package app.touchai.android

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Airy baby-blue & pure white light theme + starry sky-blue dark theme with crisp readable ink.
private val LightColors = lightColorScheme(
    primary = Color(0xFF3F7FE8), onPrimary = Color.White,
    primaryContainer = Color(0xFFEEF5FF), onPrimaryContainer = Color(0xFF172038),
    secondary = Color(0xFF79B4F7), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDBE8FD), onSecondaryContainer = Color(0xFF1F57C3),
    tertiary = Color(0xFFC43A68), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFF1F5), onTertiaryContainer = Color(0xFFC43A68),
    background = Color(0xFFFFFFFF), onBackground = Color(0xFF172038),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF172038),
    surfaceVariant = Color(0xFFF7F8FC), onSurfaceVariant = Color(0xFF424E6E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8FC),
    surfaceContainer = Color(0xFFEEF5FF),
    surfaceContainerHigh = Color(0xFFE3E8F5),
    surfaceContainerHighest = Color(0xFFDBE8FD),
    outline = Color(0xFF7581A3), outlineVariant = Color(0xFFE8ECF5),
    error = Color(0xFFC43A68), onError = Color.White,
    errorContainer = Color(0xFFFFF1F5), onErrorContainer = Color(0xFFC43A68),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7BB2F4), onPrimary = Color(0xFF171528),
    primaryContainer = Color(0xFF2A3351), onPrimaryContainer = Color(0xFFF2F4FB),
    secondary = Color(0xFFA8CDFA), onSecondary = Color(0xFF171528),
    secondaryContainer = Color(0xFF384971), onSecondaryContainer = Color(0xFFF2F4FB),
    tertiary = Color(0xFFF797B4), onTertiary = Color(0xFF171528),
    tertiaryContainer = Color(0xFF4E2C35), onTertiaryContainer = Color(0xFFF797B4),
    background = Color(0xFF1A1924), onBackground = Color(0xFFF2F4FB),
    surface = Color(0xFF1A1924), onSurface = Color(0xFFF2F4FB),
    surfaceVariant = Color(0xFF2C2B39), onSurfaceVariant = Color(0xFFC5CAD9),
    surfaceContainerLowest = Color(0xFF16151E),
    surfaceContainerLow = Color(0xFF24222F),
    surfaceContainer = Color(0xFF2C2B39),
    surfaceContainerHigh = Color(0xFF32313F),
    surfaceContainerHighest = Color(0xFF384971),
    outline = Color(0xFF5D5B72), outlineVariant = Color(0xFF353344),
    error = Color(0xFFEA7B85), onError = Color(0xFF171528),
    errorContainer = Color(0xFF49272A), onErrorContainer = Color(0xFFEA7B85),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun TouchAiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, shapes = AppShapes, content = content)
}

@Composable
fun AppIcon(@DrawableRes id: Int, description: String?, modifier: Modifier = Modifier, size: Dp = 24.dp, tint: Color = LocalContentColor.current) {
    Icon(painterResource(id), description, modifier.size(size), tint = tint)
}

/** Icon-only action; the description doubles as the accessibility label. */
@Composable
fun AppIconButton(@DrawableRes id: Int, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled) { AppIcon(id, description) }
}

@Composable
fun AppDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 0.75.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
fun AppTopBar(title: String, navigation: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .padding(start = if (navigation == null) 20.dp else 6.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navigation?.invoke()
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(start = if (navigation == null) 0.dp else 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            actions()
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(bottom = 8.dp),
    )
}

@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), content = content)
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
fun MessageBanner(text: String, error: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier.fillMaxWidth(),
        color = if (error) colors.errorContainer else colors.primaryContainer,
        contentColor = if (error) colors.onErrorContainer else colors.onPrimaryContainer,
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp))
    }
}

/** Borderless filled tonal text input sitting directly on the page background (single surface layer). */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val scheme = MaterialTheme.colorScheme
    val container = scheme.surfaceContainerLow
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { text -> { Text(text, color = scheme.onSurfaceVariant.copy(alpha = 0.8f)) } },
        supportingText = supportingText?.let { text -> { Text(text) } },
        trailingIcon = trailingIcon,
        singleLine = singleLine,
        minLines = minLines,
        readOnly = readOnly,
        enabled = enabled,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            disabledContainerColor = container.copy(alpha = 0.6f),
            errorContainerColor = scheme.errorContainer.copy(alpha = 0.45f),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
        ),
        modifier = modifier,
    )
}

/** Borderless tonal action button replacing Material OutlinedButton. */
@Composable
fun AppTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.primary,
    shape: RoundedCornerShape = RoundedCornerShape(14.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = if (enabled) containerColor else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

/** Borderless tonal dropdown trigger row sitting directly on the page background. */
@Composable
fun AppPickerButton(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerLow,
        contentColor = scheme.onSurface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            AppIcon(R.drawable.ic_expand, null, size = 18.dp, tint = scheme.onSurfaceVariant)
        }
    }
}

/** Borderless segmented selector pill replacing Material SingleChoiceSegmentedButtonRow. */
@Composable
fun <T> AppSegmentBar(
    options: List<T>,
    selected: (T) -> Boolean,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable (T) -> Unit)? = null,
    onSelect: (T) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { option ->
                val isSelected = selected(option)
                val bg = if (isSelected) scheme.primaryContainer else Color.Transparent
                val fg = when {
                    !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.45f)
                    isSelected -> scheme.primary
                    else -> scheme.onSurface
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(11.dp))
                        .background(bg)
                        .selectable(
                            selected = isSelected,
                            enabled = enabled,
                            role = Role.Tab,
                            onClick = { onSelect(option) },
                        )
                        .padding(horizontal = 8.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (icon != null) {
                        CompositionLocalProvider(LocalContentColor provides fg) {
                            icon(option)
                        }
                    }
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Borderless capsule chip replacing Material FilterChip. */
@Composable
fun AppPillChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accentTertiary: Boolean = false,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        selected && accentTertiary -> scheme.tertiaryContainer
        selected -> scheme.primary
        else -> scheme.surfaceContainerLow
    }
    val fg = when {
        !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.45f)
        selected && accentTertiary -> scheme.onTertiaryContainer
        selected -> scheme.onPrimary
        else -> scheme.onSurface
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = bg,
        contentColor = fg,
        modifier = modifier.semantics { this.selected = selected },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            trailingIcon?.invoke()
        }
    }
}
