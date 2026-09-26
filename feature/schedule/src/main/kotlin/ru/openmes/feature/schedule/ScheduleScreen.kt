package ru.openmes.feature.schedule

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import android.widget.Toast
import androidx.core.content.FileProvider
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
import androidx.compose.ui.graphics.Shape
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
import ru.openmes.core.designsystem.components.WeekBar
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
        ) {
            LessonDetailsSheet(details, loading = viewModel.lessonDetailsLoading)
        }
    }

    val context = LocalContext.current
    // PDF недели выбранного дня: открыть просмотрщиком (или поделиться, если его нет).
    LaunchedEffect(viewModel) {
        viewModel.pdfResults.collect { result ->
            result.onSuccess { file -> context.openPdf(file) }
                .onFailure { Toast.makeText(context, "Не удалось получить PDF: ${it.message}", Toast.LENGTH_LONG).show() }
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
                    ScrollableFill { ErrorState(onRetry = viewModel::refresh, details = state.error) }
                else -> Column(Modifier.fillMaxSize()) {
                    if (state.error != null) RefreshErrorBanner(onRetry = viewModel::refresh)
                    HorizontalPager(
                        state = pagerState,
                        flingBehavior = rememberShortSwipeFling(pagerState),
                        // Без key — ключи вызывали ANR (грабли из OctoDiary-kt).
                        modifier = Modifier.fillMaxSize(),
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
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.CloudOff, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                "Не удалось обновить — показано сохранённое. Нажмите, чтобы повторить",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
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
            .padding(top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                date.toRuDate(),
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
            Text(
                "${date.dayOfWeek.toFullRu().replaceFirstChar { it.uppercase() }}, ${date.year}",
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

