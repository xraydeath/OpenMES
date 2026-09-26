package ru.openmes.feature.more

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
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.MilitaryTech
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.SportsGymnastics
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import ru.openmes.core.common.pluralRu
import ru.openmes.core.common.runSuspendCatching
import ru.openmes.core.common.toRuDate
import ru.openmes.core.data.CollegeRepository
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
import ru.openmes.core.designsystem.components.StatusPill
import ru.openmes.core.designsystem.components.groupShape
import ru.openmes.core.model.Portfolio
import ru.openmes.core.model.PortfolioEvent
import ru.openmes.core.model.PortfolioReward
import java.time.LocalDate

class PortfolioViewModel(
    sessionRepository: SessionRepository,
    private val collegeRepository: CollegeRepository,
) : ViewModel() {

    data class PortfolioUiState(
        val data: Portfolio? = null,
        val loading: Boolean = true,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(PortfolioUiState())
    val state = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        // Смена ребёнка: прошлая загрузка отменяется, чужое портфолио не показывается.
        sessionRepository.currentChildIdChanges()
            .onEach { childId ->
                loadJob?.cancel()
                _state.value = PortfolioUiState()
                if (childId != null) refresh(force = false)
            }
            .launchIn(viewModelScope)
    }

    fun refresh(force: Boolean = true) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            // Сначала — сохранённое (мгновенно), затем свежее из сети.
            if (_state.value.data == null) {
                collegeRepository.cachedOnly { getPortfolio(force = true) }?.let { data ->
                    if (_state.value.data == null) _state.value = _state.value.copy(data = data)
                }
            }
            runSuspendCatching { collegeRepository.getPortfolio(force) }
                .onSuccess { _state.value = PortfolioUiState(data = it, loading = false) }
                .onFailure { e -> _state.value = _state.value.copy(loading = false, error = e.message) }
        }
    }
}

/** Портфолио: награды (в т.ч. ГТО) и олимпиады с мероприятиями по учебным годам. */
@Composable
fun PortfolioScreen(viewModel: PortfolioViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val data = state.data

    MesPullToRefreshBox(
        isRefreshing = state.loading && data != null,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            state.loading && data == null -> LoadingState()
            data == null -> ScrollableFill {
                ErrorState(title = "Портфолио недоступно", onRetry = viewModel::refresh, details = state.error)
            }

            data.events.isEmpty() && data.rewards.isEmpty() -> ScrollableFill {
                EmptyState(
                    icon = Icons.Rounded.EmojiEvents,
                    title = "Портфолио пока пустое",
                    subtitle = "Здесь появятся олимпиады, конкурсы и награды",
                )
            }

            else -> {
                val eventsByYear = remember(data.events) {
                    data.events.groupBy { it.date?.schoolYear() }.toSortedMap(nullsLast(compareByDescending { it }))
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(GroupGap),
                ) {
                    item { PortfolioHero(data) }
                    if (data.rewards.isNotEmpty()) {
                        item { SectionHeader("Награды", Modifier.padding(top = 12.dp)) }
                        itemsIndexed(data.rewards) { index, reward ->
                            RewardItem(reward, groupShape(index, data.rewards.size))
                        }
                    }
                    eventsByYear.forEach { (year, events) ->
                        item(key = "year_$year") {
                            SectionHeader(
                                year?.let { "$it учебный год" } ?: "Без даты",
                                Modifier.padding(top = 12.dp),
                                trailing = {
                                    Text(
                                        "${events.size} ${pluralRu(events.size, "мероприятие", "мероприятия", "мероприятий")}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                        itemsIndexed(events, key = { i, _ -> "${year}_$i" }) { index, event ->
                            EventItem(event, groupShape(index, events.size))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PortfolioHero(data: Portfolio) {
    val prizes = data.events.count { it.reward.isPrize() }
    HeroCard {
        Text("Портфолио", style = MaterialTheme.typography.titleLargeEmphasized)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            StatValue(
                data.events.size.toString(),
                pluralRu(data.events.size, "мероприятие", "мероприятия", "мероприятий"),
                color = MaterialTheme.colorScheme.primary,
            )
            StatValue(prizes.toString(), pluralRu(prizes, "призовое\nместо", "призовых\nместа", "призовых\nмест"), color = MaterialTheme.colorScheme.primary)
            StatValue(
                data.rewards.size.toString(),
                pluralRu(data.rewards.size, "награда", "награды", "наград"),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun RewardItem(reward: PortfolioReward, shape: Shape) {
    MesListItem(
        headline = reward.name,
        supporting = listOfNotNull(
            reward.source,
            reward.details,
            reward.date?.toRuDate(includeYear = true),
            reward.expireDate?.let { "действует до ${it.toRuDate(includeYear = true)}" },
        ).joinToString(" · ").ifEmpty { null },
        icon = if (reward.sport) Icons.Rounded.SportsGymnastics else Icons.Rounded.MilitaryTech,
        iconShape = MaterialShapes.Sunny.toShape(),
        iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
        iconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = shape,
        trailingContent = reward.number?.let { number -> { StatusPill("№ $number") } },
    )
}

@Composable
private fun EventItem(event: PortfolioEvent, shape: Shape) {
    val prize = event.reward.isPrize()
    MesListItem(
        headline = event.name,
        supporting = listOfNotNull(event.date?.toRuDate(includeYear = true), event.format)
            .joinToString(" · ").ifEmpty { null },
        icon = if (prize) Icons.Rounded.EmojiEvents else Icons.Rounded.School,
        iconContainerColor = if (prize) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        iconContentColor = if (prize) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = shape,
        trailingContent = if (event.score == null && event.reward == null) null else {
            {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    event.score?.let { score ->
                        StatusPill(
                            score.formatScore() + (event.maxScore?.let { " из ${it.formatScore()}" } ?: ""),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    event.reward?.let { reward ->
                        StatusPill(
                            reward,
                            icon = if (prize) Icons.Rounded.EmojiEvents else null,
                            containerColor = if (prize) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = if (prize) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        },
    )
}

/** Призёр или победитель — всё, кроме участия. */
private fun String?.isPrize(): Boolean = this != null && !startsWith("Участник", ignoreCase = true)

/** 15.0 → «15», 12.5 → «12,5». */
private fun Double.formatScore(): String =
    if (this % 1.0 == 0.0) toLong().toString() else toString().replace('.', ',')

/** Учебный год даты: «2024/25» (с 1 сентября). */
private fun LocalDate.schoolYear(): String {
    val start = if (monthValue >= 9) year else year - 1
    return "%d/%02d".format(start, (start + 1) % 100)
}
