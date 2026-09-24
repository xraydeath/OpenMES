package ru.openmes.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ru.openmes.core.designsystem.R

/**
 * Inter — тот же шрифт, что использует оригинальное приложение «Дневник МЭШ».
 * Bundled как вариативный TTF (оси opsz + wght): один файл покрывает все веса,
 * поэтому ось wght задаём явно — иначе все начертания рендерятся как Regular.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun inter(weight: FontWeight) = Font(
    R.font.inter_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Inter = FontFamily(
    inter(FontWeight.Normal),
    inter(FontWeight.Medium),
    inter(FontWeight.SemiBold),
    inter(FontWeight.Bold),
    inter(FontWeight.ExtraBold),
)

private fun mes(
    size: Int,
    line: Int,
    weight: FontWeight,
    tracking: Float = 0f,
): TextStyle = TextStyle(
    fontFamily = Inter,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

/**
 * Type Scale M3 Expressive: базовая шкала + emphasized-варианты
 * (на ступень тяжелее) для заголовков экранов, акцентов и ключевых цифр.
 */
val OpenMESTypography = Typography(
    displayLarge = mes(57, 64, FontWeight.Normal, -0.25f),
    displayMedium = mes(45, 52, FontWeight.Normal),
    displaySmall = mes(36, 44, FontWeight.Normal),
    headlineLarge = mes(32, 40, FontWeight.Normal),
    headlineMedium = mes(28, 36, FontWeight.Normal),
    headlineSmall = mes(24, 32, FontWeight.Normal),
    titleLarge = mes(22, 28, FontWeight.Normal),
    titleMedium = mes(16, 24, FontWeight.Medium, 0.15f),
    titleSmall = mes(14, 20, FontWeight.Medium, 0.1f),
    bodyLarge = mes(16, 24, FontWeight.Normal, 0.5f),
    bodyMedium = mes(14, 20, FontWeight.Normal, 0.25f),
    bodySmall = mes(12, 16, FontWeight.Normal, 0.4f),
    labelLarge = mes(14, 20, FontWeight.Medium, 0.1f),
    labelMedium = mes(12, 16, FontWeight.Medium, 0.5f),
    labelSmall = mes(11, 16, FontWeight.Medium, 0.5f),
    displayLargeEmphasized = mes(57, 64, FontWeight.SemiBold, -0.25f),
    displayMediumEmphasized = mes(45, 52, FontWeight.SemiBold),
    displaySmallEmphasized = mes(36, 44, FontWeight.SemiBold),
    headlineLargeEmphasized = mes(32, 40, FontWeight.SemiBold),
    headlineMediumEmphasized = mes(28, 36, FontWeight.SemiBold),
    headlineSmallEmphasized = mes(24, 32, FontWeight.SemiBold),
    titleLargeEmphasized = mes(22, 28, FontWeight.SemiBold),
    titleMediumEmphasized = mes(16, 24, FontWeight.Bold, 0.15f),
    titleSmallEmphasized = mes(14, 20, FontWeight.Bold, 0.1f),
    bodyLargeEmphasized = mes(16, 24, FontWeight.Medium, 0.5f),
    bodyMediumEmphasized = mes(14, 20, FontWeight.Medium, 0.25f),
    bodySmallEmphasized = mes(12, 16, FontWeight.Medium, 0.4f),
    labelLargeEmphasized = mes(14, 20, FontWeight.Bold, 0.1f),
    labelMediumEmphasized = mes(12, 16, FontWeight.Bold, 0.5f),
    labelSmallEmphasized = mes(11, 16, FontWeight.Bold, 0.5f),
)
