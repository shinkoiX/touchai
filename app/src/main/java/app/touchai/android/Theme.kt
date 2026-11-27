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

// Airy baby-blue & warm porcelain light theme + starry sky-blue dark theme.
private val LightColors = lightColorScheme(
    primary = Color(0xFF3F7FE8), onPrimary = Color.White,
    primaryContainer = Color(0xFFEEF5FF), onPrimaryContainer = Color(0xFF24305E),
    secondary = Color(0xFF79B4F7), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDBE8FD), onSecondaryContainer = Color(0xFF1F57C3),
    tertiary = Color(0xFFC43A68), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFF1F5), onTertiaryContainer = Color(0xFFC43A68),
    background = Color(0xFFFFFFFF), onBackground = Color(0xFF24305E),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF24305E),
    surfaceVariant = Color(0xFFF7F8FC), onSurfaceVariant = Color(0xFF57628C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8FC),
    surfaceContainer = Color(0xFFEEF5FF),
    surfaceContainerHigh = Color(0xFFE3E8F5),
    surfaceContainerHighest = Color(0xFFDBE8FD),
    outline = Color(0xFF8A93B5), outlineVariant = Color(0xFFEEF0F7),
    error = Color(0xFFC43A68), onError = Color.White,
    errorContainer = Color(0xFFFFF1F5), onErrorContainer = Color(0xFFC43A68),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7BB2F4), onPrimary = Color(0xFF171528),
    primaryContainer = Color(0xFF2A3351), onPrimaryContainer = Color(0xFFE6E6EF),
    secondary = Color(0xFFA8CDFA), onSecondary = Color(0xFF171528),
    secondaryContainer = Color(0xFF384971), onSecondaryContainer = Color(0xFFE6E6EF),
    tertiary = Color(0xFFF797B4), onTertiary = Color(0xFF171528),
    tertiaryContainer = Color(0xFF4E2C35), onTertiaryContainer = Color(0xFFF797B4),
    background = Color(0xFF1A1924), onBackground = Color(0xFFE6E6EF),
    surface = Color(0xFF1A1924), onSurface = Color(0xFFE6E6EF),
    surfaceVariant = Color(0xFF2C2B39), onSurfaceVariant = Color(0xFFAEAEBC),
    surfaceContainerLowest = Color(0xFF16151E),
    surfaceContainerLow = Color(0xFF24222F),
    surfaceContainer = Color(0xFF2C2B39),
    surfaceContainerHigh = Color(0xFF32313F),
    surfaceContainerHighest = Color(0xFF384971),
    outline = Color(0xFF4C4A5E), outlineVariant = Color(0xFF32303D),
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
fun AppTopBar(title: String, navigation: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = if (navigation == null) 20.dp else 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        navigation?.invoke()
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(start = if (navigation == null) 0.dp else 4.dp), maxLines = 1)
        actions()
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Medium,
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
    Surface(modifier.fillMaxWidth(), color = if (error) colors.errorContainer else colors.primaryContainer,
        contentColor = if (error) colors.onErrorContainer else colors.onPrimaryContainer, shape = MaterialTheme.shapes.medium) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}
