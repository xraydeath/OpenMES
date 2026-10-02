package ru.openmes.feature.schedule

import androidx.annotation.StringRes
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Apartment
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
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
import androidx.core.content.FileProvider
import ru.openmes.core.model.AbsenceReason
import ru.openmes.core.model.DayInfo
import java.io.File
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.YearMonth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.openmes.core.common.pluralRu
import ru.openmes.core.common.toFullRu
import ru.openmes.core.common.toHM
import ru.openmes.core.common.toRuDate
import ru.openmes.core.designsystem.components.EmptyState
import ru.openmes.core.designsystem.components.ErrorState
import ru.openmes.core.designsystem.components.GroupGap
import ru.openmes.core.designsystem.components.LoadingState
import ru.openmes.core.designsystem.components.mesDockReservedHeight
import ru.openmes.core.designsystem.components.mesFadeTop
import ru.openmes.core.designsystem.components.MarkBadge
import ru.openmes.core.designsystem.components.MesCard
import ru.openmes.core.designsystem.components.MesListItem
import ru.openmes.core.designsystem.components.MesPullToRefreshBox
import ru.openmes.core.designsystem.components.ScrollableFill
import ru.openmes.core.designsystem.components.WithMarkWeight
import ru.openmes.core.designsystem.components.mesSnackbar
import ru.openmes.core.designsystem.components.SectionHeader
import ru.openmes.core.designsystem.components.ShapeIcon
import ru.openmes.core.designsystem.components.StatusPill
import ru.openmes.core.designsystem.components.groupShape
import ru.openmes.core.designsystem.components.markShape
import ru.openmes.core.designsystem.components.markTone
import ru.openmes.core.designsystem.components.openUrl
import ru.openmes.core.designsystem.components.WeekBar
import ru.openmes.core.designsystem.theme.Spacing
import ru.openmes.core.designsystem.components.rememberDayPager
import ru.openmes.core.designsystem.components.rememberShortSwipeFling
import ru.openmes.core.model.DayKind
import ru.openmes.core.model.Lesson
import ru.openmes.core.model.LessonDetails
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import ru.openmes.feature.schedule.R

/**
 * Текущие дата и время, обновляемые на границе каждой минуты, пока экран на переднем плане,
 * и сразу при возврате (ON_RESUME) — иначе «сегодня» и «СЕЙЧАС» замирали бы на моменте открытия.
 */
@Composable
private fun rememberNow(): State<LocalDateTime> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(LocalDateTime.now(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val now = LocalDateTime.now()
                value = now
                delay(Duration.between(now, now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)).toMillis() + 1)
            }
        }
    }
}

/**
 * Расписание в стиле OctoDiary-kt: пейджер дней (окно ±45 дней),
 * CalendarBar с датами, карточки уроков с цветами по source и перерывами.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(viewModel: ScheduleViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now = rememberNow()
    // derivedStateOf: подписчики «сегодня» перерисовываются только при смене даты, а не каждую минуту.
    val today by remember { derivedStateOf { now.value.toLocalDate() } }
    val dayPager = rememberDayPager(state.selectedDate, viewModel::selectDate)
    val pagerState = dayPager.pagerState
    val days = dayPager.days

    // Наступила полночь, а выбран был «вчерашний сегодняшний» день — переезжаем на новый сегодняшний.
    var previousToday by remember { mutableStateOf(today) }
    LaunchedEffect(today) {
        if (today != previousToday) {
            if (state.selectedDate == previousToday && today in days) viewModel.selectDate(today)
            previousToday = today
        }
    }

    // BottomSheet деталей урока: открывается сразу, детали догружаются.
    viewModel.lessonDetails?.let { details ->
        // Свежее состояние на каждое открытие и сразу во всю высоту: без «полуоткрытой»
        // промежуточной точки свайп вниз закрывает шторку с первого раза.
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = viewModel::closeLessonDetails,
            sheetState = sheetState,
            // Свои ручки вместо стандартной: у той есть «нажимная» анимация, как у кнопки.
            dragHandle = { SheetHandle() },
        ) {
            LessonDetailsSheet(details, loading = viewModel.lessonDetailsLoading)
        }
    }

    val context = LocalContext.current
    // PDF недели выбранного дня: открыть просмотрщиком (или поделиться, если его нет).
    LaunchedEffect(viewModel) {
        viewModel.pdfResults.collect { result ->
            result.onSuccess { file -> context.openPdf(file) }
                .onFailure {
                    // Внутри LaunchedEffect composable-вызовов нет — берём текст через Context.
                    mesSnackbar.show(
                        errorText(context, R.string.schedule_pdf_failed, it.message),
                        duration = SnackbarDuration.Long,
                    )
                }
        }
    }
    fun exportPdf(date: LocalDate) {
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        viewModel.exportWeekPdf(monday, File(context.cacheDir, "exports"))
    }

    MesPullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            WeekBar(
                firstMonday = dayPager.firstMonday,
                selected = dayPager.highlightedDate,
                today = today,
                dayOff = { state.dayKind(it) != DayKind.WORKDAY },
                onSelect = viewModel::selectDate,
            )

            // Полноэкранная ошибка — только если выбранный месяц показать нечем; иначе расписание
            // (из кэша или прошлой загрузки) остаётся, а сбой обновления — плашкой сверху.
            val selectedMonthShown = YearMonth.from(state.selectedDate) in state.months
            when {
                state.error != null && !selectedMonthShown ->
                    ScrollableFill {
                        ErrorState(onRetry = viewModel::refresh, details = state.error?.let { stringResource(it) })
                    }
                else -> Column(Modifier.fillMaxSize()) {
                    if (state.error != null) RefreshErrorBanner(onRetry = viewModel::refresh)
                    HorizontalPager(
                        state = pagerState,
                        flingBehavior = rememberShortSwipeFling(pagerState),
                        // Без key — ключи вызывали ANR (грабли из OctoDiary-kt).
                        // mesFadeTop на пейджере, а не на списке внутри страницы: полоса тогда
                        // не уезжает вместе со страницей при свайпе по дням.
                        modifier = Modifier
                            .fillMaxSize()
                            .mesFadeTop(),
                    ) { page ->
                        val date = days[page]
                        DayPage(
                            date = date,
                            today = today,
                            now = { now.value.toLocalTime() },
                            lessons = state.lessonsFor(date),
                            // Месяц этой страницы ещё не пришёл — крутилка, а не ложное «Уроков нет».
                            loading = state.loading && YearMonth.from(date) !in state.months,
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
}

/** Неблокирующая плашка: обновить не вышло, но сохранённое расписание на экране. Тап — повтор. */
@Composable
private fun RefreshErrorBanner(onRetry: () -> Unit) {
    Surface(
        onClick = onRetry,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.xs),
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.l, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Icon(Icons.Rounded.CloudOff, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                stringResource(R.string.schedule_refresh_failed_banner),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Текст ошибки: строка [textRes] сама по себе, а с деталью от сети/сервера —
 * одной строкой через [R.string.schedule_error_detail].
 */
private fun errorText(context: Context, @StringRes textRes: Int, detail: String?): String =
    detail?.let { context.getString(R.string.schedule_error_detail, context.getString(textRes), it) }
        ?: context.getString(textRes)


// ---------------------------------------------------------------------------
// Страница дня: заголовок + слитые карточки уроков с перерывами
// ---------------------------------------------------------------------------

@Composable
private fun DayPage(
    date: LocalDate,
    today: LocalDate,
    now: () -> LocalTime,
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
        lessons.isEmpty() -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.l)) {
            DayHeader(date, lessons.size, pdfBusy = pdfBusy, onPdf = onPdf)
            EmptyState(
                icon = Icons.Rounded.EventBusy,
                title = stringResource(R.string.schedule_empty_title),
                subtitle = dayInfo?.note ?: when (dayKind) {
                    DayKind.VACATION -> dayInfo?.title ?: stringResource(R.string.schedule_day_vacation)
                    DayKind.HOLIDAY -> stringResource(R.string.schedule_day_off)
                    DayKind.WORKDAY -> dayInfo?.title?.takeUnless { it.isTheory() }
                        ?: stringResource(R.string.schedule_day_no_lessons)
                },
            )
        }

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.l,
                end = Spacing.l,
                bottom = Spacing.xl + mesDockReservedHeight(),
            ),
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
                        modifier = Modifier.padding(bottom = Spacing.m),
                    )
                }
            }
            item(key = "day_lessons") {
                LessonsGroup(lessons, isToday = date == today, now = now, onLessonClick = onLessonClick, detailsOf = detailsOf, isTest = isTest)
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
            .padding(top = Spacing.s, bottom = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                date.toRuDate(),
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
            Text(
                stringResource(
                    R.string.schedule_day_subtitle,
                    date.dayOfWeek.toFullRu().replaceFirstChar { it.uppercase() },
                    date.year,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (count > 0) {
            StatusPill(
                text = "$count ${pluralRu(count, "урок", "урока", "уроков")}",
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        IconButton(onClick = onPdf, enabled = !pdfBusy) {
            if (pdfBusy) {
                LoadingIndicator(Modifier.size(24.dp))
            } else {
                Icon(Icons.Rounded.PictureAsPdf, contentDescription = stringResource(R.string.schedule_pdf_cd))
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
        getString(R.string.schedule_pdf_share_title),
    )
    // Просмотрщика PDF может не быть — тогда хотя бы поделиться файлом.
    runCatching { startActivity(view) }
        .recoverCatching { startActivity(share) }
        .onFailure { mesSnackbar.show(getString(R.string.schedule_pdf_no_app), duration = SnackbarDuration.Long) }
}

/** Слитые карточки уроков с перерывами между ними (стиль OctoDiary DayItem). */
@Composable
private fun LessonsGroup(
    lessons: List<Lesson>,
    isToday: Boolean,
    /** Текущее время читается здесь: ежеминутный тик перерисовывает только группу уроков. */
    now: () -> LocalTime,
    onLessonClick: (Lesson) -> Unit,
    detailsOf: (Lesson) -> LessonDetails?,
    isTest: (Lesson) -> Boolean,
) {
    // Номера — только у плановых уроков.
    val planNumbers = remember(lessons) {
        var n = 0
        lessons.map { if (it.source == null || it.source == "PLAN") ++n else null }
    }
    val time = if (isToday) now() else null
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
                    val current = time != null && lesson.startTime != null && lesson.endTime != null &&
                        time >= lesson.startTime && time < lesson.endTime
                    val details = detailsOf(lesson)
                    // eventcalendar шлёт is_missed_lesson=false: пропуск реально приходит только
                    // из lesson_schedule_items — деталями, которые префетчатся на выбранный день ±1.
                    LessonCard(
                        lesson = lesson,
                        number = planNumbers[row.index],
                        current = current,
                        status = details?.diseaseStatusType,
                        distance = lesson.isDistance || details?.isDistance == true,
                        test = isTest(lesson),
                        missed = lesson.isMissedLesson || details?.isMissedLesson == true,
                        missedReasonId = lesson.absenceReasonId ?: details?.absenceReasonId,
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
            Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.CenterHorizontally),
        ) {
            Icon(Icons.Rounded.Coffee, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.schedule_break, minutes), style = MaterialTheme.typography.labelLarge)
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
    missed: Boolean,
    missedReasonId: Int?,
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
        contentPadding = PaddingValues(horizontal = Spacing.l, vertical = 14.dp),
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
            Spacer(Modifier.width(Spacing.m))

            Column(Modifier.weight(1f)) {
                if (current) {
                    Text(
                        stringResource(R.string.schedule_now),
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
                    stringResource(R.string.schedule_distance).takeIf { distance },
                    lesson.room,
                ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = secondary)
                }
            }

            // Время + оценки + ДЗ
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
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
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    if (status != null) DiseaseStatusIcon(status)
                    if (missed) MiniAbsence(missedReasonId)
                    if (test) {
                        Icon(
                            Icons.Rounded.Quiz,
                            contentDescription = stringResource(R.string.schedule_test_cd),
                            modifier = Modifier.size(16.dp),
                            tint = colors.error,
                        )
                    }
                    if (distance) {
                        Icon(
                            Icons.Rounded.Videocam,
                            contentDescription = stringResource(R.string.schedule_distance_lesson),
                            modifier = Modifier.size(16.dp),
                            tint = secondary,
                        )
                    }
                    if (lesson.homework != null) {
                        Icon(
                            Icons.AutoMirrored.Rounded.MenuBook,
                            contentDescription = stringResource(R.string.schedule_homework_cd),
                            modifier = Modifier.size(16.dp),
                            tint = secondary,
                        )
                    }
                    // Пропуск может прийти и флагом, и оценкой «Н» — не дублируем бейдж.
                    val marks = if (missed) {
                        lesson.marks.filterNot { it.value.trim().equals("Н", ignoreCase = true) }
                    } else {
                        lesson.marks
                    }
                    marks.forEach { mark -> MiniMark(mark.value, mark.weight) }
                }
            }
        }
    }
}

/** Маленький значок болезни/освобождения в карточке урока. */
@Composable
private fun DiseaseStatusIcon(status: String) {
    val (container, content, label) = when (status) {
        "EXEMPT" -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            stringResource(R.string.schedule_status_exempt),
        )
        "SICK", "SICK_WITH_INFECTION" -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            stringResource(R.string.schedule_status_sick),
        )
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

/**
 * Значок пропуска в строке урока: «Н» в красной плашке, по размеру как компактная оценка.
 * Причина (если сервер её прислал) — в contentDescription и в шторке деталей урока.
 */
@Composable
private fun MiniAbsence(reasonId: Int?) {
    val reason = AbsenceReason.byId(reasonId)?.title
    // semantics-лямбда не composable — текст описания разворачиваем здесь.
    val absenceCd = reason?.let { stringResource(R.string.schedule_absence_cd, it) }
        ?: stringResource(R.string.schedule_absence_cd_short)
    Surface(
        modifier = Modifier.size(26.dp),
        shape = MaterialShapes.Circle.toShape(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.schedule_absence_mark),
                style = MaterialTheme.typography.labelMediumEmphasized,
                maxLines = 1,
                modifier = Modifier.semantics {
                    // Причина от сервера — в описание, сама буква «Н» её не заменяет.
                    contentDescription = absenceCd
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// BottomSheet деталей урока (стиль OctoDiary LessonSheetContent)
// ---------------------------------------------------------------------------

/** Ручка шторки: просто полоска, без отклика на нажатие — это не кнопка. */
@Composable
private fun SheetHandle() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = Spacing.m, bottom = Spacing.m),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 32.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
        )
    }
}

/**
 * Плашка статуса занятия, собранная заранее, а не нарисованная сразу: статусов у урока
 * несколько, и перечислить их удобно списком, который потом раскладывается по ряду.
 */
private class StatusPillData(
    val text: String,
    val icon: ImageVector,
    val container: Color,
    val content: Color,
)

@Composable
private fun LessonDetailsSheet(details: LessonDetails, loading: Boolean) {
    val context = LocalContext.current
    val info = listOfNotNull(
        details.module?.let { Triple(Icons.Rounded.EventNote, stringResource(R.string.schedule_detail_module), it) },
        details.teacherName?.let { Triple(Icons.Rounded.Person, stringResource(R.string.schedule_detail_teacher), it) },
        details.room?.let { Triple(Icons.Rounded.MeetingRoom, stringResource(R.string.schedule_detail_room), it) },
        details.building?.let { Triple(Icons.Rounded.Apartment, stringResource(R.string.schedule_detail_building), it) },
        details.comment?.takeIf { it.isNotBlank() }
            ?.let { Triple(Icons.AutoMirrored.Rounded.Comment, stringResource(R.string.schedule_detail_comment), it) },
    )

    // Шторка раскрыта полностью: гасим остаток жеста вверх, который содержимое
    // уже не прокручивает, — иначе шторка вздрагивает (resist-and-snap) вместо «ничего».
    val absorbLeftoverUp = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (available.y < 0) available else Offset.Zero

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                if (available.y < 0) available else Velocity.Zero
        }
    }

    // Всё содержимое урока (ДЗ, тема, учитель) можно выделить и скопировать.
    SelectionContainer {
        Column(
            Modifier
                .fillMaxWidth()
                // Порядок важен: nestedScroll снаружи verticalScroll — тогда остаток
                // прокрутки гасится раньше, чем его получит шторка.
                .nestedScroll(absorbLeftoverUp)
                .verticalScroll(rememberScrollState(), overscrollEffect = null)
                .padding(horizontal = Spacing.l)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(GroupGap),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.l),
                modifier = Modifier.padding(bottom = Spacing.m),
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

            // Плашки статусов — болезнь, пропуск, дистанционное занятие, контрольная.
// Раньше каждая стояла своей строкой, и урок с двумя-тремя статусами растягивался
// на пол-экрана: между однородными плашками отдельная строка ничего не добавляет.
// Теперь это один ряд, а не влезшие переносятся ниже — [FlowRow] сам решает,
// где кончится место.
            val statusPills = buildList {
                // Статус здоровья
                details.diseaseStatusType?.let { status ->
                    val (text, container, content) = when (status) {
                        "EXEMPT" -> Triple(
                            stringResource(R.string.schedule_status_exempt),
                            MaterialTheme.colorScheme.tertiaryContainer,
                            MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        "SICK" -> Triple(
                            stringResource(R.string.schedule_status_sick),
                            MaterialTheme.colorScheme.errorContainer,
                            MaterialTheme.colorScheme.onErrorContainer,
                        )
                        "SICK_WITH_INFECTION" -> Triple(
                            stringResource(R.string.schedule_status_sick_infection),
                            MaterialTheme.colorScheme.errorContainer,
                            MaterialTheme.colorScheme.onErrorContainer,
                        )
                        else -> Triple(
                            status,
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    add(StatusPillData(text, Icons.Rounded.Healing, container, content))
                }
                // Пропуск: преподаватель отметил отсутствие на занятии
                if (details.isMissedLesson) {
                    val reason = AbsenceReason.byId(details.absenceReasonId)?.title
                    add(
                        StatusPillData(
                            text = reason?.let { stringResource(R.string.schedule_missed_reason, it) }
                                ?: stringResource(R.string.schedule_missed),
                            icon = Icons.Rounded.EventBusy,
                            container = MaterialTheme.colorScheme.errorContainer,
                            content = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    )
                }
                // Дистанционное занятие
                if (details.isDistance) {
                    add(
                        StatusPillData(
                            text = stringResource(R.string.schedule_distance_lesson),
                            icon = Icons.Rounded.Videocam,
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    )
                }
                details.testName?.let { name ->
                    add(
                        StatusPillData(
                            text = name,
                            icon = Icons.Rounded.Quiz,
                            container = MaterialTheme.colorScheme.errorContainer,
                            content = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    )
                }
            }
            if (statusPills.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                    modifier = Modifier.padding(bottom = Spacing.s),
                ) {
                    statusPills.forEach { pill ->
                        StatusPill(
                            text = pill.text,
                            icon = pill.icon,
                            containerColor = pill.container,
                            contentColor = pill.content,
                        )
                    }
                }
            }
            details.joinUrl?.let { url ->
                FilledTonalButton(
                    onClick = { context.openUrl(url) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.s),
                ) {
                    Icon(Icons.Rounded.Videocam, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.schedule_join_lesson))
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
                    stringResource(R.string.schedule_homework_section),
                    modifier = Modifier.padding(top = Spacing.s),
                    trailing = if (details.homeworkDone) {
                        {
                            StatusPill(
                                stringResource(R.string.schedule_homework_done),
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
                SectionHeader(stringResource(R.string.schedule_marks_section), Modifier.padding(top = Spacing.s))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    details.marks.forEach { mark -> MarkBadge(mark.value, large = true, weight = mark.weight) }
                }
            }
        }
    }
}

