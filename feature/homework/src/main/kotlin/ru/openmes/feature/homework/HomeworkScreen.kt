package ru.openmes.feature.homework

import androidx.annotation.StringRes
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
import android.content.Context
import ru.openmes.core.designsystem.components.ScrollableFill
import ru.openmes.core.designsystem.components.MesPullToRefreshBox
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import ru.openmes.core.designsystem.components.mesDockReservedHeight
import ru.openmes.core.designsystem.components.mesFadeTop
import ru.openmes.core.designsystem.components.MesCard
import ru.openmes.core.designsystem.components.SectionHeader
import ru.openmes.core.designsystem.components.StatusPill
import ru.openmes.core.designsystem.components.groupShape
import ru.openmes.core.designsystem.components.mesSnackbar
import ru.openmes.core.designsystem.components.openUrl
import ru.openmes.core.model.Homework
import ru.openmes.core.model.HomeworkMaterial
import java.time.DayOfWeek
import java.time.LocalDate
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.update
import ru.openmes.core.common.weekStart
import ru.openmes.core.common.toFullRu
import ru.openmes.core.designsystem.components.WeekBar
import ru.openmes.core.designsystem.components.rememberDayPager
import ru.openmes.core.designsystem.components.rememberShortSwipeFling
import ru.openmes.core.designsystem.theme.Spacing
import ru.openmes.feature.homework.R

class HomeworkViewModel(
    private val sessionRepository: SessionRepository,
    private val diaryRepository: DiaryRepository,
) : ViewModel() {

    /** Ошибка загрузки недели: ресурс строки и, если есть, деталь от сервера. */
    data class LoadError(@StringRes val textRes: Int, val detail: String? = null)

    data class HomeworkUiState(
        val selectedDate: LocalDate = LocalDate.now(),
        /** Задания по неделям: понедельник → задания недели. */
        val weeks: Map<LocalDate, List<Homework>> = emptyMap(),
        /** Недели, которые сейчас грузятся. */
        val loadingWeeks: Set<LocalDate> = emptySet(),
        /** Обновление по свайпу (фоновое — после показа кэша — без индикатора). */
        val refreshing: Boolean = false,
        /** Ошибки загрузки по неделям. */
        val errors: Map<LocalDate, LoadError> = emptyMap(),
    ) {
        /** Задания дня, по предмету. */
        fun homeworksFor(date: LocalDate): List<Homework> =
            weeks[date.weekStart()].orEmpty().filter { it.date == date }.sortedBy { it.subjectName }

        fun isLoaded(date: LocalDate) = date.weekStart() in weeks

        fun isLoading(date: LocalDate) = date.weekStart() in loadingWeeks

        fun errorFor(date: LocalDate) = errors[date.weekStart()]
    }

    private val _state = MutableStateFlow(HomeworkUiState())
    val state = _state.asStateFlow()

    /** Одноразовые события: открыть ссылку / показать ошибку. */
    sealed interface Event {
        data class OpenUrl(val url: String) : Event
        /** ЭОР Библиотеки МЭШ — во встроенном браузере с сессией библиотеки. */
        data class OpenLibrary(val url: String) : Event
        data class CopyUrl(val url: String) : Event
        /** Ошибка: ресурс строки и, если есть, деталь от сервера. */
        data class Error(@StringRes val textRes: Int, val detail: String? = null) : Event
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** uuid материала, ссылка на который сейчас запрашивается. */
    var openingMaterial by mutableStateOf<String?>(null)
        private set

    /** Ребёнок, чьи задания сейчас в состоянии: сменили ребёнка — всё чужое сбрасываем. */
    private var loadedChildId: String? = null
    /** Загрузки по неделям: новая загрузка недели отменяет прежнюю. */
    private val loadJobs = mutableMapOf<LocalDate, Job>()

    /**
     * Отметки «выполнено», ещё не подтверждённые сервером (id → желаемое значение):
     * пришедший в это время список (кэш или сеть) не должен откатить оптимистичное значение.
     */
    private val pendingDone = mutableMapOf<String, Boolean>()
    /** Последнее известное серверу значение отметок из [pendingDone] — к нему откатываемся при ошибке. */
    private val confirmedDone = mutableMapOf<String, Boolean>()
    /** Номер последнего переключения по id: ответ на более старый запрос итог не решает. */
    private val pendingSeq = mutableMapOf<String, Int>()
    private var toggleSeq = 0
    /** Запросы отметок — по одному: при двойном тапе последним на сервер уходит последнее значение. */
    private val toggleMutex = Mutex()

    init {
        // Перезагрузка — только при смене ребёнка (или входе), а не на каждое обновление сессии.
        sessionRepository.session
            .map { (it as? Session.LoggedIn)?.currentChild?.id }
            .distinctUntilChanged()
            .onEach { childId -> if (childId != null) load(childId, userInitiated = false) }
            .launchIn(viewModelScope)
    }

    fun refresh() {
        val childId = (sessionRepository.session.value as? Session.LoggedIn)?.currentChild?.id ?: return
        load(childId, _state.value.selectedDate.weekStart(), userInitiated = true)
    }

    fun selectDate(date: LocalDate) {
        if (_state.value.selectedDate == date) return
        _state.update { it.copy(selectedDate = date) }
        val childId = loadedChildId ?: return
        val monday = date.weekStart()
        if (monday !in _state.value.weeks && monday !in loadJobs) load(childId, monday, userInitiated = false)
    }

    private fun load(childId: String, userInitiated: Boolean) {
        if (childId != loadedChildId) {
            loadedChildId = childId
            loadJobs.values.forEach { it.cancel() }
            loadJobs.clear()
            pendingDone.clear()
            confirmedDone.clear()
            pendingSeq.clear()
            _state.update { HomeworkUiState(selectedDate = it.selectedDate) }
        }
        load(childId, _state.value.selectedDate.weekStart(), userInitiated)
    }

    private fun load(childId: String, monday: LocalDate, userInitiated: Boolean) {
        // Новая загрузка отменяет прежнюю: иначе опоздавший старый ответ затирал бы свежий.
        loadJobs.remove(monday)?.cancel()
        val job = viewModelScope.launch {
            _state.update {
                it.copy(
                    loadingWeeks = it.loadingWeeks + monday,
                    refreshing = userInitiated || it.refreshing,
                    errors = it.errors - monday,
                )
            }
            val sunday = monday.plusDays(6)
            // Сначала — сохранённое в офлайн-кэше (мгновенно), затем свежие данные из сети.
            if (monday !in _state.value.weeks) {
                diaryRepository.cachedOnly { getHomeworks(childId, monday, sunday) }?.let { list ->
                    _state.update { if (monday in it.weeks) it else it.copy(weeks = it.weeks + (monday to list.withPending())) }
                }
            }
            runSuspendCatching {
                diaryRepository.getHomeworks(childId, monday, sunday)
            }.onSuccess { list ->
                _state.update { it.copy(weeks = it.weeks + (monday to list.withPending())) }
            }.onFailure { e ->
                _state.update {
                    it.copy(errors = it.errors + (monday to LoadError(R.string.homework_load_failed, e.message)))
                }
                // С данными на экране полноэкранной ошибки нет — сообщаем отдельно, чтобы сбой не был тихим.
                if (monday in _state.value.weeks && userInitiated) {
                    _events.send(Event.Error(R.string.homework_refresh_failed, e.message))
                }
            }
            _state.update { it.copy(loadingWeeks = it.loadingWeeks - monday, refreshing = it.refreshing && !userInitiated) }
        }
        loadJobs[monday] = job
        job.invokeOnCompletion { if (loadJobs[monday] === job) loadJobs.remove(monday) }
    }

    /** Неподтверждённые отметки поверх пришедших с сервера. */
    private fun List<Homework>.withPending(): List<Homework> =
        map { hw -> pendingDone[hw.id]?.let { hw.copy(isDone = it) } ?: hw }

    fun toggleDone(homeworkId: String) {
        val childId = loadedChildId ?: return
        // Текущее значение — из состояния, а не из снимка композиции: двойной тап не шлёт одно и то же.
        val current = _state.value.weeks.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.id == homeworkId } } ?: return
        val target = !current.isDone
        // Оптимистичное обновление
        if (homeworkId !in pendingDone) confirmedDone[homeworkId] = current.isDone
        pendingDone[homeworkId] = target
        val seq = ++toggleSeq
        pendingSeq[homeworkId] = seq
        updateLocal(homeworkId, target)
        viewModelScope.launch {
            val result = toggleMutex.withLock {
                runSuspendCatching { diaryRepository.setHomeworkDone(childId, homeworkId, target) }
            }
            if (loadedChildId != childId) return@launch
            if (result.isSuccess) confirmedDone[homeworkId] = target
            // Пока шёл запрос, отметку переключили снова — решит более новый запрос.
            if (pendingSeq[homeworkId] != seq) return@launch
            pendingSeq -= homeworkId
            pendingDone -= homeworkId
            val confirmed = confirmedDone.remove(homeworkId)
            result.onFailure { e ->
                updateLocal(homeworkId, confirmed ?: !target)
                _events.send(Event.Error(R.string.homework_save_done_failed, e.message))
            }
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
            _events.trySend(Event.Error(R.string.homework_material_no_url))
            return
        }
        if (openingMaterial != null) return
        viewModelScope.launch {
            openingMaterial = uuid
            runSuspendCatching { diaryRepository.getHomeworkMaterialUrl(entryId, uuid) }
                .onSuccess { _events.send(launched(it)) }
                .onFailure { _events.send(Event.Error(R.string.homework_material_open_failed, it.message)) }
            openingMaterial = null
        }
    }

    private fun updateLocal(homeworkId: String, isDone: Boolean) {
        _state.update { st ->
            st.copy(weeks = st.weeks.mapValues { (_, list) -> list.map { if (it.id == homeworkId) it.copy(isDone = isDone) else it } })
        }
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
                        .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.homework_material_link_clip), event.url))
                    // С Android 13 система сама показывает, что скопировано.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        // Внутри LaunchedEffect composable-вызовов нет — берём текст через Context.
                        mesSnackbar.show(context.getString(R.string.homework_link_copied))
                    }
                }
                // Внутри LaunchedEffect composable-вызовов нет — берём текст через Context.
                is HomeworkViewModel.Event.Error ->
                    mesSnackbar.show(errorText(context, event.textRes, event.detail), duration = SnackbarDuration.Long)
            }
        }
    }

    val today = remember { LocalDate.now() }
    val dayPager = rememberDayPager(state.selectedDate, viewModel::selectDate)
    val colors = MaterialTheme.colorScheme

    MesPullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize()) {
            WeekBar(
                firstMonday = dayPager.firstMonday,
                selected = dayPager.highlightedDate,
                today = today,
                dayOff = { it.dayOfWeek == DayOfWeek.SUNDAY },
                onSelect = viewModel::selectDate,
                // Точка — есть невыполненное задание, бледная — всё сделано.
                marker = { date ->
                    val items = state.homeworksFor(date)
                    when {
                        items.isEmpty() -> null
                        items.any { !it.isDone } -> colors.primary
                        else -> colors.outlineVariant
                    }
                },
            )
            HorizontalPager(
                state = dayPager.pagerState,
                flingBehavior = rememberShortSwipeFling(dayPager.pagerState),
                // mesFadeTop на пейджере, а не на списке внутри страницы: полоса тогда
                // не уезжает вместе со страницей при свайпе по дням.
                modifier = Modifier
                    .fillMaxSize()
                    .mesFadeTop(),
            ) { page ->
                val date = dayPager.days[page]
                val items = state.homeworksFor(date)
                val error = state.errorFor(date)
                when {
                    !state.isLoaded(date) && error != null ->
                        ScrollableFill {
                            ErrorState(
                                onRetry = viewModel::refresh,
                                details = errorText(context, error.textRes, error.detail),
                            )
                        }
                    !state.isLoaded(date) -> LoadingState()
                    items.isEmpty() -> Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.l),
                    ) {
                        DayHeader(date, items)
                        EmptyState(
                            icon = Icons.AutoMirrored.Rounded.MenuBook,
                            title = stringResource(R.string.homework_empty_title),
                            subtitle = stringResource(R.string.homework_empty_subtitle),
                        )
                    }

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = Spacing.l,
                            end = Spacing.l,
                            bottom = Spacing.xl + mesDockReservedHeight(),
                        ),
                        verticalArrangement = Arrangement.spacedBy(GroupGap),
                    ) {
                        item(key = "header") { DayHeader(date, items) }
                        itemsIndexed(items, key = { _, it -> it.id }) { index, homework ->
                            HomeworkCard(
                                homework = homework,
                                shape = groupShape(index, items.size),
                                openingMaterial = viewModel.openingMaterial,
                                onToggleDone = { viewModel.toggleDone(homework.id) },
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
}

/** «Сегодня, пятница» / «29 сентября, понедельник» и счётчик «2 из 3». */
@Composable
private fun DayHeader(date: LocalDate, items: List<Homework>) {
    val done = items.count { it.isDone }
    val allDone = items.isNotEmpty() && done == items.size
    SectionHeader(
        title = stringResource(R.string.homework_day_title, date.humanize(), date.dayOfWeek.toFullRu()),
        modifier = Modifier.padding(top = Spacing.s),
        trailing = {
            if (items.isNotEmpty()) {
                StatusPill(
                    text = stringResource(R.string.homework_done_counter, done, items.size),
                    containerColor = if (allDone) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (allDone) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/**
 * Текст ошибки: строка [textRes] сама по себе, а с деталью от сети/сервера —
 * одной строкой через [R.string.homework_error_detail].
 */
private fun errorText(context: Context, @StringRes textRes: Int, detail: String?): String =
    detail?.let { context.getString(R.string.homework_error_detail, context.getString(textRes), it) }
        ?: context.getString(textRes)


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
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
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
                            .padding(top = Spacing.xxs),
                    )
                }
                if (expanded) SelectionContainer { task() } else task()
                if (homework.materials.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = Spacing.s),
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
                        text = stringResource(R.string.homework_materials_count, homework.materialsCount),
                        icon = Icons.Rounded.AttachFile,
                        modifier = Modifier.padding(top = Spacing.s),
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
                    contentDescription = stringResource(
                        if (done) R.string.homework_done else R.string.homework_mark_done_cd
                    ),
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
                onLongClickLabel = stringResource(R.string.homework_copy_link_label),
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = AssistChipDefaults.Height)
                .padding(start = Spacing.s, end = Spacing.l),
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
            Spacer(Modifier.width(Spacing.s))
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
