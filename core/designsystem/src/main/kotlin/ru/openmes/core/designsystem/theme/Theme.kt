package ru.openmes.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

/**
 * Тема OpenMES — Material Design 3 Expressive.
 *
 * Отличия от обычной MaterialTheme:
 *  - [MotionScheme.expressive] — «пружинные» анимации (spatial springs);
 *  - expressive-набор форм: крупные скругления, «мягкие» морфинг-шейпы;
 *  - поддержка динамического цвета (Material You, Android 12+) — по настройке.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OpenMESTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) {
                dynamicDarkColorScheme(LocalContext.current)
            } else {
                dynamicLightColorScheme(LocalContext.current)
            }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        // Без «растяжения» списков у края (stretch overscroll Android 12+) — выглядит как подёргивание.
        LocalOverscrollFactory provides null,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            shapes = OpenMESShapes,
            motionScheme = MotionScheme.expressive(),
            typography = OpenMESTypography,
            content = content,
        )
    }
}

/** Тёмная ли сейчас тема (нужно для семантических цветов оценок). */
val LocalDarkTheme = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Локальная настройка брендинга (например, выключение Material You). */
val LocalDynamicColorEnabled = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
fun OpenMESAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = LocalDynamicColorEnabled.current,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDynamicColorEnabled provides dynamicColor) {
        OpenMESTheme(darkTheme = darkTheme, dynamicColor = dynamicColor, content = content)
    }
}
