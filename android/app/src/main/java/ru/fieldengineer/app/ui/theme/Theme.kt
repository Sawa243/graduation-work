package ru.fieldengineer.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = lightColorScheme(
    primary = Color(0xFF1F4E79),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E3F0),
    onPrimaryContainer = Color(0xFF0B2239),
    secondary = Color(0xFF4A6072),
    secondaryContainer = Color(0xFFD5E3F0),
    onSecondaryContainer = Color(0xFF0B2239),
    background = Color(0xFFF6F8FA),
    surface = Color(0xFFF6F8FA),
    // оттенки карточек и панелей — нейтрально-серые вместо фиолетовых по умолчанию
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFEFF2F5),
    surfaceContainerHigh = Color(0xFFE9EDF1),
    surfaceContainerHighest = Color(0xFFE6EBF0),
    error = Color(0xFFB3261E),
)

/** Светлая тема приложения; скриншоты для ВКР снимаются в ней. */
@Composable
fun FieldEngineerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
