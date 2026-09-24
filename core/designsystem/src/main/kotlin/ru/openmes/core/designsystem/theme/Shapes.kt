package ru.openmes.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shape Scale M3 Expressive (m3.material.io/styles/shape/corner-radius-scale).
 *
 * Иерархия применения:
 *  - extraSmall (4dp):            внутренние углы слитных групп, теги
 *  - small (8dp):                 плашки, поля ввода
 *  - medium (12dp):               чипы, компактные карточки, нажатое состояние карточек
 *  - large (16dp):                вложенные контейнеры
 *  - largeIncreased (20dp):       карточки и группы списков (по умолчанию)
 *  - extraLarge (28dp):           bottom sheets, диалоги
 *  - extraLargeIncreased (32dp):  карточки-герои
 *  - extraExtraLarge (48dp):      крупные декоративные поверхности
 */
val OpenMESShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
    largeIncreased = RoundedCornerShape(20.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(48.dp),
)
