package ru.openmes.core.designsystem.components

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.openmes.core.designsystem.R
import ru.openmes.core.designsystem.theme.Spacing
import kotlin.math.roundToInt

/** Всё, что может стоять в пилюле дока. */
interface MesDockDestination {
    val icon: ImageVector

    @get:StringRes
    val label: Int

    /**
     * Есть непрочитанное — док рисует точку на иконке.
     *
     * Точка только у невыбранного пункта: у выбранного и так видно, что он открыт, а
     * поверхность иконки перекрывала бы подпись, которая разворачивается рядом.
     */
    val unread: Boolean get() = false
}

private val DockHeight = 64.dp
private val DockVerticalPadding = 12.dp
private val DockItemHeight = 48.dp
private val DockIconSize = 24.dp

/** Точка непрочитанного на иконке дока. */
private val DockUnreadDotSize = 8.dp
private val DockUnreadDotOffset = 2.dp

/** Тень дока: мягкая — контент под доком сам уходит вниз по градиенту. */
private val DockShadowElevation = 3.dp
private const val DOCK_SHADOW_AMBIENT = 0.06f
private const val DOCK_SHADOW_SPOT = 0.12f

/**
 * Высота, которую экраны обязаны оставить снизу под док: сама пилюля плюс поля вокруг неё.
 * Списки верхнего уровня прибавляют [mesDockReservedHeight] к своему нижнему отступу — тогда
 * контент уезжает под док, а не упирается в него.
 *
 * Сама по себе пилюля; системную навигацию добавляет [mesDockReservedHeight], потому что
 * док накладывается и на неё тоже.
 */
val MesDockReservedHeight: Dp = DockHeight + DockVerticalPadding * 2

/**
 * Резерв снизу под док вместе с системной навигацией — сколько экрану надо оставить пустым,
 * чтобы контент уезжал под обоими, а не упирался в них.
 *
 * Отдельная функция, а не `val`, потому что инсет навигации известен только в композиции:
 * на жестовой навигации он один, на трёхкнопочной другой.
 */
@Composable
fun mesDockReservedHeight(): Dp =
    MesDockReservedHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * Сколько снизу остаётся до верхней кромки пилюли дока — то есть до самой верхней строки,
 * которую док ещё не закрывает. Контент должен растворяться в фон именно тут: стык списка
 * с пилюлей обязан быть чистым фоном, иначе строки просвечивали бы сквозь край дока.
 *
 * На [MesDockReservedHeight] больше на [DockVerticalPadding]: списки оставляют снизу с запасом,
 * и последний элемент встаёт над доком с полем, а не впритык.
 */
val MesDockTopInset: Dp = DockHeight + DockVerticalPadding

/**
 * Плавающий док: отдельная круглая пилюля, висящая поверх контента. Выбранный пункт
 * разворачивает подпись и встаёт на тональный индикатор, остальные — просто иконки.
 *
 * Перенесено из ADHDium (https://git.tetopie.lol/miho/ADHDium), `ui/main/FloatingBottomBar.kt`.
 * Отличия от оригинала:
 *  - без второй пилюли с морфом «табы ↔ кнопка назад ↔ категории настроек»: в OpenMES док —
 *    единственная точка входа верхнего уровня, и разворачивать ему не во что;
 *  - без ступени тона по признаку «под доком контент»: в ADHDium док над пустым фоном
 *    главного экрана должен сливаться с ним, здесь же док всегда висит над списком;
 *  - без стекла: матовое стекло там рисуется перерисовкой записанного слоя под собой
 *    (`GraphicsLayer.record`), а этот API стал публичным только в Compose 1.12 — в версии
 *    этого проекта он ещё внутренний. Так что док здесь такой же, какой у ADHDium выходит
 *    по умолчанию: `navigationBarBlur = false`, непрозрачная подложка.
 */
@Composable
fun <T : MesDockDestination> MesDock(
    destinations: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = Spacing.l, vertical = DockVerticalPadding)
            // Весь след дока, включая поля вокруг пилюли, принадлежит доку: нажатие там
            // съедается здесь, а не проваливается в страницу под ним. Сама пилюля свои касания
            // выигрывает — дети видят события первыми, так что это забирает только пустые
            // участки вокруг неё.
            .pointerInput(Unit) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MesDockSurface {
            PillItems(destinations, selected, onSelect)
        }
    }
}

/**
 * Пилюля дока: `surfaceContainer` по гайдлайнам навигации плюс мягкая тень — док висит над
 * прокручиваемым контентом, поэтому отрываться от фона ему нужно.
 */
@Composable
private fun MesDockSurface(content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = colors.surfaceContainer,
        contentColor = colors.onSurface,
        modifier = Modifier.shadow(
            elevation = DockShadowElevation,
            shape = CircleShape,
            ambientColor = Color.Black.copy(alpha = DOCK_SHADOW_AMBIENT),
            spotColor = Color.Black.copy(alpha = DOCK_SHADOW_SPOT),
            clip = false,
        ),
    ) {
        content()
    }
}

@Composable
private fun <T : MesDockDestination> PillItems(
    items: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .padding((DockHeight - DockItemHeight) / 2)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            PillItem(
                icon = item.icon,
                label = stringResource(item.label),
                unreadLabel = if (item.unread) stringResource(R.string.dock_unread_cd) else null,
                selected = item == selected,
                onClick = { onSelect(item) },
            )
        }
    }
}

/** Выбран: иконка с подписью на тональном индикаторе. Остальные: просто иконка. */
@Composable
private fun PillItem(
    icon: ImageVector,
    label: String,
    unreadLabel: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    // Индикатор гаснет вместе с выбором. Под невыбранными иконками не рисуется ничего.
    val selection = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = motion.defaultEffectsSpec(),
        label = "dock item selection",
    )
    val content by animateColorAsState(
        targetValue = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        animationSpec = motion.defaultEffectsSpec(),
        label = "dock item content",
    )
    // Подпись разворачивается вместе с выбором и складывается без него. Её окно именно
    // обрезается, а не перемеряется: анимируемая ширина заставляла бы перемерять текст
    // каждый кадр — с переносами, с пересчётом эллипсиса, с миганием в момент схлопывания
    // прошлой подписи. Измеренная один раз в полном размере, она просто уезжает за край.
    val labelReveal = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = motion.defaultSpatialSpec(),
        label = "dock label reveal",
    )
    Row(
        modifier = Modifier
            .height(DockItemHeight)
            .clip(CircleShape)
            .drawBehind {
                val alpha = selection.value.coerceIn(0f, 1f)
                if (alpha > 0f) drawRect(colors.secondaryContainer.copy(alpha = alpha))
            }
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = (DockItemHeight - DockIconSize) / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(DockIconSize)) {
            Icon(
                imageVector = icon,
                // Когда пункт выбран, его уже описывает видимая подпись.
                contentDescription = when {
                    selected -> null
                    unreadLabel != null -> unreadLabel
                    else -> label
                },
                tint = content,
                modifier = Modifier.size(DockIconSize),
            )
            if (unreadLabel != null && !selected) {
                // Точка на углу иконки. Тот же приём, что был у колокольчика в шапке:
                // бейдж из BadgedBox целится в угол ограничивающего квадрата и на
                // скруглённой иконке уезжает за её край.
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = DockUnreadDotOffset, y = -DockUnreadDotOffset)
                        .size(DockUnreadDotSize)
                        .background(colors.error, CircleShape),
                )
            }
        }
        Box(
            modifier = Modifier
                .graphicsLayer { alpha = labelReveal.value.coerceIn(0f, 1f) }
                .clipToBounds()
                .layout { measurable, _ ->
                    val placeable = measurable.measure(Constraints())
                    // Пружина проезжает мимо нуля на выходе; без ограничения окно отдало бы
                    // отрицательную ширину на кадр и уронило бы измерение.
                    val width = (placeable.width * labelReveal.value)
                        .roundToInt()
                        .coerceIn(0, placeable.width)
                    layout(width, placeable.height) { placeable.place(0, 0) }
                },
        ) {
            Text(
                text = label,
                color = content,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.padding(start = Spacing.s, end = Spacing.xs),
            )
        }
    }
}