package com.roco.catcher.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.roco.catcher.R

private val LightColors = lightColorScheme(
    primary = Color(0xFFFFC65F),
    onPrimary = Color(0xFF141414),
    primaryContainer = Color(0xFFFFD88B),
    onPrimaryContainer = Color(0xFF141414),
    secondary = Color(0xFFF4F0E8),
    onSecondary = Color(0xFF141414),
    secondaryContainer = Color(0xFFF4F0E8),
    onSecondaryContainer = Color(0xFF141414),
    tertiary = Color(0xFF2F9E66),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDF7E8),
    onTertiaryContainer = Color(0xFF073B22),
    background = Color(0xFFFFC65F),
    surface = Color(0xFFFFFAF2),
    surfaceVariant = Color(0xFFF4F0E8),
    onSurface = Color(0xFF141414),
    onSurfaceVariant = Color(0xFF6F665C),
    outline = Color(0xFFD7CABB),
    outlineVariant = Color(0xFFE7DCCF),
    error = Color(0xFFB83A32),
    onError = Color.White,
)

private val RocoKingdomSans = FontFamily(Font(R.font.roco_kingdom_sans))
private val DefaultTypography = Typography()

private fun TextStyle.withRocoFont(): TextStyle = copy(fontFamily = RocoKingdomSans)

private val AppTypography = Typography(
    displayLarge = DefaultTypography.displayLarge.withRocoFont(),
    displayMedium = DefaultTypography.displayMedium.withRocoFont(),
    displaySmall = DefaultTypography.displaySmall.withRocoFont(),
    headlineLarge = DefaultTypography.headlineLarge.withRocoFont(),
    headlineMedium = DefaultTypography.headlineMedium.withRocoFont(),
    headlineSmall = DefaultTypography.headlineSmall.withRocoFont(),
    titleLarge = DefaultTypography.titleLarge.withRocoFont(),
    titleMedium = DefaultTypography.titleMedium.withRocoFont(),
    titleSmall = DefaultTypography.titleSmall.withRocoFont(),
    bodyLarge = DefaultTypography.bodyLarge.withRocoFont(),
    bodyMedium = DefaultTypography.bodyMedium.withRocoFont(),
    bodySmall = DefaultTypography.bodySmall.withRocoFont(),
    labelLarge = DefaultTypography.labelLarge.withRocoFont(),
    labelMedium = DefaultTypography.labelMedium.withRocoFont(),
    labelSmall = DefaultTypography.labelSmall.withRocoFont(),
)

@Composable
fun RocoCatcherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = AppTypography,
        content = content,
    )
}

