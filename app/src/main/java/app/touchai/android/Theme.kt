package app.touchai.android

import androidx.annotation.DrawableRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5), onPrimary = Color.White,
    primaryContainer = Color(0xFFE2DFFF), onPrimaryContainer = Color(0xFF14006B),
    secondary = Color(0xFF5D5C72), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E1F9), onSecondaryContainer = Color(0xFF1A1A2C),
    tertiary = Color(0xFF7A4F8A), tertiaryContainer = Color(0xFFFBD7FF), onTertiaryContainer = Color(0xFF2F0A3F),
    background = Color(0xFFFCF8FF), onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFCF8FF), onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE4E1EC), onSurfaceVariant = Color(0xFF47464F),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F2FC),
    surfaceContainer = Color(0xFFF0ECF6), surfaceContainerHigh = Color(0xFFEAE6F0),
    surfaceContainerHighest = Color(0xFFE4E1EA),
    outline = Color(0xFF787680), outlineVariant = Color(0xFFC8C5D0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC3C0FF), onPrimary = Color(0xFF2400A6),
    primaryContainer = Color(0xFF3B30C4), onPrimaryContainer = Color(0xFFE2DFFF),
    secondary = Color(0xFFC6C4DD), onSecondary = Color(0xFF2F2F42),
    secondaryContainer = Color(0xFF45455A), onSecondaryContainer = Color(0xFFE4E1F9),
    tertiary = Color(0xFFEBB5F9), tertiaryContainer = Color(0xFF603770), onTertiaryContainer = Color(0xFFFBD7FF),
    background = Color(0xFF131318), onBackground = Color(0xFFE5E1E9),
    surface = Color(0xFF131318), onSurface = Color(0xFFE5E1E9),
    surfaceVariant = Color(0xFF47464F), onSurfaceVariant = Color(0xFFC8C5D0),
    surfaceContainerLowest = Color(0xFF0E0E13), surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25), surfaceContainerHigh = Color(0xFF2A292F),
    surfaceContainerHighest = Color(0xFF35343A),
    outline = Color(0xFF918F9A), outlineVariant = Color(0xFF47464F),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
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
fun AppTopBar(title: String, navigation: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = if (navigation == null) 20.dp else 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        navigation?.invoke()
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(start = if (navigation == null) 0.dp else 4.dp), maxLines = 1)
        actions()
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp))
}

@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(vertical = 8.dp), content = content)
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
fun MessageBanner(text: String, error: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), color = if (error) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (error) colors.onErrorContainer else colors.onSecondaryContainer, shape = MaterialTheme.shapes.medium) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}
