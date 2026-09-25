package ru.openmes.feature.schedule

import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Apartment
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.MeetingRoom
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.EventNote
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material3.IconButton
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import ru.openmes.core.model.DayInfo
import java.io.File
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.openmes.core.common.toHM
import ru.openmes.core.common.toShortRu
import ru.openmes.core.designsystem.components.EmptyState
import ru.openmes.core.designsystem.components.ErrorState
import ru.openmes.core.designsystem.components.GroupGap
import ru.openmes.core.designsystem.components.LoadingState
import ru.openmes.core.designsystem.components.MarkBadge
import ru.openmes.core.designsystem.components.MesCard
import ru.openmes.core.designsystem.components.MesListItem
import ru.openmes.core.designsystem.components.MesPullToRefreshBox
import ru.openmes.core.designsystem.components.ScrollableFill
import ru.openmes.core.designsystem.components.WithMarkWeight
import ru.openmes.core.designsystem.components.SectionHeader
import ru.openmes.core.designsystem.components.ShapeIcon
import ru.openmes.core.designsystem.components.StatusPill
import ru.openmes.core.designsystem.components.groupShape
import ru.openmes.core.designsystem.components.markShape
import ru.openmes.core.designsystem.components.markTone
import ru.openmes.core.designsystem.components.openUrl
import ru.openmes.core.designsystem.components.rememberPressMorphShape
import ru.openmes.core.model.DayKind
import ru.openmes.core.model.Lesson
import ru.openmes.core.model.LessonDetails
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.pager.PagerState
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.snapping.snapFlingBehavior
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.TargetedFlingBehavior

private val MONTHS_GEN = mapOf(
    1 to "января", 2 to "февраля", 3 to "марта", 4 to "апреля",
    5 to "мая", 6 to "июня", 7 to "июля", 8 to "августа",
    9 to "сентября", 10 to "октября", 11 to "ноября", 12 to "декабря",
)

private val WEEKDAYS = listOf(
    "понедельник", "вторник", "среда", "четверг",
    "пятница", "суббота", "воскресенье",
)

/**
 * Расписание в стиле OctoDiary-kt: пейджер дней (окно ±45 дней),
 * CalendarBar с датами, карточки уроков с цветами по source и перерывами.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(viewModel: ScheduleViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    // Окно: ±WEEKS_AROUND недель от текущей, дни — с понедельника первой по воскресенье последней.
    val firstMonday = remember(today) {
        today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(WEEKS_AROUND.toLong())
    }
    val days = remember(firstMonday) { (0 until WEEK_COUNT * 7).map { firstMonday.plusDays(it.toLong()) } }
    val pagerState = rememberPagerState(initialPage = days.indexOf(today)) { days.size }

    // BottomSheet деталей урока: открывается сразу, детали догружаются.
    viewModel.lessonDetails?.let { details ->
        // Свежее состояние на каждое открытие и сразу во всю высоту: без «полуоткрытой»
        // промежуточной точки свайп вниз закрывает шторку с первого раза.
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = viewModel::closeLessonDetails,
            sheetState = sheetState,
        ) {
            LessonDetailsSheet(details, loading = viewModel.lessonDetailsLoading)
        }
    }

    // Идёт программный переход к выбранной дате: промежуточные остановки пейджера — не выбор пользователя.
    var syncing by remember { mutableStateOf(false) }

    // Выделение в WeekBar: пока пользователь листает дни — день, к которому едет пейджер,
    // чтобы подсветка не ждала окончания анимации свайпа.
    val selectedDate by rememberUpdatedState(state.selectedDate)
    val highlightedDate by remember {
        derivedStateOf {
            if (!syncing && pagerState.isScrollInProgress) days.getOrNull(pagerState.targetPage) ?: selectedDate else selectedDate
        }
    }

    // Дата, выбранная самим пейджером: к ней не нужно ехать программно.
    val pagerDate = remember { mutableStateOf<LocalDate?>(null) }

    // Пейджер остановился → выбрать дату (подгрузить месяц).
    LaunchedEffect(pagerState.settledPage) {
        val page = pagerState.settledPage
        if (!syncing && page in days.indices) {
            pagerDate.value = days[page]
            viewModel.selectDate(days[page])
        }
    }
    // Выбор даты (день в WeekBar, «К сегодня») → проскроллить пейджер.
    // collectLatest: новая дата отменяет недоехавший переход и сразу едет дальше с текущего места —
    // без проверок «идёт ли прокрутка», из-за которых выбор терялся и страница застревала между днями.
    LaunchedEffect(pagerState) {
        snapshotFlow { selectedDate }.collectLatest { date ->
            // Выбор пришёл от свайпа: страница уже там, а если палец успел начать следующий свайп —
            // «доводка» к этой дате отменяла бы его (быстрые свайпы подряд откатывались назад).
            if (date == pagerDate.value) return@collectLatest
            pagerDate.value = null
            val index = days.indexOf(date)
            if (index < 0 || (pagerState.currentPage == index && pagerState.currentPageOffsetFraction == 0f)) return@collectLatest
            syncing = true
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
                syncing = false
            }
        }
    }

    val context = LocalContext.current
    // PDF недели выбранного дня: открыть просмотрщиком (или поделиться, если его нет).
    fun exportPdf(date: LocalDate) {
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        viewModel.exportWeekPdf(monday, File(context.cacheDir, "exports")) { result ->
            result.onSuccess { file -> context.openPdf(file) }
                .onFailure { Toast.makeText(context, "Не удалось получить PDF: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    MesPullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            WeekBar(
                firstMonday = firstMonday,
                selected = highlightedDate,
                today = today,
                dayOff = { state.dayKind(it) != DayKind.WORKDAY },
                onSelect = viewModel::selectDate,
            )

            when {
                state.error != null -> ScrollableFill { ErrorState(onRetry = viewModel::refresh, details = state.error) }
                else -> HorizontalPager(
                    state = pagerState,
                    flingBehavior = rememberShortSwipeFling(pagerState),
                    // Без key — ключи вызывали ANR (грабли из OctoDiary-kt).
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val date = days[page]
                    DayPage(
                        date = date,
                        today = today,
                        lessons = state.lessonsFor(date),
                        loading = state.loading && state.months.isEmpty(),
                        dayKind = state.dayKind(date),
                        dayInfo = state.dayInfo(date),
                        onLessonClick = viewModel::openLessonDetails,
                        detailsOf = { viewModel.detailsById[it.id.toLongOrNull()] },
                        isTest = { state.testFor(it) != null },
                        pdfBusy = viewModel.pdfExporting,
                        onPdf = { exportPdf(date) },
                    )
                }
            }
        }
    }
}

/** Плавный сдвиг страницы без пружинного «отскока». */
private val PageSlideSpec = tween<Float>(durationMillis = 450, easing = FastOutSlowInEasing)

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
private fun rememberShortSwipeFling(state: PagerState): TargetedFlingBehavior {
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

private const val WEEKS_AROUND = 13
private const val WEEK_COUNT = WEEKS_AROUND * 2 + 1

// ---------------------------------------------------------------------------
// WeekBar: одна неделя (пн–вс), стрелки и свайп листают ровно на неделю
// ---------------------------------------------------------------------------

@Composable
private fun WeekBar(
    firstMonday: LocalDate,
    selected: LocalDate,
    today: LocalDate,
    dayOff: (LocalDate) -> Boolean,
    onSelect: (LocalDate) -> Unit,
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
        "${monday.dayOfMonth}–${sunday.dayOfMonth} ${MONTHS_GEN[sunday.monthValue]}"
    } else {
        "${monday.dayOfMonth} ${MONTHS_GEN[monday.monthValue]} – ${sunday.dayOfMonth} ${MONTHS_GEN[sunday.monthValue]}"
    }
}

@Composable
private fun DayChip(
    date: LocalDate,
    selected: Boolean,
    today: Boolean,
    dayOff: Boolean,
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
        }
    }
}

// ---------------------------------------------------------------------------
// Страница дня: заголовок + слитые карточки уроков с перерывами
// ---------------------------------------------------------------------------

@Composable
private fun DayPage(
    date: LocalDate,
    today: LocalDate,
    lessons: List<Lesson>,
    loading: Boolean,
    dayKind: DayKind,
    dayInfo: DayInfo?,
    onLessonClick: (Lesson) -> Unit,
    detailsOf: (Lesson) -> LessonDetails?,
    isTest: (Lesson) -> Boolean,
    pdfBusy: Boolean,
    onPdf: () -> Unit,
) {
    when {
        loading -> LoadingState()
        lessons.isEmpty() -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            DayHeader(date, lessons.size, pdfBusy = pdfBusy, onPdf = onPdf)
            EmptyState(
                icon = Icons.Rounded.EventBusy,
                title = "Уроков нет",
                subtitle = dayInfo?.note ?: when (dayKind) {
                    DayKind.VACATION -> dayInfo?.title ?: "Каникулы"
                    DayKind.HOLIDAY -> "Выходной"
                    DayKind.WORKDAY -> dayInfo?.title?.takeUnless { it.isTheory() } ?: "Занятий в этот день нет"
                },
            )
        }

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item(key = "day_header") { DayHeader(date, lessons.size, pdfBusy = pdfBusy, onPdf = onPdf) }
            // Перенос или особый период (практика) — плашкой над уроками.
            val banner = dayInfo?.note ?: dayInfo?.title?.takeUnless { it.isTheory() }
            if (banner != null) {
                item(key = "day_banner") {
                    StatusPill(
                        text = banner,
                        icon = Icons.Rounded.EventNote,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
            item(key = "day_lessons") {
                LessonsGroup(lessons, isToday = date == today, onLessonClick = onLessonClick, detailsOf = detailsOf, isTest = isTest)
            }
        }
    }
}

/** «23 сентября, среда» + число уроков. */
@Composable
private fun DayHeader(date: LocalDate, count: Int, pdfBusy: Boolean, onPdf: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${date.dayOfMonth} ${MONTHS_GEN[date.monthValue]}",
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
            Text(
                "${WEEKDAYS[date.dayOfWeek.value - 1].replaceFirstChar { it.uppercase() }}, ${date.year}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (count > 0) {
            StatusPill(
                text = "$count ${lessonsWord(count)}",
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        IconButton(onClick = onPdf, enabled = !pdfBusy) {
            if (pdfBusy) {
                LoadingIndicator(Modifier.size(24.dp))
            } else {
                Icon(Icons.Rounded.PictureAsPdf, contentDescription = "Расписание недели в PDF")
            }
        }
    }
}

/** «Теоретическое обучение» — обычный учебный день, отдельно его не показываем. */
private fun String.isTheory() = startsWith("Теоретическ", ignoreCase = true)

private fun Context.openPdf(file: File) {
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val share = Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        "Расписание",
    )
    // Просмотрщика PDF может не быть — тогда хотя бы поделиться файлом.
    runCatching { startActivity(view) }
        .recoverCatching { startActivity(share) }
        .onFailure { Toast.makeText(this, "Нет приложения для PDF", Toast.LENGTH_LONG).show() }
}

/** Слитые карточки уроков с перерывами между ними (стиль OctoDiary DayItem). */
@Composable
private fun LessonsGroup(
    lessons: List<Lesson>,
    isToday: Boolean,
    onLessonClick: (Lesson) -> Unit,
    detailsOf: (Lesson) -> LessonDetails?,
    isTest: (Lesson) -> Boolean,
) {
    // Номера — только у плановых уроков.
    val planNumbers = remember(lessons) {
        var n = 0
        lessons.map { if (it.source == null || it.source == "PLAN") ++n else null }
    }
    val now = remember { LocalTime.now() }
    // Перемены — полноширинные строки той же слитной группы, что и уроки.
    val rows = remember(lessons) {
        buildList {
            lessons.forEachIndexed { index, lesson ->
                val prev = lessons.getOrNull(index - 1)
                if (prev?.endTime != null && lesson.startTime != null) {
                    val gap = Duration.between(prev.endTime, lesson.startTime).toMinutes()
                    if (gap > 0) add(ScheduleRow.Break(gap))
                }
                add(ScheduleRow.LessonRow(lesson, index))
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        rows.forEachIndexed { rowIndex, row ->
            val shape = groupShape(rowIndex, rows.size)
            when (row) {
                is ScheduleRow.Break -> BreakRow(row.minutes, shape)
                is ScheduleRow.LessonRow -> {
                    val lesson = row.lesson
                    val current = isToday && lesson.startTime != null && lesson.endTime != null &&
                        now >= lesson.startTime && now < lesson.endTime
                    val details = detailsOf(lesson)
                    LessonCard(
                        lesson = lesson,
                        number = planNumbers[row.index],
                        current = current,
                        status = details?.diseaseStatusType,
                        distance = lesson.isDistance || details?.isDistance == true,
                        test = isTest(lesson),
                        shape = shape,
                        onClick = { onLessonClick(lesson) },
                    )
                }
            }
        }
    }
}

private sealed interface ScheduleRow {
    data class LessonRow(val lesson: Lesson, val index: Int) : ScheduleRow
    data class Break(val minutes: Long) : ScheduleRow
}

@Composable
private fun BreakRow(minutes: Long, shape: Shape) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            Icon(Icons.Rounded.Coffee, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("Перемена $minutes мин", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun LessonCard(
    lesson: Lesson,
    number: Int?,
    current: Boolean,
    status: String?,
    distance: Boolean,
    test: Boolean,
    shape: Shape,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when {
        current -> colors.primaryContainer to colors.onPrimaryContainer
        lesson.source == "EC" -> colors.secondaryContainer to colors.onSecondaryContainer
        lesson.source == "AE" -> colors.tertiaryContainer to colors.onTertiaryContainer
        lesson.source == "EVENTS" -> colors.surfaceContainerHighest to colors.onSurface
        else -> colors.surfaceContainer to colors.onSurface
    }
    val secondary = content.copy(alpha = 0.72f)

    MesCard(
        onClick = onClick,
        shape = shape,
        containerColor = container,
        contentColor = content,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Номер урока в фигуре (только PLAN)
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (number != null) {
                    Surface(
                        modifier = Modifier.size(if (current) 40.dp else 32.dp),
                        shape = if (current) MaterialShapes.Cookie9Sided.toShape() else MaterialShapes.Circle.toShape(),
                        color = if (current) colors.primary else colors.secondaryContainer,
                        contentColor = if (current) colors.onPrimary else colors.onSecondaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(number.toString(), style = MaterialTheme.typography.labelLargeEmphasized)
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                if (current) {
                    Text(
                        "СЕЙЧАС",
                        style = MaterialTheme.typography.labelSmallEmphasized,
                        color = colors.primary,
                    )
                }
                Text(
                    lesson.subjectName,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    lesson.lessonForm?.trim(),
                    "Дистанционно".takeIf { distance },
                    lesson.room,
                ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = secondary)
                }
            }

            // Время + оценки + ДЗ
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    buildString {
                        lesson.startTime?.let { append(it.toHM()) }
                        lesson.endTime?.let { append("–").append(it.toHM()) }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = secondary,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (status != null) DiseaseStatusIcon(status)
                    if (test) {
                        Icon(
                            Icons.Rounded.Quiz,
                            contentDescription = "Контрольное занятие",
                            modifier = Modifier.size(16.dp),
                            tint = colors.error,
                        )
                    }
                    if (distance) {
                        Icon(
                            Icons.Rounded.Videocam,
                            contentDescription = "Дистанционное занятие",
                            modifier = Modifier.size(16.dp),
                            tint = secondary,
                        )
                    }
                    if (lesson.homework != null) {
                        Icon(
                            Icons.AutoMirrored.Rounded.MenuBook,
                            contentDescription = "Есть ДЗ",
                            modifier = Modifier.size(16.dp),
                            tint = secondary,
                        )
                    }
                    lesson.marks.forEach { mark -> MiniMark(mark.value, mark.weight) }
                }
            }
        }
    }
}

/** Маленький значок болезни/освобождения в карточке урока. */
@Composable
private fun DiseaseStatusIcon(status: String) {
    val (container, content, label) = when (status) {
        "EXEMPT" -> Triple(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, "Освобождение")
        "SICK", "SICK_WITH_INFECTION" -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, "Болезнь")
        else -> Triple(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant, status)
    }
    Surface(modifier = Modifier.size(22.dp), shape = CircleShape, color = container, contentColor = content) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Healing, contentDescription = label, modifier = Modifier.size(14.dp))
        }
    }
}

/** Компактная оценка в строке урока: та же форма и тон, что у MarkBadge. */
@Composable
private fun MiniMark(value: String, weight: Int?) {
    val tone = markTone(value)
    WithMarkWeight(weight, compact = true) { badgeModifier ->
        Surface(modifier = badgeModifier.size(26.dp), shape = markShape(value), color = tone.container) {
            Box(contentAlignment = Alignment.Center) {
                Text(value, style = MaterialTheme.typography.labelMediumEmphasized, color = tone.content, maxLines = 1)
            }
        }
    }
}

private fun lessonsWord(n: Int): String = when {
    n % 100 in 11..14 -> "уроков"
    n % 10 == 1 -> "урок"
    n % 10 in 2..4 -> "урока"
    else -> "уроков"
}

// ---------------------------------------------------------------------------
// BottomSheet деталей урока (стиль OctoDiary LessonSheetContent)
// ---------------------------------------------------------------------------

@Composable
private fun LessonDetailsSheet(details: LessonDetails, loading: Boolean) {
    val context = LocalContext.current
    val info = listOfNotNull(
        details.module?.let { Triple(Icons.Rounded.EventNote, "Модуль / тема", it) },
        details.teacherName?.let { Triple(Icons.Rounded.Person, "Преподаватель", it) },
        details.room?.let { Triple(Icons.Rounded.MeetingRoom, "Кабинет", it) },
        details.building?.let { Triple(Icons.Rounded.Apartment, "Корпус", it) },
        details.comment?.takeIf { it.isNotBlank() }?.let { Triple(Icons.AutoMirrored.Rounded.Comment, "Комментарий", it) },
    )

    // Всё содержимое урока (ДЗ, тема, учитель) можно выделить и скопировать.
    SelectionContainer {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(GroupGap),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                ShapeIcon(
                    icon = Icons.Rounded.School,
                    shape = MaterialShapes.Cookie9Sided.toShape(),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    size = 56.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text(details.subjectName, style = MaterialTheme.typography.headlineSmallEmphasized)
                    val time = listOfNotNull(details.beginTime?.toHM(), details.endTime?.toHM()).joinToString("–")
                    if (time.isNotEmpty()) {
                        Text(time, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (loading) LoadingIndicator(Modifier.size(40.dp))
            }

            // Статус здоровья
            details.diseaseStatusType?.let { status ->
                val (text, container, content) = when (status) {
                    "EXEMPT" -> Triple("Освобождение", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                    "SICK" -> Triple("Болезнь", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                    "SICK_WITH_INFECTION" -> Triple("Болезнь (инфекция)", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                    else -> Triple(status, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(
                    text = text,
                    icon = Icons.Rounded.Healing,
                    containerColor = container,
                    contentColor = content,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            // Дистанционное занятие: плашка и кнопка подключения
            if (details.isDistance) {
                StatusPill(
                    text = "Дистанционное занятие",
                    icon = Icons.Rounded.Videocam,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            details.testName?.let { name ->
                StatusPill(
                    text = name,
                    icon = Icons.Rounded.Quiz,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            details.joinUrl?.let { url ->
                FilledTonalButton(
                    onClick = { context.openUrl(url) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Icon(Icons.Rounded.Videocam, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Подключиться к занятию")
                }
            }

            info.forEachIndexed { index, (icon, label, value) ->
                MesListItem(
                    headline = value,
                    supporting = label,
                    icon = icon,
                    shape = groupShape(index, info.size),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
            }

            // Домашнее задание
            details.homework?.takeIf { it.isNotBlank() }?.let { hw ->
                SectionHeader(
                    "Домашнее задание",
                    modifier = Modifier.padding(top = 8.dp),
                    trailing = if (details.homeworkDone) {
                        {
                            StatusPill(
                                "Выполнено",
                                icon = Icons.Rounded.Check,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    } else {
                        null
                    },
                )
                MesCard(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(hw, style = MaterialTheme.typography.bodyLarge)
                }
            }

            // Оценки
            if (details.marks.isNotEmpty()) {
                SectionHeader("Оценки", Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    details.marks.forEach { mark -> MarkBadge(mark.value, large = true, weight = mark.weight) }
                }
            }
        }
    }
}

private val DayChipColorSpec: TweenSpec<Color> = tween(durationMillis = 120)
