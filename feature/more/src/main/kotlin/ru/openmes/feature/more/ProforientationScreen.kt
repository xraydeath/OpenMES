package ru.openmes.feature.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import ru.openmes.core.common.runSuspendCatching
import ru.openmes.core.common.toRuDate
import ru.openmes.core.data.CollegeRepository
import ru.openmes.core.data.Session
import ru.openmes.core.data.SessionRepository
import ru.openmes.core.designsystem.components.EmptyState
import ru.openmes.core.designsystem.components.ErrorState
import ru.openmes.core.designsystem.components.GroupGap
import ru.openmes.core.designsystem.components.HeroCard
import ru.openmes.core.designsystem.components.LoadingState
import ru.openmes.core.designsystem.components.MesCard
import ru.openmes.core.designsystem.components.MesListItem
import ru.openmes.core.designsystem.components.MesPullToRefreshBox
import ru.openmes.core.designsystem.components.ScrollableFill
import ru.openmes.core.designsystem.components.SectionHeader
import ru.openmes.core.designsystem.components.StatusPill
import ru.openmes.core.designsystem.components.groupShape
import ru.openmes.core.designsystem.components.openUrl
import ru.openmes.core.model.ProfEvent
import ru.openmes.core.model.ProfIndustry
import ru.openmes.core.model.Proforientation

class ProforientationViewModel(
    sessionRepository: SessionRepository,
    private val collegeRepository: CollegeRepository,
) : ViewModel() {

    data class ProforientationUiState(
        val data: Proforientation? = null,
        val loading: Boolean = true,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(ProforientationUiState())
    val state = _state.asStateFlow()

    init {
        sessionRepository.session
            .onEach { if (it is Session.LoggedIn) refresh(force = false) }
            .launchIn(viewModelScope)
    }

    fun refresh(force: Boolean = true) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runSuspendCatching { collegeRepository.getProforientation(force) }
                .onSuccess { _state.value = ProforientationUiState(data = it, loading = false) }
                .onFailure { e -> _state.value = _state.value.copy(loading = false, error = e.message) }
        }
    }
}

/** Профориентация (portfolio): результат теста, рекомендованные отрасли и мероприятия. */
@Composable
fun ProforientationScreen(viewModel: ProforientationViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val data = state.data
    val context = LocalContext.current

    MesPullToRefreshBox(
        isRefreshing = state.loading && data != null,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            state.loading && data == null -> LoadingState()
            data == null -> ScrollableFill {
                ErrorState(title = "Профориентация недоступна", onRetry = viewModel::refresh, details = state.error)
            }

            data.testUrl == null && data.industries.isEmpty() && data.upcoming.isEmpty() && data.history.isEmpty() ->
                ScrollableFill {
                    EmptyState(
                        icon = Icons.Rounded.Explore,
                        title = "Результатов пока нет",
                        subtitle = "Пройдите профориентационное тестирование",
                    )
                }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(GroupGap),
            ) {
                item {
                    HeroCard {
                        Text("Профориентация", style = MaterialTheme.typography.titleLargeEmphasized)
                        Text(
                            data.testDate?.let { "Тест пройден ${it.toLocalDate().toRuDate(includeYear = true)}" }
                                ?: "Тест ещё не пройден",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                        if (data.industries.isNotEmpty()) {
                            Text(
                                "Подходящие отрасли: " + data.industries.joinToString { it.name },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
                val links = listOfNotNull(
                    data.testUrl?.let { Triple(Icons.Rounded.Assignment, "Отчёт о тестировании", it) },
                    data.detailsUrl?.let { Triple(Icons.Rounded.Explore, "Подробные результаты", it) },
                )
                if (links.isNotEmpty()) {
                    item { SectionHeader("Результаты", Modifier.padding(top = 12.dp)) }
                    itemsIndexed(links) { index, (icon, title, url) ->
                        MesListItem(
                            headline = title,
                            icon = icon,
                            onClick = { context.openUrl(url) },
                            trailingContent = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
                            shape = groupShape(index, links.size),
                        )
                    }
                }
                if (data.industries.isNotEmpty()) {
                    item { SectionHeader("Рекомендованные отрасли", Modifier.padding(top = 12.dp)) }
                    items(data.industries) { industry ->
                        IndustryCard(industry, onOpen = { context.openUrl(it) }, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
                eventGroup("Записи на мероприятия", data.upcoming)
                eventGroup("История мероприятий", data.history)
            }
        }
    }
}

@Composable
private fun IndustryCard(industry: ProfIndustry, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    MesCard(modifier = modifier, onClick = industry.atlasUrl?.let { { onOpen(it) } }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(industry.name, style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
            if (industry.atlasUrl != null) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Атлас колледжей")
            }
        }
        if (industry.specialties.isNotEmpty()) {
            Text(
                "Специальности",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
            industry.specialties.forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (industry.colleges.isNotEmpty()) {
            Text(
                "Колледжи",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                industry.colleges.forEach { college ->
                    AssistChip(
                        onClick = { college.atlasUrl?.let(onOpen) },
                        enabled = college.atlasUrl != null,
                        label = { Text(college.name) },
                        leadingIcon = { Icon(Icons.Rounded.School, contentDescription = null) },
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.eventGroup(title: String, events: List<ProfEvent>) {
    if (events.isEmpty()) return
    item { SectionHeader(title, Modifier.padding(top = 12.dp)) }
    itemsIndexed(events) { index, event ->
        MesListItem(
            headline = event.name,
            supporting = listOfNotNull(
                event.kind,
                listOfNotNull(event.date?.toRuDate(includeYear = true), event.time).joinToString(", ").ifBlank { null },
                event.address ?: event.organizer,
            ).joinToString("\n"),
            icon = if (event.visited) Icons.Rounded.CheckCircle else Icons.Rounded.Event,
            iconContainerColor = if (event.visited) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
            iconContentColor = if (event.visited) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            trailingContent = if (event.visited) ({ StatusPill("был") }) else null,
            shape = groupShape(index, events.size),
        )
    }
}
