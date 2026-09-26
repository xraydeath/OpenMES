package ru.openmes.feature.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DoorFront
import androidx.compose.material.icons.rounded.NoMeetingRoom
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.openmes.core.common.humanize
import ru.openmes.core.common.toHM
import ru.openmes.core.common.runSuspendCatching
import ru.openmes.core.data.DiaryRepository
import ru.openmes.core.data.Session
import ru.openmes.core.data.SessionRepository
import ru.openmes.core.designsystem.components.EmptyState
import ru.openmes.core.designsystem.components.ErrorState
import ru.openmes.core.designsystem.components.GroupGap
import ru.openmes.core.designsystem.components.HeroCard
import ru.openmes.core.designsystem.components.LoadingState
import ru.openmes.core.designsystem.components.MesListItem
import ru.openmes.core.designsystem.components.MesPullToRefreshBox
import ru.openmes.core.designsystem.components.ScrollableFill
import ru.openmes.core.designsystem.components.SectionHeader
import ru.openmes.core.designsystem.components.StatValue
import ru.openmes.core.designsystem.components.groupShape
import ru.openmes.core.model.Visit
import ru.openmes.core.model.VisitDay
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

class VisitsViewModel(
    private val sessionRepository: SessionRepository,
    private val diaryRepository: DiaryRepository,
) : ViewModel() {

    data class VisitsUiState(
        val days: List<VisitDay> = emptyList(),
        /** Показан период с этой даты по сегодня. */
        val from: LocalDate = LocalDate.now().minusDays(PAGE_DAYS - 1),
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(VisitsUiState())
    val state = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var moreJob: Job? = null

    init {
        // Смена ребёнка: прошлые загрузки отменяются, чужие проходы не показываются.
        sessionRepository.currentChildIdChanges()
            .onEach { childId ->
                refreshJob?.cancel()
                moreJob?.cancel()
                _state.value = VisitsUiState()
                if (childId != null) refresh()
            }
            .launchIn(viewModelScope)
    }

    private fun childId() = (sessionRepository.session.value as? Session.LoggedIn)?.currentChild?.id

    fun refresh() {
        val childId = childId() ?: return
        // Подгрузка старых недель, закончившись после обновления, сдвинула бы `from` мимо загруженных дней.
        moreJob?.cancel()
        refreshJob?.cancel()
        val from = _state.value.from
        val today = LocalDate.now()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, loadingMore = false, error = null) }
            if (_state.value.days.isEmpty()) {
                diaryRepository.cachedOnly { getVisits(childId, from, today) }?.let { days ->
                    if (_state.value.days.isEmpty()) _state.update { it.copy(days = days) }
                }
            }
            runSuspendCatching { diaryRepository.getVisits(childId, from, today) }
                .onSuccess { days -> _state.update { it.copy(days = days, loading = false) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    /** Ещё четыре недели в прошлое. */
    fun loadMore() {
        val childId = childId() ?: return
        if (_state.value.loadingMore || _state.value.loading) return
        val to = _state.value.from.minusDays(1)
        val from = to.minusDays(PAGE_DAYS - 1)
        moreJob = viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            runSuspendCatching { diaryRepository.getVisits(childId, from, to) }
                .onSuccess { older ->
                    _state.update { st -> st.copy(days = st.days + older, from = from, loadingMore = false) }
                }
                .onFailure { _state.update { it.copy(loadingMore = false) } }
        }
    }

    private companion object {
        const val PAGE_DAYS = 28L
    }
}

/** Проходы через турникеты: когда пришёл, когда ушёл, сколько пробыл. */
@Composable
fun VisitsScreen(viewModel: VisitsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val days = state.days.filter { it.visits.isNotEmpty() }

    MesPullToRefreshBox(
        isRefreshing = state.loading && state.days.isNotEmpty(),
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            state.error != null && state.days.isEmpty() -> ScrollableFill { ErrorState(onRetry = viewModel::refresh, details = state.error) }
            state.loading && state.days.isEmpty() -> LoadingState()
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(GroupGap),
            ) {
                item { VisitsSummary(days, state.from) }
                if (days.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Rounded.NoMeetingRoom,
                            title = "Проходов нет",
                            subtitle = "За этот период турникеты не отмечали входов",
                        )
                    }
                }
                days.forEach { day ->
                    item(key = "h_${day.date}") {
                        SectionHeader(
                            title = day.date.humanize(),
                            modifier = Modifier.animateItem(),
                            trailing = {
                                day.totalMinutes().takeIf { it > 0 }?.let {
                                    Text(
                                        formatMinutes(it),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    }
                    itemsIndexed(day.visits, key = { i, _ -> "${day.date}_$i" }) { index, visit ->
                        VisitItem(visit, Modifier.animateItem(), groupShape(index, day.visits.size))
                    }
                }
                item(key = "more") {
                    FilledTonalButton(
                        onClick = viewModel::loadMore,
                        enabled = !state.loadingMore && !state.loading,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) {
                        if (state.loadingMore) {
                            LoadingIndicator(Modifier.padding(end = 8.dp))
                        }
                        Text("Показать ещё 4 недели")
                    }
                }
            }
        }
    }
}

@Composable
private fun VisitsSummary(days: List<VisitDay>, from: LocalDate) {
    val minutes = days.map { it.totalMinutes() }.filter { it > 0 }
    val firstIns = days.mapNotNull { day -> day.visits.mapNotNull { it.entered }.minOrNull() }
    HeroCard {
        Text(
            "С ${from.humanize()} по сегодня",
            style = MaterialTheme.typography.titleMediumEmphasized,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            StatValue(days.size.toString(), "дней\nв колледже", color = MaterialTheme.colorScheme.primary)
            StatValue(
                if (minutes.isEmpty()) "—" else formatMinutes(minutes.average().toLong()),
                "в среднем\nза день",
                color = MaterialTheme.colorScheme.primary,
            )
            StatValue(
                firstIns.averageTime()?.toHM() ?: "—",
                "средний\nприход",
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun VisitItem(visit: Visit, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape) {
    val times = "${visit.entered?.toHM() ?: "—"} → ${visit.left?.toHM() ?: "—"}"
    MesListItem(
        headline = times,
        supporting = listOfNotNull(
            visit.duration,
            "нет отметки ${if (visit.entered == null) "входа" else "выхода"}".takeIf { visit.incomplete },
            visit.place,
        ).joinToString(" · ").ifBlank { null },
        icon = Icons.Rounded.DoorFront,
        iconContainerColor = if (visit.incomplete) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        iconContentColor = if (visit.incomplete) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = shape,
        modifier = modifier,
    )
}

private fun VisitDay.totalMinutes(): Long = visits.sumOf { v ->
    if (v.entered != null && v.left != null && v.left!! > v.entered!!) Duration.between(v.entered, v.left).toMinutes() else 0L
}

private fun List<LocalTime>.averageTime(): LocalTime? =
    takeIf { it.isNotEmpty() }?.let { list -> LocalTime.ofSecondOfDay(list.map { it.toSecondOfDay().toLong() }.average().toLong()) }

private fun formatMinutes(total: Long): String = "${total / 60} ч ${total % 60} мин"
