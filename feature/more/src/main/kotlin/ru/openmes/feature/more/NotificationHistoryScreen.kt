package ru.openmes.feature.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AlarmOn
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Grade
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import ru.openmes.core.common.humanize
import ru.openmes.core.data.NotificationHistory
import ru.openmes.core.data.NotificationRecord
import ru.openmes.core.data.day
import ru.openmes.core.data.eventDay
import ru.openmes.core.data.timeHm
import ru.openmes.core.designsystem.components.EmptyState
import ru.openmes.core.designsystem.components.GroupGap
import ru.openmes.core.designsystem.components.mesDockReservedHeight
import ru.openmes.core.designsystem.components.MesCard
import ru.openmes.core.designsystem.components.MesListItem
import ru.openmes.core.designsystem.components.SectionHeader
import ru.openmes.core.designsystem.components.ShapeIcon
import ru.openmes.core.designsystem.components.groupShape
import ru.openmes.core.designsystem.theme.Spacing

/**
 * Высота плавающей кнопки очистки вместе с полями вокруг неё — столько снизу оставляет
 * список, чтобы последняя запись не уезжала под кнопку.
 */
private val ClearHistoryFabHeight = 80.dp

class NotificationHistoryViewModel(
    private val history: NotificationHistory,
) : ViewModel() {
    val entries: StateFlow<List<NotificationRecord>> = history.entries

    fun markSeen() = history.markSeen()

    fun clear() = history.clear()
}

/**
 * История уведомлений: всё, что приложение показывало (оценки, напоминания о парах,
 * ДЗ, изменения расписания), сгруппировано по дням. Новые сверху. По нажатию на запись
 * открывается шторка с деталями: предмет, преподаватель, день события, оценка.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationHistoryScreen(
    viewModel: NotificationHistoryViewModel,
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()

    // Открыли историю — непросмотренных больше нет (гасит бейдж колокольчика).
    LaunchedEffect(Unit) { viewModel.markSeen() }

    // Запись, чьи детали открыты в шторке (null — шторка закрыта).
    var selected by remember { mutableStateOf<NotificationRecord?>(null) }

    if (entries.isEmpty()) {
        EmptyState(
            icon = Icons.Rounded.Notifications,
            title = stringResource(R.string.more_history_empty),
            subtitle = stringResource(R.string.more_history_empty_sub),
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    val byDay = entries.asReversed().groupBy { it.day() }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Снизу место под плавающую кнопку и под док, иначе последняя запись
            // уезжает под обоих.
            contentPadding = PaddingValues(
                start = Spacing.l,
                end = Spacing.l,
                top = Spacing.s,
                bottom = mesDockReservedHeight() + ClearHistoryFabHeight,
            ),
            verticalArrangement = Arrangement.spacedBy(GroupGap),
        ) {
            byDay.forEach { (day, dayEntries) ->
                item(key = "day_$day") { SectionHeader(day.humanize()) }
                itemsIndexed(dayEntries, key = { _, record -> record.timeMillis }) { index, record ->
                    MesListItem(
                        headline = record.title.ifBlank { stringResource(R.string.more_history_default_title) },
                        supporting = listOf(record.timeHm(), record.text).filter { it.isNotBlank() }
                            .joinToString(" · "),
                        icon = channelIcon(record.channelId),
                        iconShape = channelShape(record.channelId),
                        shape = groupShape(index, dayEntries.size),
                        onClick = { selected = record },
                    )
                }
            }
        }

        // Очистка — плавающей кнопкой, а не строкой в конце списка: действие разрушающее и
        // требует внимания, а внизу списка оно терялось среди карточек. Плюс остаётся на
        // виду, когда история длинная и до конца не домотать.
        ExtendedFloatingActionButton(
            onClick = viewModel::clear,
            icon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
            text = { Text(stringResource(R.string.more_history_clear)) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Spacing.l)
                // История теперь живёт в доке, поэтому снизу надо место и под него.
                .padding(bottom = mesDockReservedHeight()),
        )
    }

    selected?.let { record ->
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            // Свои ручки вместо стандартной: у той есть «нажимная» анимация, как у кнопки.
            dragHandle = { SheetHandle() },
        ) {
            RecordSheet(record)
        }
    }
}

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

/** Детали записи истории: предмет, преподаватель, день события и оценка, если известны. */
@Composable
private fun RecordSheet(record: NotificationRecord) {
    val details = record.details
    // Подписи строк — ресурсами: список собирается до отрисовки.
    val info = listOfNotNull(
        record.eventDay()?.let { Triple(Icons.Rounded.Event, R.string.more_history_day, it.humanize()) },
        details?.teacher?.let { Triple(Icons.Rounded.Person, R.string.more_history_teacher, it) },
        details?.mark?.let { Triple(Icons.Rounded.Grade, R.string.more_history_mark, it) },
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

    // Текст уведомления можно выделить и скопировать.
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
                    icon = channelIcon(record.channelId),
                    shape = channelShape(record.channelId),
                    size = 56.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        details?.subject ?: record.title.ifBlank { stringResource(R.string.more_history_default_title) },
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                    )
                    Text(
                        stringResource(
                            R.string.more_history_received,
                            record.day().humanize().lowercase(),
                            record.timeHm(),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            info.forEachIndexed { index, (icon, label, value) ->
                MesListItem(
                    headline = value,
                    supporting = stringResource(label),
                    icon = icon,
                    shape = groupShape(index, info.size),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
            }

            if (record.text.isNotBlank()) {
                MesCard(
                    modifier = Modifier.padding(top = Spacing.xs),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(record.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** Иконка по каналу (id — из Notifications.Channel в модуле приложения). */
private fun channelIcon(channelId: String): ImageVector = when (channelId) {
    "data_update" -> Icons.Rounded.Grade
    "lessons" -> Icons.Rounded.AlarmOn
    "schedule_changes" -> Icons.Rounded.EditCalendar
    "evening" -> Icons.AutoMirrored.Rounded.MenuBook
    "homework" -> Icons.Rounded.Assignment
    else -> Icons.Rounded.Notifications
}

/** Фигура иконки — своя на канал, для ритма. */
@Composable
private fun channelShape(channelId: String) = when (channelId) {
    "data_update" -> MaterialShapes.Cookie6Sided
    "lessons" -> MaterialShapes.Sunny
    "schedule_changes" -> MaterialShapes.Cookie9Sided
    "homework" -> MaterialShapes.Pill
    else -> MaterialShapes.Clover4Leaf
}.toShape()
