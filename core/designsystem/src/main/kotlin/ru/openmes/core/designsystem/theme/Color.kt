package ru.openmes.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Брендовая палитра OpenMES.
 * Первичный цвет — фирменный синий МЭШ (тон 40), тонированные контейнеры
 * в духе Material Design 3 Expressive.
 */

// ---------- Light ----------
val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF00174B),
    inversePrimary = Color(0xFFAEC6FF),
    secondary = Color(0xFF565E71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDBE2F9),
    onSecondaryContainer = Color(0xFF131B2C),
    tertiary = Color(0xFF6D5D2F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF7E1A7),
    onTertiaryContainer = Color(0xFF231A00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF9FF),
    onBackground = Color(0xFF1A1B22),
    surface = Color(0xFFFBF9FF),
    onSurface = Color(0xFF1A1B22),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    surfaceDim = Color(0xFFDBD9E0),
    surfaceBright = Color(0xFFFBF9FF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F3FB),
    surfaceContainer = Color(0xFFEFEDF5),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE3E1EA),
    inverseSurface = Color(0xFF2F3037),
    inverseOnSurface = Color(0xFFF1F0F7),
    outline = Color(0xFF757780),
    outlineVariant = Color(0xFFC1C6D0),
    scrim = Color(0xFF000000),
)

// ---------- Dark ----------
val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFA7C8FF),
    onPrimary = Color(0xFF002D6B),
    primaryContainer = Color(0xFF00419A),
    onPrimaryContainer = Color(0xFFD9E2FF),
    inversePrimary = Color(0xFF0B57D0),
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283041),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDBE2F9),
    tertiary = Color(0xFFD8C44D),
    onTertiary = Color(0xFF383000),
    tertiaryContainer = Color(0xFF524600),
    onTertiaryContainer = Color(0xFFF5E264),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE3E1E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE3E1E9),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    surfaceDim = Color(0xFF121318),
    surfaceBright = Color(0xFF38393F),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF1A1B22),
    surfaceContainer = Color(0xFF1E1F27),
    surfaceContainerHigh = Color(0xFF282A31),
    surfaceContainerHighest = Color(0xFF33353C),
    inverseSurface = Color(0xFFE3E1E9),
    inverseOnSurface = Color(0xFF2F3037),
    outline = Color(0xFF8F909A),
    outlineVariant = Color(0xFF44474F),
    scrim = Color(0xFF000000),
)

// ---------- Семантические цвета оценок ----------
// Тональные пары container/onContainer (тоны 90/10 и 30/90) — читаемы и
// в светлой, и в тёмной теме, в духе M3-контейнеров вместо «кричащих» заливок.

/** Цвета плашки оценки: контейнер и контент. */
data class MarkTone(val container: Color, val content: Color)

val MarkToneFiveLight = MarkTone(Color(0xFFC4EFC0), Color(0xFF002106))
val MarkToneFourLight = MarkTone(Color(0xFFD6E3FF), Color(0xFF001B3E))
val MarkToneThreeLight = MarkTone(Color(0xFFFFDDB3), Color(0xFF291800))
val MarkToneTwoLight = MarkTone(Color(0xFFFFDAD6), Color(0xFF410002))

val MarkToneFiveDark = MarkTone(Color(0xFF1E5220), Color(0xFFC4EFC0))
val MarkToneFourDark = MarkTone(Color(0xFF1B4787), Color(0xFFD6E3FF))
val MarkToneThreeDark = MarkTone(Color(0xFF653E00), Color(0xFFFFDDB3))
val MarkToneTwoDark = MarkTone(Color(0xFF93000A), Color(0xFFFFDAD6))
