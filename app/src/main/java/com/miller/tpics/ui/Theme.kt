package com.miller.tpics.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* Identidad nueva: tinta + papel cálido, con coral como acento y turquesa de apoyo.
 * Las superficies son neutras para que las fotos sean lo que resalta. */

val Coral = Color(0xFFFF7A59)
val CoralDeep = Color(0xFFD9502F)
val Teal = Color(0xFF2DD4BF)
val TealDeep = Color(0xFF0F8F80)
val Ink = Color(0xFF0F1216)

private val Dark = darkColorScheme(
    primary = Coral,
    onPrimary = Color(0xFF3A1206),
    primaryContainer = Color(0xFF5A2413),
    onPrimaryContainer = Color(0xFFFFDBD0),
    secondary = Teal,
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF123D38),
    onSecondaryContainer = Color(0xFFB4F1E8),
    tertiary = Color(0xFFF2C14E),
    background = Ink,
    onBackground = Color(0xFFECE8E1),
    surface = Ink,
    onSurface = Color(0xFFECE8E1),
    surfaceVariant = Color(0xFF1F252D),
    onSurfaceVariant = Color(0xFFA8AFB9),
    surfaceContainerLowest = Color(0xFF0B0E11),
    surfaceContainerLow = Color(0xFF14181D),
    surfaceContainer = Color(0xFF181D23),
    surfaceContainerHigh = Color(0xFF1F252D),
    surfaceContainerHighest = Color(0xFF283039),
    outline = Color(0xFF4A535F),
    outlineVariant = Color(0xFF2A323B),
    error = Color(0xFFFF8A80),
)

private val Light = lightColorScheme(
    primary = CoralDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBD0),
    onPrimaryContainer = Color(0xFF3A1206),
    secondary = TealDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDF3EC),
    onSecondaryContainer = Color(0xFF05302B),
    tertiary = Color(0xFF8A6500),
    background = Color(0xFFFAF7F2),
    onBackground = Color(0xFF1A1D21),
    surface = Color(0xFFFAF7F2),
    onSurface = Color(0xFF1A1D21),
    surfaceVariant = Color(0xFFEDE8E0),
    onSurfaceVariant = Color(0xFF5B616A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F1EA),
    surfaceContainer = Color(0xFFF0EBE3),
    surfaceContainerHigh = Color(0xFFEAE5DC),
    surfaceContainerHighest = Color(0xFFE3DDD3),
    outline = Color(0xFF8C9199),
    outlineVariant = Color(0xFFD8D2C7),
    error = Color(0xFFBA1A1A),
)

/** Colores de etiquetas (mismo índice en todos los dispositivos). */
val TagColors = listOf(
    Color(0xFFFF7A59), Color(0xFF2DD4BF), Color(0xFFF2C14E), Color(0xFF7AA2F7), Color(0xFFE879A6),
    Color(0xFF9CCC65), Color(0xFFB794F6), Color(0xFF4FC3F7), Color(0xFFFFA94D), Color(0xFF90A4AE),
)

fun tagColor(index: Int): Color = TagColors[((index % TagColors.size) + TagColors.size) % TagColors.size]

private val Type = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun isDark(mode: Int): Boolean = when (mode) {
    1 -> false
    2 -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun TPicsTheme(mode: Int, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDark(mode)) Dark else Light,
        typography = Type,
        shapes = AppShapes,
        content = content,
    )
}
