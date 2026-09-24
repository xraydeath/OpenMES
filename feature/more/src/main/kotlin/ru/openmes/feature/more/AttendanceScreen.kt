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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import ru.openmes.core.common.humanize
import ru.openmes.core.common.runSuspendCatching
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AttendanceViewModel(
    private val sessionRepository: SessionRepository,
    private val diaryRepository: DiaryRepository,
) : ViewModel() {

    data class AttendanceUiState(
        val days: List<AttendanceEntry> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(AttendanceUiState())
    val state = _state.asStateFlow()

    init {
        sessionRepository.session
            .onEach { if (it is Session.LoggedIn) refresh() }
            .launchIn(viewModelScope)
    }

    fun refresh() {
        val childId = (sessionRepository.session.value as? Session.LoggedIn)?.currentChild?.id ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val today = LocalDate.now()
            // С начала учебного года (1 сентября).
            val yearStart = LocalDate.of(if (today.monthValue >= 9) today.year else today.year - 1, 9, 1)
            runSuspendCatching { diaryRepository.getAttendance(childId, yearStart, today) }
                .onSuccess { days ->
                    _state.value = AttendanceUiState(days = days.filter { it.lessons.isNotEmpty() }, loading = false)
                }
                .onFailure { e -> _state.value = _state.value.copy(loading = false, error = e.message) }
        }
    }
}

private enum class AttendanceFilter(val title: String) {
    All("Все"),
    Excused("Уважительные"),
    Unexcused("Без причины"),
}

/** Пропуски за учебный год: сводка + список по дням (причина, статус здоровья). */
@Composable
fun AttendanceScreen(viewModel: AttendanceViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(AttendanceFilter.All) }

    MesPullToRefreshBox(
        isRefreshing = state.loading && state.days.isNotEmpty(),
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            state.error != null -> ScrollableFill { ErrorState(onRetry = viewModel::refresh, details = state.error) }
            state.loading && state.days.isEmpty() -> LoadingState()
            state.days.isEmpty() -> ScrollableFill {
                EmptyState(
                    icon = Icons.Rounded.EventAvailable,
                    title = "Пропусков нет",
                    subtitle = "С начала учебного года не отмечено ни одного пропуска",
                )
            }

            else -> {
                val all = state.days.flatMap { it.lessons }
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
                                StatValue(all.size.toString(), "занятий\nпропущено", color = MaterialTheme.colorScheme.primary)
                                StatValue(state.days.size.toString(), "дней", color = MaterialTheme.colorScheme.primary)
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
                                        "${day.lessons.size} ${lessonsWord(day.lessons.size)}",
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

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun AttendanceLessonItem(lesson: AttendanceLesson, shape: Shape, modifier: Modifier = Modifier) {
    val excused = lesson.isExcused()
    MesListItem(
        headline = lesson.subjectName,
        modifier = modifier,
        supporting = lesson.beginTime?.let {
            listOfNotNull(lesson.beginTime, lesson.endTime).joinToString("–") { t -> t.format(timeFormat) }
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

/** Без причины — если причины нет или она из «неуважительных». */
private fun AttendanceLesson.isExcused(): Boolean = reason?.isExcused == true || healthStatus != null

private fun healthStatusTitle(status: String?): String? = when (status) {
    "SICK" -> "Болеет"
    "SICK_WITH_INFECTION" -> "Инфекционное заболевание"
    "EXEMPT" -> "Освобождение"
    null, "" -> null
    else -> status
}

private fun lessonsWord(n: Int): String = when {
    n % 100 in 11..14 -> "занятий"
    n % 10 == 1 -> "занятие"
    n % 10 in 2..4 -> "занятия"
    else -> "занятий"
}
