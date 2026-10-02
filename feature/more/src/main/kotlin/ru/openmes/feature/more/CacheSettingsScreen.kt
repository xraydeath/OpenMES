package ru.openmes.feature.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DoorFront
import androidx.compose.material.icons.rounded.EventNote
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.Grade
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.openmes.core.data.AppSettings
import ru.openmes.core.data.SettingsRepository
import ru.openmes.core.designsystem.components.ConnectedChoiceGroup
import ru.openmes.core.designsystem.components.GroupGap
import ru.openmes.core.designsystem.components.MesCard
import ru.openmes.core.designsystem.components.SectionHeader
import ru.openmes.core.designsystem.components.ShapeIcon
import ru.openmes.core.designsystem.components.groupShape
import ru.openmes.core.designsystem.theme.Spacing
import ru.openmes.core.network.interceptor.CacheSection
import ru.openmes.core.network.interceptor.CacheStats
import ru.openmes.core.network.interceptor.OfflineCache
import java.util.Locale

class CacheSettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val offlineCache: OfflineCache,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _stats = MutableStateFlow<CacheStats?>(null)
    val stats = _stats.asStateFlow()

    fun refreshStats() = viewModelScope.launch {
        _stats.value = withContext(Dispatchers.IO) { offlineCache.stats() }
    }

    /** Новая политика применяется сразу (не дожидаясь подписки в OpenMESApp), чтобы размер был уже после чистки. */
    private fun update(block: suspend SettingsRepository.() -> Unit) = viewModelScope.launch {
        settingsRepository.block()
        offlineCache.policy = settingsRepository.settings.first().cachePolicy
        withContext(Dispatchers.IO) { offlineCache.cleanup() }
        refreshStats()
    }

    fun setEnabled(enabled: Boolean) = update { setCacheEnabled(enabled) }
    fun setSection(section: CacheSection, enabled: Boolean) = update { setCacheSection(section, enabled) }
    fun setWindowDays(days: Int?) = update { setCacheWindowDays(days) }
    fun setBackgroundRefresh(enabled: Boolean) = update { setCacheBackgroundRefresh(enabled) }

    /** Профиль для входа оставляем: без него запуск без сети выкидывает из аккаунта. */
    fun clear() = viewModelScope.launch {
        withContext(Dispatchers.IO) { offlineCache.clear(keepSession = true) }
        refreshStats()
    }
}

/** Настройки офлайн-кэша: вкл/выкл, разделы, период данных, ручная очистка. */
@Composable
fun CacheSettingsScreen(viewModel: CacheSettingsViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refreshStats() }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
            title = { Text(stringResource(R.string.more_cache_clear_title)) },
            text = { Text(stringResource(R.string.more_cache_clear_text)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clear()
                        confirmClear = false
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(R.string.more_action_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.more_action_cancel))
                }
            },
        )
    }

    val main = buildList<@Composable (Shape) -> Unit> {
        add { shape ->
            SwitchItem(
                icon = Icons.Rounded.Storage,
                title = stringResource(R.string.more_cache_enabled),
                subtitle = stringResource(R.string.more_cache_enabled_sub),
                checked = settings.cacheEnabled,
                onCheckedChange = viewModel::setEnabled,
                shape = shape,
            )
        }
        if (settings.cacheEnabled) {
            add { shape ->
                SwitchItem(
                    icon = Icons.Rounded.Sync,
                    title = stringResource(R.string.more_cache_background),
                    subtitle = stringResource(R.string.more_cache_background_sub),
                    checked = settings.cacheBackgroundRefresh,
                    onCheckedChange = viewModel::setBackgroundRefresh,
                    shape = shape,
                )
            }
        }
    }
    val sections = CacheSection.entries.filter { it.userToggleable }.map { section ->
        val (icon, title, subtitle) = section.presentation()
        val row: @Composable (Shape) -> Unit = { shape ->
            SwitchItem(
                icon = icon,
                title = stringResource(title),
                subtitle = subtitle?.let { stringResource(it) },
                checked = section in settings.cacheSections,
                onCheckedChange = { viewModel.setSection(section, it) },
                shape = shape,
            )
        }
        row
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.l, end = Spacing.l, top = Spacing.s, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(GroupGap),
    ) {
        item {
            MesCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                    ShapeIcon(
                        icon = Icons.Rounded.Storage,
                        shape = MaterialShapes.Cookie9Sided.toShape(),
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                        size = 48.dp,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            stats?.let { formatBytes(it.bytes) } ?: "…",
                            style = MaterialTheme.typography.titleLargeEmphasized,
                        )
                        Text(
                            stats?.let { stringResource(R.string.more_cache_records, it.files) }
                                ?: stringResource(R.string.more_cache_counting),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    FilledTonalButton(
                        onClick = { confirmClear = true },
                        enabled = (stats?.files ?: 0) > 0,
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(R.string.more_action_clear)) }
                }
            }
        }

        item { SectionHeader(stringResource(R.string.more_cache_section), Modifier.padding(top = Spacing.m)) }
        group(main)

        if (settings.cacheEnabled) {
            item { SectionHeader(stringResource(R.string.more_cache_what), Modifier.padding(top = Spacing.m)) }
            group(sections)

            item { SectionHeader(stringResource(R.string.more_cache_period), Modifier.padding(top = Spacing.m)) }
            item {
                // label у ChoiceCard — обычная функция, подписи периода разворачиваем заранее.
                val periodLabels = mapOf(
                    14 to stringResource(R.string.more_cache_period_2w),
                    30 to stringResource(R.string.more_cache_period_month),
                    90 to stringResource(R.string.more_cache_period_3m),
                    null to stringResource(R.string.more_cache_period_all),
                )
                ChoiceCard(
                    description = stringResource(R.string.more_cache_period_desc),
                    options = listOf(14, 30, 90, null),
                    selected = settings.cacheWindowDays,
                    onSelect = viewModel::setWindowDays,
                    label = { periodLabels[it] ?: periodLabels.getValue(null) },
                )
            }
        }

        item {
            Text(
                stringResource(R.string.more_cache_profile_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.m),
            )
        }
    }
}

@Composable
private fun ChoiceCard(
    description: String,
    options: List<Int?>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    label: (Int?) -> String,
) {
    MesCard {
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ConnectedChoiceGroup(
            options = options,
            selected = selected,
            onSelect = onSelect,
            label = label,
            modifier = Modifier.padding(top = Spacing.m),
        )
    }
}

/** Иконка и id ресурсов подписи раздела офлайн-кэша. */
private fun CacheSection.presentation(): Triple<ImageVector, Int, Int?> = when (this) {
    CacheSection.SCHEDULE -> Triple(Icons.Rounded.CalendarMonth, R.string.more_cache_sec_schedule, R.string.more_cache_sec_schedule_sub)
    CacheSection.CALENDAR -> Triple(Icons.Rounded.EventNote, R.string.more_cache_sec_calendar, R.string.more_cache_sec_calendar_sub)
    CacheSection.PLAN -> Triple(Icons.Rounded.Quiz, R.string.more_cache_sec_plan, R.string.more_cache_sec_plan_sub)
    CacheSection.MARKS -> Triple(Icons.Rounded.Grade, R.string.more_cache_sec_marks, R.string.more_cache_sec_marks_sub)
    CacheSection.HOMEWORK -> Triple(Icons.AutoMirrored.Rounded.MenuBook, R.string.more_cache_sec_homework, null)
    CacheSection.ATTENDANCE -> Triple(Icons.Rounded.EventAvailable, R.string.more_cache_sec_attendance, R.string.more_cache_sec_attendance_sub)
    CacheSection.VISITS -> Triple(Icons.Rounded.DoorFront, R.string.more_cache_sec_visits, R.string.more_cache_sec_visits_sub)
    CacheSection.FOOD -> Triple(Icons.Rounded.Restaurant, R.string.more_cache_sec_food, R.string.more_cache_sec_food_sub)
    CacheSection.NEWS -> Triple(Icons.Rounded.Newspaper, R.string.more_cache_sec_news, null)
    CacheSection.PORTFOLIO -> Triple(Icons.Rounded.WorkspacePremium, R.string.more_cache_sec_portfolio, R.string.more_cache_sec_portfolio_sub)
    CacheSection.PROFILE -> Triple(Icons.Rounded.Badge, R.string.more_cache_sec_profile, null)
    CacheSection.SESSION -> Triple(Icons.Rounded.Storage, R.string.more_cache_sec_session, null)
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> String.format(Locale("ru"), "%.1f КБ", bytes / 1024.0)
    else -> String.format(Locale("ru"), "%.1f МБ", bytes / (1024.0 * 1024.0))
}
