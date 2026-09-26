package ru.openmes.feature.more

import ru.openmes.core.designsystem.components.ScrollableFill
import ru.openmes.core.designsystem.components.MesPullToRefreshBox
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import ru.openmes.core.common.humanize
import ru.openmes.core.common.pluralRu
import ru.openmes.core.common.runSuspendCatching
import ru.openmes.core.common.toHM
import ru.openmes.core.common.toRuDate
import ru.openmes.core.data.DiaryRepository
import ru.openmes.core.data.Session
import ru.openmes.core.data.SessionRepository
import ru.openmes.core.designsystem.components.ConnectedChoiceGroup
import ru.openmes.core.designsystem.components.EmptyState
import ru.openmes.core.designsystem.components.ErrorState
import ru.openmes.core.designsystem.components.GroupGap
import ru.openmes.core.designsystem.components.HeroCard
import ru.openmes.core.designsystem.components.LoadingState
import ru.openmes.core.designsystem.components.MesListItem
import ru.openmes.core.designsystem.components.SectionHeader
import ru.openmes.core.designsystem.components.StatValue
import ru.openmes.core.designsystem.components.StatusPill
import ru.openmes.core.designsystem.components.groupShape
import ru.openmes.core.model.AttendanceEntry
import ru.openmes.core.model.AttendanceLesson
import ru.openmes.core.model.MedicalRecord
import java.time.DayOfWeek
import java.time.LocalDate

class AttendanceViewModel(
    private val sessionRepository: SessionRepository,
    private val diaryRepository: DiaryRepository,
) : ViewModel() {

    data class AttendanceUiState(
        val days: List<AttendanceEntry> = emptyList(),
        /** Справки ЕМИАС с начала учебного года, по дням. */
        val medical: List<MedicalRecord> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(AttendanceUiState())
    val state = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        // Смена ребёнка: прошлая загрузка отменяется, чужие пропуски не показываются.
        sessionRepository.currentChildIdChanges()
            .onEach { childId ->
                loadJob?.cancel()
                _state.value = AttendanceUiState()
                if (childId != null) refresh()
            }
            .launchIn(viewModelScope)
    }

    fun refresh() {
        val childId = (sessionRepository.session.value as? Session.LoggedIn)?.currentChild?.id ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val today = LocalDate.now()
            // С начала учебного года (1 сентября).
            val yearStart = LocalDate.of(if (today.monthValue >= 9) today.year else today.year - 1, 9, 1)
            // Сначала — сохранённое (мгновенно), затем свежее из сети.
            if (_state.value.days.isEmpty()) {
                diaryRepository.cachedOnly { getAttendance(childId, yearStart, today) }?.let { days ->
                    if (_state.value.days.isEmpty()) _state.value = _state.value.copy(days = days.filter { it.lessons.isNotEmpty() })
                }
            }
            if (_state.value.medical.isEmpty()) {
                diaryRepository.cachedOnly { getMedicalRecords(childId) }?.let { all ->
                    val medical = all.filter { !it.date.isBefore(yearStart) }
                    if (_state.value.medical.isEmpty()) _state.value = _state.value.copy(medical = medical)
                }
            }
            // Справки — дополнение: их сбой не мешает показать пропуски. Сервис отдаёт их за всё время.
            val medical = runSuspendCatching { diaryRepository.getMedicalRecords(childId) }.getOrNull()
                ?.filter { !it.date.isBefore(yearStart) }
            runSuspendCatching { diaryRepository.getAttendance(childId, yearStart, today) }
                .onSuccess { days ->
                    _state.value = AttendanceUiState(
                        days = days.filter { it.lessons.isNotEmpty() },
                        medical = medical ?: _state.value.medical,
                        loading = false,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        medical = medical ?: _state.value.medical,
                        loading = false,
                        error = e.message,
                    )
                }
        }
    }
}

private enum class AttendanceFilter(val title: String) {
    All("Все"),
    Excused("Уважительные"),
    Unexcused("Без причины"),
}

/** Пропуски за учебный год: сводка, справки ЕМИАС и список по дням (причина, статус здоровья). */
@Composable
fun AttendanceScreen(viewModel: AttendanceViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(AttendanceFilter.All) }
    var allPeriods by remember { mutableStateOf(false) }

    MesPullToRefreshBox(
        isRefreshing = state.loading && state.days.isNotEmpty(),
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            state.error != null && state.days.isEmpty() && state.medical.isEmpty() -> ScrollableFill { ErrorState(onRetry = viewModel::refresh, details = state.error) }
            state.loading && state.days.isEmpty() && state.medical.isEmpty() -> LoadingState()
            state.days.isEmpty() && state.medical.isEmpty() -> ScrollableFill {
                EmptyState(
                    icon = Icons.Rounded.EventAvailable,
                    title = "Пропусков нет",
                    subtitle = "С начала учебного года не отмечено ни одного пропуска",
                )
            }

            else -> {
                val all = state.days.flatMap { it.lessons }
                val periods = remember(state.medical) { state.medical.toPeriods() }
                val excused = all.count { it.isExcused() }
                val filtered = remember(state.days, filter) {
                    state.days.mapNotNull { day ->
                        val lessons = day.lessons.filter {
                            when (filter) {
                                AttendanceFilter.All -> true
                                AttendanceFilter.Excused -> it.isExcused()
                                AttendanceFilter.Unexcused -> !it.isExcused()
                            }
                        }
                        lessons.takeIf { it.isNotEmpty() }?.let { day.copy(lessons = it) }
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(GroupGap),
                ) {
                    item {
                        HeroCard {
                            Text("С начала учебного года", style = MaterialTheme.typography.titleMediumEmphasized)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp),
                                horizontalArrangement = Arrangement.SpaceAround,
                            ) {
                                StatValue(all.size.toString(), "${pluralRu(all.size, "занятие", "занятия", "занятий")}\nпропущено", color = MaterialTheme.colorScheme.primary)
                                StatValue(state.days.size.toString(), pluralRu(state.days.size, "день", "дня", "дней"), color = MaterialTheme.colorScheme.primary)
                                StatValue(
                                    (all.size - excused).toString(),
                                    "без\nпричины",
                                    color = if (all.size - excused > 0) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                )
                            }
                        }
                    }
                    if (periods.isNotEmpty()) {
                        val shown = if (allPeriods) periods else periods.take(MEDICAL_PREVIEW)
                        item(key = "medical_header") {
                            SectionHeader(
                                "Справки ЕМИАС",
                                modifier = Modifier.padding(top = 12.dp),
                                trailing = {
                                    Text(
                                        "${state.medical.size} ${pluralRu(state.medical.size, "день", "дня", "дней")}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                        val more = periods.size > MEDICAL_PREVIEW
                        val count = shown.size + if (more) 1 else 0
                        itemsIndexed(shown, key = { _, p -> "medical_${p.from}_${p.type}" }) { index, period ->
                            MedicalPeriodItem(period, groupShape(index, count), Modifier.animateItem())
                        }
                        if (more) {
                            item(key = "medical_more") {
                                MesListItem(
                                    headline = if (allPeriods) "Свернуть" else "Показать все (${periods.size})",
                                    icon = if (allPeriods) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    shape = groupShape(count - 1, count),
                                    onClick = { allPeriods = !allPeriods },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                    item {
                        ConnectedChoiceGroup(
                            options = AttendanceFilter.entries,
                            selected = filter,
                            onSelect = { filter = it },
                            label = { it.title },
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    filtered.forEach { day ->
                        item(key = "h_${day.date}") {
                            SectionHeader(
                                title = day.date.humanize(),
                                modifier = Modifier.animateItem(),
                                trailing = {
                                    Text(
                                        "${day.lessons.size} ${pluralRu(day.lessons.size, "занятие", "занятия", "занятий")}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                        itemsIndexed(day.lessons, key = { i, _ -> "${day.date}_$i" }) { index, lesson ->
                            AttendanceLessonItem(
                                lesson = lesson,
                                shape = groupShape(index, day.lessons.size),
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttendanceLessonItem(lesson: AttendanceLesson, shape: Shape, modifier: Modifier = Modifier) {
    val excused = lesson.isExcused()
    MesListItem(
        headline = lesson.subjectName,
        modifier = modifier,
        supporting = lesson.beginTime?.let {
            listOfNotNull(lesson.beginTime, lesson.endTime).joinToString("–") { t -> t.toHM() }
        },
        icon = if (excused) Icons.Rounded.EventAvailable else Icons.Rounded.EventBusy,
        iconShape = if (excused) MaterialShapes.Cookie6Sided.toShape() else MaterialShapes.SoftBurst.toShape(),
        iconContainerColor = if (excused) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        iconContentColor = if (excused) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onErrorContainer
        },
        shape = shape,
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusPill(
                    text = lesson.reason?.title ?: "Без причины",
                    containerColor = if (excused) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    contentColor = if (excused) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                )
                healthStatusTitle(lesson.healthStatus)?.let {
                    StatusPill(
                        text = it,
                        icon = Icons.Rounded.Healing,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        },
    )
}

/** Период по справкам ЕМИАС: подряд идущие дни одного типа (выходные внутри не разрывают). */
private data class MedicalPeriod(
    val type: String,
    val partial: Boolean,
    val from: LocalDate,
    val to: LocalDate,
    val days: Int,
)

private const val MEDICAL_PREVIEW = 3

/** Дни — в периоды, свежие сверху. */
private fun List<MedicalRecord>.toPeriods(): List<MedicalPeriod> {
    val result = mutableListOf<MedicalPeriod>()
    for (r in sortedBy { it.date }) {
        val last = result.lastOrNull()
        if (last != null && last.type == r.type && last.partial == r.partial && onlyWeekendsBetween(last.to, r.date)) {
            result[result.lastIndex] = last.copy(to = r.date, days = last.days + 1)
        } else {
            result += MedicalPeriod(r.type, r.partial, r.date, r.date, 1)
        }
    }
    return result.asReversed()
}

private fun onlyWeekendsBetween(a: LocalDate, b: LocalDate): Boolean =
    generateSequence(a.plusDays(1)) { it.plusDays(1) }
        .takeWhile { it.isBefore(b) }
        .all { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY }

@Composable
private fun MedicalPeriodItem(period: MedicalPeriod, shape: Shape, modifier: Modifier = Modifier) {
    val exempt = period.type == "EXEMPT"
    val title = when {
        exempt && period.partial -> "Освобождение от части предметов"
        else -> healthStatusTitle(period.type) ?: period.type
    }
    val dates = if (period.from == period.to) {
        period.from.toRuDate(includeYear = true)
    } else {
        "${period.from.toRuDate(includeYear = period.from.year != period.to.year)} – ${period.to.toRuDate(includeYear = true)}"
    }
    MesListItem(
        headline = title,
        supporting = dates,
        icon = Icons.Rounded.Healing,
        iconShape = MaterialShapes.Clover4Leaf.toShape(),
        iconContainerColor = if (exempt) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
        iconContentColor = if (exempt) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
        shape = shape,
        modifier = modifier,
        trailingContent = {
            StatusPill(
                "${period.days} ${pluralRu(period.days, "день", "дня", "дней")}",
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/** Без причины — если причины нет или она из «неуважительных». */
private fun AttendanceLesson.isExcused(): Boolean = reason?.isExcused == true || healthStatus != null

private fun healthStatusTitle(status: String?): String? = when (status) {
    "SICK" -> "Болеет"
    "SICK_WITH_INFECTION" -> "Инфекционное заболевание"
    "EXEMPT" -> "Освобождение"
    null, "" -> null
    else -> status
}
