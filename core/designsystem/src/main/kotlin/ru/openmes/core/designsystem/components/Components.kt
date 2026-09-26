@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ru.openmes.core.designsystem.components

import androidx.compose.ui.zIndex
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.openmes.core.designsystem.theme.LocalDarkTheme
import ru.openmes.core.designsystem.theme.MarkTone
import ru.openmes.core.designsystem.theme.MarkToneFiveDark
import ru.openmes.core.designsystem.theme.MarkToneFiveLight
import ru.openmes.core.designsystem.theme.MarkToneFourDark
import ru.openmes.core.designsystem.theme.MarkToneFourLight
import ru.openmes.core.designsystem.theme.MarkToneThreeDark
import ru.openmes.core.designsystem.theme.MarkToneThreeLight
import ru.openmes.core.designsystem.theme.MarkToneTwoDark
import ru.openmes.core.designsystem.theme.MarkToneTwoLight

// ---------------------------------------------------------------------------
// Карточки (M3 Expressive: тональные контейнеры + морфинг формы при нажатии)
// ---------------------------------------------------------------------------

/** Скругление карточки в покое и при нажатии (углы «сжимаются» пружиной). */
private val CardCorner = 20.dp
private val CardPressedCorner = 12.dp

/**
 * Форма, морфящая скругление при нажатии (expressive shape morph).
 * [interactionSource] — тот же, что у кликабельной поверхности.
 */
@Composable
fun rememberPressMorphShape(
    interactionSource: MutableInteractionSource,
    corner: Dp = CardCorner,
    pressedCorner: Dp = CardPressedCorner,
): Shape {
    val pressed by interactionSource.collectIsPressedAsState()
    val radius by animateDpAsState(
        targetValue = if (pressed) pressedCorner else corner,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "press_morph",
    )
    return RoundedCornerShape(radius)
}

/**
 * Карточка первого уровня. Тональная заливка surfaceContainer (без тени),
 * скругление 20dp; кликабельная — морфит углы при нажатии.
 */
@Composable
fun MesCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    shape: Shape? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onClick != null) {
        val interactionSource = remember { MutableInteractionSource() }
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape ?: rememberPressMorphShape(interactionSource),
            color = containerColor,
            contentColor = contentColor,
            interactionSource = interactionSource,
        ) {
            Column(Modifier.animateContentSize().padding(contentPadding), content = content)
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape ?: MaterialTheme.shapes.largeIncreased,
            color = containerColor,
            contentColor = contentColor,
        ) {
            Column(Modifier.animateContentSize().padding(contentPadding), content = content)
        }
    }
}

/**
 * Карточка-герой: главный акцент экрана (primaryContainer, скругление 32dp).
 * Для сводок, профиля, студенческого билета.
 */
@Composable
fun HeroCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    MesCard(
        modifier = modifier,
        onClick = onClick,
        containerColor = containerColor,
        contentColor = contentColor,
        shape = if (onClick == null) MaterialTheme.shapes.extraLargeIncreased else null,
        contentPadding = PaddingValues(20.dp),
        content = content,
    )
}

/**
 * Слитная группа (M3 Expressive «segmented list»): внешние углы 20dp,
 * внутренние 4dp, между элементами — зазор 2dp.
 */
@Composable
fun groupShape(
    index: Int,
    size: Int,
    shapes: Shapes = MaterialTheme.shapes,
): Shape {
    val outer = shapes.largeIncreased
    val inner = shapes.extraSmall
    return when {
        size == 1 -> outer
        index == 0 -> inner.copy(topStart = outer.topStart, topEnd = outer.topEnd)
        index == size - 1 -> inner.copy(bottomStart = outer.bottomStart, bottomEnd = outer.bottomEnd)
        else -> inner
    }
}

/** Зазор между элементами слитной группы. */
val GroupGap = 2.dp

/**
 * Строка списка в expressive-стиле: иконка в фигурном контейнере,
 * заголовок/подзаголовок, trailing-слот. Для групп — передайте [shape] из [groupShape].
 */
@Composable
fun MesListItem(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    icon: ImageVector? = null,
    iconShape: Shape = MaterialShapes.Cookie6Sided.toShape(),
    iconContainerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    iconContentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.largeIncreased,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                leadingContent != null -> leadingContent()
                icon != null -> ShapeIcon(
                    icon = icon,
                    shape = iconShape,
                    containerColor = if (enabled) iconContainerColor else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (enabled) iconContentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (supporting != null) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailingContent?.invoke(this)
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = containerColor,
            content = content,
        )
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = containerColor, content = content)
    }
}

/** Иконка в фигурном контейнере (MaterialShapes) — фирменный expressive-акцент. */
@Composable
fun ShapeIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialShapes.Cookie6Sided.toShape(),
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    size: Dp = 40.dp,
    iconSize: Dp = size * 0.5f,
    contentDescription: String? = null,
) {
    Surface(modifier = modifier.size(size), shape = shape, color = containerColor, contentColor = contentColor) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(iconSize))
        }
    }
}

/** Плашка статуса (причина пропуска, тип ДЗ и т. п.). */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    icon: ImageVector? = null,
) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = containerColor, contentColor = contentColor) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ---------------------------------------------------------------------------
// Выбор (M3 Expressive: connected button group вместо SegmentedButton/чипов)
// ---------------------------------------------------------------------------

/**
 * Связанная группа переключателей: выбранный сегмент морфит в «пилюлю».
 * [fill] — растянуть на всю ширину (равные веса), иначе по контенту.
 */
@Composable
fun <T> ConnectedChoiceGroup(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector?)? = null,
    fill: Boolean = true,
) {
    Row(
        modifier = if (fill) modifier.fillMaxWidth() else modifier,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            val checked = option == selected
            ToggleButton(
                checked = checked,
                onCheckedChange = { onSelect(option) },
                // Ширина по длине подписи (+ запас на отступы): при равных весах длинная подпись
                // обрезается на узком экране, хотя соседям место не нужно.
                modifier = (if (fill) Modifier.weight(label(option).length + 2f) else Modifier)
                    .semantics { role = Role.RadioButton },
                // Внешние края — полукруг, внутренние — плоские; форма не меняется при выборе.
                shapes = when {
                    options.size == 1 -> ToggleButtonDefaults.shapes()
                    index == 0 -> fixedToggleShapes(ConnectedLeadingShape)
                    index == options.lastIndex -> fixedToggleShapes(ConnectedTrailingShape)
                    else -> fixedToggleShapes(ConnectedMiddleShape)
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                icon?.invoke(option)?.let {
                    Icon(it, contentDescription = null, modifier = Modifier.size(ToggleButtonDefaults.IconSize))
                    androidx.compose.foundation.layout.Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                }
                Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val ConnectedLeadingShape = RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
private val ConnectedTrailingShape = RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
private val ConnectedMiddleShape = RoundedCornerShape(0.dp)

private fun fixedToggleShapes(shape: Shape) = ToggleButtonShapes(shape = shape, pressedShape = shape, checkedShape = shape)

// ---------------------------------------------------------------------------
// Оценки: тональные пары + форма по значению (MaterialShapes)
// ---------------------------------------------------------------------------

private val MarkTrailingZeros = Regex("""\.0+$""")

/** Значение оценки для сравнения: «5,0» и « 5.00 » → «5». */
private fun normalizeMark(value: String?): String? =
    value?.trim()?.replace(',', '.')?.replace(MarkTrailingZeros, "")

/** Тональные цвета плашки оценки с учётом темы. */
@Composable
fun markTone(value: String?): MarkTone {
    val dark = LocalDarkTheme.current
    return when (normalizeMark(value)) {
        "5", "Зач", "Зачёт", "зачёт", "З", "зачтено" -> if (dark) MarkToneFiveDark else MarkToneFiveLight
        "4" -> if (dark) MarkToneFourDark else MarkToneFourLight
        "3" -> if (dark) MarkToneThreeDark else MarkToneThreeLight
        "2", "1" -> if (dark) MarkToneTwoDark else MarkToneTwoLight
        null, "" -> MarkTone(
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )

        else -> MarkTone(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** Форма плашки по значению: «пятёрка» — печенье, «двойка» — клевер. */
@Composable
fun markShape(value: String?): Shape = when (normalizeMark(value)) {
    "5" -> MaterialShapes.Cookie9Sided.toShape()
    "4" -> MaterialShapes.Cookie6Sided.toShape()
    "3" -> MaterialShapes.Square.toShape()
    "2", "1" -> MaterialShapes.Clover4Leaf.toShape()
    else -> MaterialShapes.Circle.toShape()
}

/**
 * Плашка оценки: форма и тон зависят от значения, вес — маленькая цифра снизу справа.
 */
@Composable
fun MarkBadge(
    value: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    weight: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    val tone = markTone(value)
    val size = if (large) 48.dp else 40.dp
    val shape = markShape(value)
    val content: @Composable () -> Unit = {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Text(
                text = value,
                style = if (large) {
                    MaterialTheme.typography.titleLargeEmphasized
                } else {
                    MaterialTheme.typography.titleMediumEmphasized
                },
                color = tone.content,
                maxLines = 1,
            )
        }
    }
    WithMarkWeight(weight, modifier) { badgeModifier ->
        if (onClick != null) {
            // Без невидимой зоны касания 48 dp: иначе плашка веса ставилась бы от её края, а не от значка.
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Surface(onClick = onClick, modifier = badgeModifier, shape = shape, color = tone.container, content = content)
            }
        } else {
            Surface(modifier = badgeModifier, shape = shape, color = tone.container, content = content)
        }
    }
}

/**
 * Коэффициент оценки — плашкой поверх правого верхнего угла значка.
 * Размер и положение значка не меняются: плашка только накладывается на край,
 * поэтому оценки с весом стоят вровень с обычными. Обрезающим контейнерам (LazyRow) нужен запас по краям.
 */
@Composable
fun WithMarkWeight(
    weight: Int?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    badge: @Composable (Modifier) -> Unit,
) {
    if (weight == null || weight <= 1) {
        badge(modifier)
        return
    }
    // Центр плашки — в одной и той же относительной точке у правого верхнего края значка
    // (10 % размера внутрь от угла), поэтому на оценках любого размера она лежит одинаково.
    Layout(
        // Поверх соседей: выступающую плашку не перекроет следующий значок в ряду.
        modifier = modifier.zIndex(1f),
        content = {
            badge(Modifier)
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
            ) {
                Text(
                    "×$weight",
                    style = if (compact) {
                        MaterialTheme.typography.labelSmallEmphasized.copy(fontSize = 9.sp, lineHeight = 11.sp)
                    } else {
                        MaterialTheme.typography.labelSmallEmphasized
                    },
                    modifier = Modifier.padding(horizontal = if (compact) 3.dp else 5.dp, vertical = if (compact) 0.dp else 1.dp),
                )
            }
        },
    ) { measurables, constraints ->
        val badgePlaceable = measurables[0].measure(constraints)
        val tag = measurables[1].measure(Constraints())
        val inset = (badgePlaceable.width * 0.1f).roundToInt()
        layout(badgePlaceable.width, badgePlaceable.height) {
            badgePlaceable.place(0, 0)
            tag.place(badgePlaceable.width - inset - tag.width / 2, inset - tag.height / 2)
        }
    }
}

// ---------------------------------------------------------------------------
// Состояния экранов (LoadingIndicator, иконки в фигурах MaterialShapes)
// ---------------------------------------------------------------------------

@Composable
fun LoadingState(modifier: Modifier = Modifier, label: String = "Загрузка…") {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LoadingIndicator(Modifier.size(64.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    StateLayout(
        modifier = modifier,
        visual = {
            ShapeIcon(
                icon = icon,
                shape = MaterialShapes.Cookie9Sided.toShape(),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                size = 96.dp,
                iconSize = 40.dp,
            )
        },
        title = title,
        subtitle = subtitle,
    )
}

@Composable
fun ErrorState(
    title: String = "Что-то пошло не так",
    subtitle: String? = "Попробуйте ещё раз",
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    details: String? = null,
) {
    StateLayout(
        modifier = modifier,
        visual = {
            Surface(
                modifier = Modifier.size(96.dp),
                shape = MaterialShapes.SoftBurst.toShape(),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("!", style = MaterialTheme.typography.displaySmallEmphasized)
                }
            }
        },
        title = title,
        subtitle = subtitle,
    ) {
        if (details != null) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        if (onRetry != null) {
            Button(
                onClick = onRetry,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                androidx.compose.foundation.layout.Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Повторить")
            }
        }
    }
}

@Composable
private fun StateLayout(
    modifier: Modifier,
    visual: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        visual()
        Text(
            title,
            style = MaterialTheme.typography.titleLargeEmphasized,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        extra()
    }
}

/**
 * Можно ли сейчас тянуть на обновление. Хост (Scaffold с большим заголовком) даёт `true`
 * только когда заголовок полностью развёрнут: иначе жест вниз сначала раскрывает заголовок.
 * Лямбда — чтобы читать состояние заголовка без рекомпозиции на каждый пиксель скролла.
 */
val LocalPullToRefreshEnabled = staticCompositionLocalOf<() -> Boolean> { { true } }

/** Pull-to-refresh с expressive-индикатором (морфящий LoadingIndicator). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MesPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    val enabledProvider = LocalPullToRefreshEnabled.current
    val enabled by remember(enabledProvider) { derivedStateOf(enabledProvider) }
    // Решение принимается в момент касания: если заголовок был свёрнут, этот жест только
    // раскрывает его, а обновление — следующим свайпом, уже с большим заголовком.
    var gestureAllowed by remember { mutableStateOf(true) }
    Box(
        modifier
            .pointerInput(enabledProvider) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    gestureAllowed = enabledProvider()
                }
            }
            .pullToRefresh(
                isRefreshing = isRefreshing,
                state = state,
                enabled = (enabled && gestureAllowed) || isRefreshing,
                onRefresh = onRefresh,
            ),
    ) {
        content()
        PullToRefreshDefaults.LoadingIndicator(
            state = state,
            isRefreshing = isRefreshing,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/**
 * Прокручиваемая обёртка на весь экран для состояний без списка (пусто/ошибка/загрузка):
 * без прокрутки pull-to-refresh не получает жест.
 */
@Composable
fun ScrollableFill(content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .heightIn(min = maxHeight),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------
// Заголовки
// ---------------------------------------------------------------------------

/** Заголовок секции: emphasized, цвет primary (M3 Expressive list header). */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        trailing?.invoke()
    }
}

/** Крупная цифра-акцент с подписью (сводки: средний балл, пропуски). */
@Composable
fun StatValue(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMediumEmphasized, color = color)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Обрезка по фигуре MaterialShapes (для аватаров). */
@Composable
fun Modifier.clipToMaterialShape(shape: Shape = MaterialShapes.Cookie9Sided.toShape()): Modifier = clip(shape)
