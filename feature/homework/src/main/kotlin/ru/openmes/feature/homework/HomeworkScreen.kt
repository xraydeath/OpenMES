package ru.openmes.feature.homework

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import android.os.Build
import android.content.ClipboardManager
import android.content.ClipData
import ru.openmes.core.designsystem.components.ScrollableFill
import ru.openmes.core.designsystem.components.MesPullToRefreshBox
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import ru.openmes.core.common.humanize
import ru.openmes.core.common.runSuspendCatching
import ru.openmes.core.data.DiaryRepository
import ru.openmes.core.data.Session
import ru.openmes.core.data.SessionRepository
import ru.openmes.core.designsystem.components.EmptyState
import ru.openmes.core.designsystem.components.ErrorState
import ru.openmes.core.designsystem.components.GroupGap
import ru.openmes.core.designsystem.components.LoadingState
import ru.openmes.core.designsystem.components.MesCard
import ru.openmes.core.designsystem.components.SectionHeader
import ru.openmes.core.designsystem.components.StatusPill
import ru.openmes.core.designsystem.components.groupShape
import ru.openmes.core.designsystem.components.openUrl
import ru.openmes.core.model.Homework
import ru.openmes.core.model.HomeworkMaterial
import java.time.LocalDate

class HomeworkViewModel(
    private val sessionRepository: SessionRepository,
    private val diaryRepository: DiaryRepository,
) : ViewModel() {

    data class HomeworkUiState(
        val grouped: List<Pair<LocalDate, List<Homework>>> = emptyList(),
        val loading: Boolean = true,
        /** Обновление по свайпу (фоновое — после показа кэша — без индикатора). */
        val refreshing: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(HomeworkUiState())
    val state = _state.asStateFlow()

    /** Одноразовые события: открыть ссылку / показать ошибку. */
    sealed interface Event {
        data class OpenUrl(val url: String) : Event
        /** ЭОР Библиотеки МЭШ — во встроенном браузере с сессией библиотеки. */
        data class OpenLibrary(val url: String) : Event
        data class CopyUrl(val url: String) : Event
        data class Error(val message: String) : Event
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** uuid материала, ссылка на который сейчас запрашивается. */
    var openingMaterial by mutableStateOf<String?>(null)
        private set

    init {
        sessionRepository.session
            .onEach { if (it is Session.LoggedIn) load(userInitiated = false) }
            .launchIn(viewModelScope)
    }

    fun refresh() = load(userInitiated = true)

    private fun load(userInitiated: Boolean) {
        val childId = (sessionRepository.session.value as? Session.LoggedIn)?.currentChild?.id ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, refreshing = userInitiated, error = null)
            val today = LocalDate.now()
            val from = today.minusDays(2)
            val to = today.plusDays(14)
            // Сначала — сохранённое в офлайн-кэше (мгновенно), затем свежие данные из сети.
            if (_state.value.grouped.isEmpty()) {
                diaryRepository.cachedOnly { getHomeworks(childId, from, to) }?.let { list ->
                    if (_state.value.grouped.isEmpty()) _state.value = _state.value.copy(grouped = list.grouped())
                }
            }
            runSuspendCatching {
                diaryRepository.getHomeworks(childId, from, to)
            }.onSuccess { list ->
                _state.value = HomeworkUiState(grouped = list.grouped(), loading = false)
            }.onFailure { e ->
                _state.value = _state.value.copy(loading = false, refreshing = false, error = e.message)
            }
        }
    }

    private fun List<Homework>.grouped(): List<Pair<LocalDate, List<Homework>>> =
        groupBy { it.date }
            .toSortedMap()
            .map { (date, items) -> date to items.sortedBy { it.subjectName } }

    fun toggleDone(homework: Homework) {
        val childId = (sessionRepository.session.value as? Session.LoggedIn)?.currentChild?.id ?: return
        viewModelScope.launch {
            // Оптимистичное обновление
            updateLocal(homework.id, !homework.isDone)
            runSuspendCatching {
                diaryRepository.setHomeworkDone(childId, homework.id, !homework.isDone)
            }.onFailure { updateLocal(homework.id, homework.isDone) }
        }
    }

    /** Файл открывается сразу, ЭОР — через homeworks/launch (ссылка одноразовая). */
    fun openMaterial(homework: Homework, material: HomeworkMaterial) =
        withMaterialUrl(homework, material, Event::OpenUrl, launched = Event::OpenLibrary)

    /** Долгое нажатие: прямая ссылка на файл/ЭОР — в буфер обмена. */
    fun copyMaterialLink(homework: Homework, material: HomeworkMaterial) = withMaterialUrl(homework, material, Event::CopyUrl)

    private fun withMaterialUrl(
        homework: Homework,
        material: HomeworkMaterial,
        event: (String) -> Event,
        launched: (String) -> Event = event,
    ) {
        material.fileUrl?.let {
            _events.trySend(event(it))
            return
        }
        val entryId = homework.entryId
        val uuid = material.uuid
        if (entryId == null || uuid == null) {
            _events.trySend(Event.Error("У материала нет ссылки"))
            return
        }
        if (openingMaterial != null) return
        viewModelScope.launch {
            openingMaterial = uuid
            runSuspendCatching { diaryRepository.getHomeworkMaterialUrl(entryId, uuid) }
                .onSuccess { _events.send(launched(it)) }
                .onFailure { _events.send(Event.Error(it.message ?: "Не удалось открыть материал")) }
            openingMaterial = null
        }
    }

    private fun updateLocal(homeworkId: String, isDone: Boolean) {
        _state.value = _state.value.copy(
            grouped = _state.value.grouped.map { (d, list) ->
                d to list.map { if (it.id == homeworkId) it.copy(isDone = isDone) else it }
            },
        )
    }
}

/**
 * Экран домашних заданий, сгруппированных по дням (как в оригинале),
 * с отметкой о выполнении.
 */
@Composable
fun HomeworkScreen(viewModel: HomeworkViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeworkViewModel.Event.OpenUrl -> context.openUrl(event.url)
                is HomeworkViewModel.Event.OpenLibrary -> context.openLibrary(event.url)
                is HomeworkViewModel.Event.CopyUrl -> {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Ссылка на материал", event.url))
                    // С Android 13 система сама показывает, что скопировано.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
                    }
                }
                is HomeworkViewModel.Event.Error ->
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    MesPullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            state.error != null && state.grouped.isEmpty() -> ScrollableFill { ErrorState(onRetry = viewModel::refresh, details = state.error) }
            state.loading && state.grouped.isEmpty() -> LoadingState()
            state.grouped.isEmpty() -> ScrollableFill {
                EmptyState(
                    icon = Icons.AutoMirrored.Rounded.MenuBook,
                    title = "Домашних заданий нет",
                    subtitle = "Отдыхайте!",
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(GroupGap),
            ) {
                state.grouped.forEach { (date, items) ->
                    item(key = "header_$date") {
                        val done = items.count { it.isDone }
                        SectionHeader(
                            title = date.humanize(),
                            modifier = Modifier.padding(top = 8.dp),
                            trailing = {
                                StatusPill(
                                    text = "$done из ${items.size}",
                                    containerColor = if (done == items.size) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                    contentColor = if (done == items.size) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                        )
                    }
                    itemsIndexed(items, key = { _, it -> it.id }) { index, homework ->
                        HomeworkCard(
                            homework = homework,
                            shape = groupShape(index, items.size),
                            openingMaterial = viewModel.openingMaterial,
                            onToggleDone = { viewModel.toggleDone(homework) },
                            onOpenMaterial = { viewModel.openMaterial(homework, it) },
                            onCopyMaterialLink = { viewModel.copyMaterialLink(homework, it) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeworkCard(
    homework: Homework,
    shape: Shape,
    openingMaterial: String?,
    onToggleDone: () -> Unit,
    onOpenMaterial: (HomeworkMaterial) -> Unit,
    onCopyMaterialLink: (HomeworkMaterial) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(homework.id) { mutableStateOf(false) }
    val done = homework.isDone

    MesCard(
        modifier = modifier,
        shape = shape,
        onClick = { expanded = !expanded },
        containerColor = if (done) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    homework.subjectName,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                // Развёрнутое задание можно выделить и скопировать (долгое нажатие по тексту).
                val task = @Composable {
                    Text(
                        text = homework.task,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (done && !expanded) TextDecoration.LineThrough else null,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                    )
                }
                if (expanded) SelectionContainer { task() } else task()
                if (homework.materials.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        homework.materials.forEach { material ->
                            MaterialChip(
                                material = material,
                                loading = material.uuid != null && material.uuid == openingMaterial,
                                onClick = { onOpenMaterial(material) },
                                onLongClick = { onCopyMaterialLink(material) },
                            )
                        }
                    }
                } else if (homework.materialsCount > 0) {
                    StatusPill(
                        text = "Материалов: ${homework.materialsCount}",
                        icon = Icons.Rounded.AttachFile,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            FilledTonalIconToggleButton(
                checked = done,
                onCheckedChange = { onToggleDone() },
                shapes = IconButtonDefaults.toggleableShapes(),
                colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                    checkedContainerColor = MaterialTheme.colorScheme.primary,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = if (done) "Выполнено" else "Отметить выполненным",
                )
            }
        }
    }
}

/** Чип материала: тап — открыть, долгое нажатие — скопировать ссылку (AssistChip не умеет long-click). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MaterialChip(
    material: HomeworkMaterial,
    loading: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
                onLongClickLabel = "Скопировать ссылку",
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = AssistChipDefaults.Height)
                .padding(start = 8.dp, end = 16.dp),
        ) {
            if (loading) {
                LoadingIndicator(Modifier.size(AssistChipDefaults.IconSize))
            } else {
                Icon(
                    when {
                        material.isFile -> Icons.Rounded.AttachFile
                        material.mode == "execute" -> Icons.AutoMirrored.Rounded.Assignment
                        else -> Icons.Rounded.School
                    },
                    contentDescription = material.typeName,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                material.title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
        }
    }
}
