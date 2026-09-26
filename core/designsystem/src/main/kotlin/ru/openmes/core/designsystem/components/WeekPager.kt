package ru.openmes.core.designsystem.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.snapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.openmes.core.common.toRuDate
import ru.openmes.core.common.toShortRu
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

// ---------------------------------------------------------------------------
// Пейджер дней с полосой недели (расписание, домашка)
// ---------------------------------------------------------------------------

/**
 * Окно дней для пейджера и его синхронизация с выбранной датой: свайп выбирает день,
 * выбор дня (в [WeekBar], «Сегодня») докручивает пейджер.
 */
@Stable
class DayPager internal constructor(
    /** Понедельник первой недели окна. */
    val firstMonday: LocalDate,
    val days: List<LocalDate>,
    val pagerState: PagerState,
    private val selected: State<LocalDate>,
) {
    /** Идёт программный переход к выбранной дате: промежуточные остановки пейджера — не выбор пользователя. */
    internal var syncing by mutableStateOf(false)

    private val highlighted = derivedStateOf {
        if (!syncing && pagerState.isScrollInProgress) days.getOrNull(pagerState.targetPage) ?: selected.value else selected.value
    }

    /**
     * День для подсветки в [WeekBar]: пока пользователь листает дни — тот, к которому едет пейджер,
     * чтобы подсветка не ждала окончания анимации свайпа.
     */
    val highlightedDate: LocalDate get() = highlighted.value
}

/**
 * [selectedDate] — выбранная дата из ViewModel, [onSelect] — выбор пейджером.
 * Окно: ±[WEEKS_AROUND] недель от недели открытия экрана. Якорь не следует за «сегодня»:
 * сдвиг окна после полуночи перенёс бы страницы пейджера под другие даты.
 */
@Composable
fun rememberDayPager(selectedDate: LocalDate, onSelect: (LocalDate) -> Unit): DayPager {
    val anchor = remember { LocalDate.now() }
    val firstMonday = remember(anchor) {
        anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(WEEKS_AROUND.toLong())
    }
    val days = remember(firstMonday) { (0 until WEEK_COUNT * 7).map { firstMonday.plusDays(it.toLong()) } }
    // Выбранная дата переживает пересоздание Activity (поворот, смена темы) — открываемся на ней.
    val pagerState = rememberPagerState(
        initialPage = days.indexOf(selectedDate).takeIf { it >= 0 } ?: days.indexOf(anchor),
    ) { days.size }
    val selectedState = rememberUpdatedState(selectedDate)
    val selected by selectedState
    val select by rememberUpdatedState(onSelect)
    val pager = remember(pagerState) { DayPager(firstMonday, days, pagerState, selectedState) }

    // Дата, выбранная самим пейджером: к ней не нужно ехать программно.
    val pagerDate = remember { mutableStateOf<LocalDate?>(null) }

    // Пейджер остановился → выбрать дату.
    LaunchedEffect(pagerState.settledPage) {
        val page = pagerState.settledPage
        if (!pager.syncing && page in days.indices) {
            pagerDate.value = days[page]
            select(days[page])
        }
    }
    // Выбор даты (день в WeekBar, «К сегодня») → проскроллить пейджер.
    // collectLatest: новая дата отменяет недоехавший переход и сразу едет дальше с текущего места —
    // без проверок «идёт ли прокрутка», из-за которых выбор терялся и страница застревала между днями.
    LaunchedEffect(pagerState) {
        snapshotFlow { selected }.collectLatest { date ->
            // Выбор пришёл от свайпа: страница уже там, а если палец успел начать следующий свайп —
            // «доводка» к этой дате отменяла бы его (быстрые свайпы подряд откатывались назад).
            if (date == pagerDate.value) return@collectLatest
            pagerDate.value = null
            val index = days.indexOf(date)
            if (index < 0 || (pagerState.currentPage == index && pagerState.currentPageOffsetFraction == 0f)) return@collectLatest
            pager.syncing = true
            try {
                // Всегда один плавный сдвиг: далёкую дату ставим соседней и доезжаем одной страницей.
                if (abs(pagerState.currentPage - index) > 1) {
                    pagerState.scrollToPage(if (index > pagerState.currentPage) index - 1 else index + 1)
                }
                pagerState.animateScrollToPage(index, animationSpec = PageSlideSpec)
            } catch (e: CancellationException) {
                // Переход перехватил палец пользователя — дальше страницу выберет settledPage.
                if (!currentCoroutineContext().isActive) throw e
            } finally {
                pager.syncing = false
            }
        }
    }
    return pager
}

/** Плавный сдвиг страницы без пружинного «отскока». */
val PageSlideSpec = tween<Float>(durationMillis = 450, easing = FastOutSlowInEasing)

// Доля ширины страницы, после которой свайп листает дальше, даже если палец остановился перед
// отпусканием (у PagerDefaults — 0.5, в коротком свайпе столько не набирается).
private const val PageSwipeThreshold = 0.12f

// Скорость, с которой отпущенный палец считается свайпом на соседнюю страницу. У PagerDefaults она
// зашита в 400 dp/с: короткий свайп не дотягивал и страница возвращалась назад.
private val PageFlingVelocity = 80.dp

/**
 * Листание ровно на одну страницу. Решает направление: страница, сдвинутая хоть на [PageSwipeThreshold],
 * уезжает дальше, если её не бросили обратно; без сдвига достаточно быстрого короткого свайпа.
 */
@Composable
fun rememberShortSwipeFling(state: PagerState): TargetedFlingBehavior {
    val minVelocity = with(LocalDensity.current) { PageFlingVelocity.toPx() }
    val decay = rememberSplineBasedDecay<Float>()
    return remember(state, minVelocity, decay) {
        val provider = object : SnapLayoutInfoProvider {
            override fun calculateApproachOffset(velocity: Float, decayOffset: Float) = 0f

            override fun calculateSnapOffset(velocity: Float): Float {
                val pageSize = state.layoutInfo.pageSize + state.layoutInfo.pageSpacing
                if (pageSize == 0) return 0f
                val position = state.currentPage + state.currentPageOffsetFraction
                val from = state.settledPage
                val dragged = position - from
                val target = when {
                    // Бросок обратно отменяет перелистывание.
                    dragged > 0 && velocity < -minVelocity -> from
                    dragged < 0 && velocity > minVelocity -> from
                    dragged > PageSwipeThreshold -> from + 1
                    dragged < -PageSwipeThreshold -> from - 1
                    velocity > minVelocity -> from + 1
                    velocity < -minVelocity -> from - 1
                    else -> from
                }.coerceIn(from - 1, from + 1).coerceIn(0, state.pageCount - 1)
                return (target - position) * pageSize
            }
        }
        snapFlingBehavior(provider, decay, PageSlideSpec)
    }
}

const val WEEKS_AROUND = 13
const val WEEK_COUNT = WEEKS_AROUND * 2 + 1

// ---------------------------------------------------------------------------
// WeekBar: одна неделя (пн–вс), стрелки и свайп листают ровно на неделю
// ---------------------------------------------------------------------------

@Composable
fun WeekBar(
    firstMonday: LocalDate,
    selected: LocalDate,
    today: LocalDate,
    dayOff: (LocalDate) -> Boolean,
    onSelect: (LocalDate) -> Unit,
    /** Цвет точки под числом (например, есть невыполненное ДЗ); null — без точки. */
    marker: (LocalDate) -> Color? = { null },
) {
    fun weekOf(date: LocalDate) =
        ChronoUnit.WEEKS.between(firstMonday, date).toInt().coerceIn(0, WEEK_COUNT - 1)

    val weekPager = rememberPagerState(initialPage = weekOf(selected)) { WEEK_COUNT }
    val scope = rememberCoroutineScope()

    // Выбранная дата ушла в другую неделю (свайп дней) → показать её неделю.
    // collectLatest: смена цели посреди анимации не оставляет полосу застрявшей между неделями.
    val currentSelected by rememberUpdatedState(selected)
    LaunchedEffect(weekPager) {
        snapshotFlow { weekOf(currentSelected) }.collectLatest { week ->
            if (weekPager.currentPage == week && weekPager.currentPageOffsetFraction == 0f) return@collectLatest
            try {
                weekPager.animateScrollToPage(week, animationSpec = PageSlideSpec)
            } catch (e: CancellationException) {
                // Перехвачено свайпом полосы или стрелками — это выбор пользователя.
                if (!currentCoroutineContext().isActive) throw e
            }
        }
    }
    // Стрелки и свайп только листают полосу недель — выбранный день не меняется.
    fun goWeek(delta: Int) {
        val target = (weekPager.currentPage + delta).coerceIn(0, WEEK_COUNT - 1)
        scope.launch { weekPager.animateScrollToPage(target, animationSpec = PageSlideSpec) }
    }

    val shownMonday = firstMonday.plusWeeks(weekPager.currentPage.toLong())
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = { goWeek(-1) },
                enabled = weekPager.currentPage > 0,
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Предыдущая неделя")
            }
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedContent(weekTitle(shownMonday), label = "week_title") { title ->
                    Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
                }
                val thisWeek = weekOf(today)
                // Под заголовком всегда одна строка той же высоты: на сегодняшнем дне — подпись,
                // в стороне от него — кнопка возврата со стрелкой в сторону сегодняшнего дня.
                val shownWeek = weekPager.targetPage
                val direction = when {
                    shownWeek != thisWeek -> if (shownWeek > thisWeek) -1 else 1
                    selected != today -> if (selected > today) -1 else 1
                    else -> 0
                }
                Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        targetState = direction,
                        // Кнопка «выезжает» со стороны своей стрелки и слегка пружинит; подпись просто гаснет.
                        transitionSpec = {
                            val side = if (targetState != 0) targetState else -initialState
                            val enter = fadeIn(tween(180)) + scaleIn(
                                spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
                                initialScale = 0.7f,
                            ) + slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { side * it / 3 }
                            val exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.85f)
                            (enter togetherWith exit).using(SizeTransform(clip = false))
                        },
                        label = "today_btn",
                    ) { dir ->
                        if (dir == 0) {
                            Text(
                                "эта неделя",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            FilledTonalButton(
                                onClick = {
                                    onSelect(today)
                                    // Сегодня уже выбрано, но полоса пролистана на другую неделю — вернуть её.
                                    scope.launch { weekPager.animateScrollToPage(thisWeek, animationSpec = PageSlideSpec) }
                                },
                                shapes = ButtonDefaults.shapes(),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp),
                            ) {
                                if (dir < 0) {
                                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = null, Modifier.size(18.dp))
                                }
                                Text("Сегодня", style = MaterialTheme.typography.labelMedium)
                                if (dir > 0) {
                                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
            FilledTonalIconButton(
                onClick = { goWeek(1) },
                enabled = weekPager.currentPage < WEEK_COUNT - 1,
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Следующая неделя")
            }
        }
        HorizontalPager(
            state = weekPager,
            modifier = Modifier.padding(top = 4.dp),
            flingBehavior = rememberShortSwipeFling(weekPager),
        ) { week ->
            val monday = firstMonday.plusWeeks(week.toLong())
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                repeat(7) { i ->
                    val date = monday.plusDays(i.toLong())
                    DayChip(
                        date = date,
                        selected = date == selected,
                        today = date == today,
                        dayOff = dayOff(date),
                        marker = marker(date),
                        onClick = { onSelect(date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** «21–27 сентября» / «29 сентября – 5 октября». */
private fun weekTitle(monday: LocalDate): String {
    val sunday = monday.plusDays(6)
    return if (monday.month == sunday.month) {
        "${monday.dayOfMonth}–${sunday.toRuDate()}"
    } else {
        "${monday.toRuDate()} – ${sunday.toRuDate()}"
    }
}

@Composable
private fun DayChip(
    date: LocalDate,
    selected: Boolean,
    today: Boolean,
    dayOff: Boolean,
    marker: Color?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // Короткий переход цвета: выделение переезжает сразу, но без резкого мигания.
    val container by animateColorAsState(
        when {
            selected -> colors.primary
            today -> colors.primaryContainer
            else -> colors.surfaceContainer
        },
        animationSpec = DayChipColorSpec,
        label = "day_container",
    )
    val content by animateColorAsState(
        when {
            selected -> colors.onPrimary
            today -> colors.onPrimaryContainer
            dayOff -> colors.error
            else -> colors.onSurface
        },
        animationSpec = DayChipColorSpec,
        label = "day_content",
    )
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        shape = if (selected) {
            MaterialTheme.shapes.large
        } else {
            rememberPressMorphShape(interactionSource, corner = 24.dp, pressedCorner = 12.dp)
        },
        color = container,
        contentColor = content,
        interactionSource = interactionSource,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = date.dayOfWeek.toShortRu(),
                style = MaterialTheme.typography.labelSmall,
                color = content.copy(alpha = 0.8f),
            )
            // Один стиль для всех дней: выделение — плавным масштабом, без смены шрифта и высоты строки.
            val numberScale by animateFloatAsState(if (selected) 1f else 0.8f, animationSpec = tween(120), label = "day_scale")
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleLargeEmphasized,
                modifier = Modifier.graphicsLayer {
                    scaleX = numberScale
                    scaleY = numberScale
                },
            )
            // Место под точку есть всегда — числа в соседних днях не прыгают.
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .size(5.dp)
                    .background(
                        when {
                            marker == null -> Color.Transparent
                            selected -> content
                            else -> marker
                        },
                        CircleShape,
                    ),
            )
        }
    }
}

private val DayChipColorSpec: TweenSpec<Color> = tween(durationMillis = 120)
