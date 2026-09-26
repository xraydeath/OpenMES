package ru.openmes.feature.marks

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Topic
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.openmes.core.common.pluralRu
import ru.openmes.core.common.runSuspendCatching
import ru.openmes.core.common.toFullRu
import ru.openmes.core.common.toRuDate
import ru.openmes.core.data.CollegeRepository
import ru.openmes.core.data.DiaryRepository
import ru.openmes.core.data.Session
import ru.openmes.core.data.SessionRepository
import ru.openmes.core.data.SettingsRepository
import ru.openmes.core.designsystem.components.ConnectedChoiceGroup
import ru.openmes.core.designsystem.components.EmptyState
import ru.openmes.core.designsystem.components.ErrorState
import ru.openmes.core.designsystem.components.GroupGap
import ru.openmes.core.designsystem.components.LoadingState
import ru.openmes.core.designsystem.components.MarkBadge
import ru.openmes.core.designsystem.components.MesCard
import ru.openmes.core.designsystem.components.MesListItem
import ru.openmes.core.designsystem.components.MesPullToRefreshBox
import ru.openmes.core.designsystem.components.ScrollableFill
import ru.openmes.core.designsystem.components.SectionHeader
import ru.openmes.core.designsystem.components.StatusPill
import ru.openmes.core.designsystem.components.groupShape
import ru.openmes.core.designsystem.components.markShape
import ru.openmes.core.designsystem.components.markTone
import ru.openmes.core.model.FinalMarksYear
import ru.openmes.core.model.GradeBook
import ru.openmes.core.model.Mark
import ru.openmes.core.model.MarkDetails
import ru.openmes.core.model.SubjectMarks
import ru.openmes.core.model.SubjectMarksData
import ru.openmes.core.model.SubjectPeriod
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import ru.openmes.core.model.RoundingRules

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class MarksViewModel(
    private val sessionRepository: SessionRepository,
    private val diaryRepository: DiaryRepository,
    private val settingsRepository: SettingsRepository,
    private val collegeRepository: CollegeRepository,
) : ViewModel() {

    data class MarksUiState(
        /** Оценки по предметам с периодами (subject_marks). */
        val subjects: List<SubjectMarksData> = emptyList(),
        /** Оценки за последний месяц (marks by date). */
        val byDate: List<SubjectMarks> = emptyList(),
        /** Зачётная книжка. */
        val gradeBook: GradeBook? = null,
        /** Зачётку загрузить не удалось (а сохранённой нет) — вместо вечной загрузки показываем ошибку. */
        val gradeBookError: String? = null,
        /** Годовые оценки по учебным годам (портфолио); null — ещё грузятся. */
        val finalMarks: List<FinalMarksYear>? = null,
        val finalMarksError: String? = null,
        val loading: Boolean = true,
        /** Обновление по свайпу (фоновое — после показа кэша — без индикатора). */
        val refreshing: Boolean = false,
        val error: String? = null,
    )

    /** BottomSheet деталей оценки. */
    var markDetails by mutableStateOf<MarkDetails?>(null)
        private set
    var markDetailsLoading by mutableStateOf(false)
        private set

    /** BottomSheet калькулятора: предмет + период. */
    var calculator by mutableStateOf<Pair<SubjectMarksData, SubjectPeriod>?>(null)
        private set

    val calculatorKeyboard: StateFlow<Boolean> = settingsRepository.settings
        .map { it.calculatorKeyboard }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val roundingRules: StateFlow<RoundingRules> = settingsRepository.settings
        .map { it.roundingRules }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RoundingRules.STANDARD)

    fun setRoundingRules(rules: RoundingRules) = viewModelScope.launch {
        settingsRepository.setRoundingRules(rules)
    }

    private val _state = MutableStateFlow(MarksUiState())
    val state = _state.asStateFlow()

    /** Одноразовые сообщения: не удалось обновить, хотя на экране уже есть данные. */
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** Ребёнок, чьи оценки сейчас в состоянии: сменили ребёнка — всё чужое сбрасываем. */
    private var loadedChildId: String? = null
    private var loadJob: Job? = null

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
        load(childId, userInitiated = true)
    }

    private fun load(childId: String, userInitiated: Boolean) {
        if (childId != loadedChildId) {
            loadedChildId = childId
            closeMarkDetails()
            closeCalculator()
            _state.value = MarksUiState()
        }
        // Новая загрузка отменяет прежнюю: иначе опоздавший старый ответ затирал бы свежий.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, refreshing = userInitiated, error = null)
            val today = LocalDate.now()
            // Сначала — сохранённое в офлайн-кэше (мгновенно), затем свежие данные из сети.
            if (_state.value.subjects.isEmpty()) {
                diaryRepository.cachedOnly {
                    getSubjectMarks(childId) to getMarks(childId, today.minusDays(28), today)
                }?.let { (subjects, byDate) ->
                    if (_state.value.subjects.isEmpty()) _state.value = _state.value.copy(subjects = subjects, byDate = byDate)
                }
            }
            if (_state.value.gradeBook == null) {
                diaryRepository.cachedOnly { getGradeBook(childId) }?.let { gb ->
                    _state.value = _state.value.copy(gradeBook = gb)
                }
            }
            if (_state.value.finalMarks == null) {
                collegeRepository.cachedOnly { getFinalMarks(force = true) }?.let { years ->
                    _state.value = _state.value.copy(finalMarks = years)
                }
            }
            runSuspendCatching {
                val subjects = diaryRepository.getSubjectMarks(childId)
                val byDate = diaryRepository.getMarks(childId, today.minusDays(28), today)
                subjects to byDate
            }.onSuccess { (subjects, byDate) ->
                _state.value = _state.value.copy(
                    subjects = subjects,
                    byDate = byDate,
                    loading = false,
                    refreshing = false,
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(loading = false, refreshing = false, error = e.message)
                // С данными на экране полноэкранной ошибки нет — сообщаем отдельно, чтобы сбой не был тихим.
                if (_state.value.subjects.isNotEmpty()) {
                    _messages.send("Не удалось обновить оценки" + (e.message?.let { ": $it" } ?: ""))
                }
            }
            // Зачётка грузится лениво и не блокирует основной список.
            _state.value = _state.value.copy(gradeBookError = null)
            runSuspendCatching { diaryRepository.getGradeBook(childId) }
                .onSuccess { gb -> _state.value = _state.value.copy(gradeBook = gb) }
                .onFailure { e -> _state.value = _state.value.copy(gradeBookError = e.message ?: "Не удалось загрузить зачётку") }
            // Годовые оценки меняются раз в год: обновляются только по свайпу.
            _state.value = _state.value.copy(finalMarksError = null)
            runSuspendCatching { collegeRepository.getFinalMarks(force = userInitiated) }
                .onSuccess { years -> _state.value = _state.value.copy(finalMarks = years) }
                .onFailure { e -> _state.value = _state.value.copy(finalMarksError = e.message ?: "Не удалось загрузить годовые оценки") }
        }
    }

    private var detailsJob: Job? = null

    /** BottomSheet деталей оценки: открывается сразу с тем, что уже известно, детали догружаются. */
    fun openMarkDetails(mark: Mark, subjectName: String) {
        val childId = (sessionRepository.session.value as? Session.LoggedIn)?.currentChild?.id ?: return
        val markId = mark.id.toLongOrNull() ?: return
        markDetails = MarkDetails(
            id = markId, value = mark.value, subjectName = subjectName, weight = mark.weight ?: 1,
            comment = mark.comment, controlFormName = mark.typeName, teacherName = null, lessonTopic = null,
            date = mark.date, createdAt = null, criteria = emptyList(), classResults = null,
        )
        detailsJob?.cancel()
        detailsJob = viewModelScope.launch {
            markDetailsLoading = true
            runSuspendCatching { diaryRepository.getMarkDetails(childId, markId) }
                .onSuccess { if (markDetails?.id == markId) markDetails = it }
            markDetailsLoading = false
        }
    }

    fun closeMarkDetails() {
        detailsJob?.cancel()
        markDetailsLoading = false
        markDetails = null
    }

    fun openCalculator(subject: SubjectMarksData, period: SubjectPeriod) {
        calculator = subject to period
    }

    fun closeCalculator() {
        calculator = null
    }

    fun setCalculatorKeyboard(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setCalculatorKeyboard(enabled)
    }
}

// ---------------------------------------------------------------------------
// Экран: «По дате» / «По предмету» / «Зачётка» (связанная группа кнопок)
// ---------------------------------------------------------------------------

private enum class MarksTab(val title: String) {
    ByDate("По дате"),
    BySubject("Предметы"),
    GradeBook("Зачётка"),
    Annual("Годовые"),
}

private enum class SubjectSort(val title: String) {
    ByAverage("По среднему баллу"),
    ByUpdated("По дате последней оценки"),
    Alphabetical("По алфавиту А-Я"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarksScreen(viewModel: MarksViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(MarksTab.ByDate) }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    // BottomSheet деталей оценки
    viewModel.markDetails?.let { details ->
        // Свежее состояние на каждое открытие и сразу во всю высоту: без «полуоткрытой»
        // промежуточной точки свайп вниз закрывает шторку с первого раза.
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = viewModel::closeMarkDetails,
            sheetState = sheetState,
        ) {
            MarkDetailsSheet(details, loading = viewModel.markDetailsLoading)
        }
    }

    // BottomSheet калькулятора оценок
    viewModel.calculator?.let { (subject, period) ->
        val keyboard by viewModel.calculatorKeyboard.collectAsStateWithLifecycle()
        val rules by viewModel.roundingRules.collectAsStateWithLifecycle()
        ModalBottomSheet(
            onDismissRequest = viewModel::closeCalculator,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            MarkCalculator(
                subjectName = subject.subjectName,
                period = period,
                keyboardMode = keyboard,
                onKeyboardModeChange = { viewModel.setCalculatorKeyboard(it) },
                rules = rules,
                onRulesChange = { viewModel.setRoundingRules(it) },
            )
        }
    }

    MesPullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            when {
                state.error != null && state.subjects.isEmpty() ->
                    ScrollableFill { ErrorState(onRetry = viewModel::refresh, details = state.error) }

                state.loading && state.subjects.isEmpty() -> LoadingState()
                state.subjects.isEmpty() -> ScrollableFill {
                    EmptyState(
                        icon = Icons.Rounded.Star,
                        title = "Оценок пока нет",
                        subtitle = "Здесь появятся отметки по предметам",
                    )
                }

                else -> {
                    ConnectedChoiceGroup(
                        options = MarksTab.entries,
                        selected = tab,
                        onSelect = { tab = it },
                        // Без иконок: с ними четыре вкладки не помещаются на узком экране.
                        label = { it.title },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                            (slideInHorizontally { it / 6 * dir } + fadeIn()) togetherWith
                                (slideOutHorizontally { -it / 6 * dir } + fadeOut())
                        },
                        label = "marks_tab",
                    ) { current ->
                        when (current) {
                            MarksTab.ByDate -> MarksByDate(state, viewModel)
                            MarksTab.BySubject -> MarksBySubject(state, viewModel::openCalculator)
                            MarksTab.GradeBook -> GradeBookScreen(state, onRetry = viewModel::refresh)
                            MarksTab.Annual -> AnnualMarksScreen(state, onRetry = viewModel::refresh)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Вкладка «По дате»: группы по дням (слитые карточки)
// ---------------------------------------------------------------------------

@Composable
private fun MarksByDate(state: MarksViewModel.MarksUiState, viewModel: MarksViewModel) {
    val groups = remember(state.byDate) {
        state.byDate
            .filter { it.marks.isNotEmpty() }
            .flatMap { subject ->
                subject.marks.map { subject.subjectName to it }
            }
            // Оценки без даты — отдельной группой в самом конце, а не под сегодняшним днём.
            .groupBy { it.second.date }
            .toSortedMap(nullsLast(compareByDescending { it }))
    }

    if (groups.isEmpty()) {
        EmptyState(
            icon = Icons.Rounded.DateRange,
            title = "Оценок за месяц нет",
            subtitle = "Показаны оценки за последние 4 недели",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(GroupGap),
    ) {
        groups.forEach { (date, dayMarks) ->
            item(key = "day_$date") {
                SectionHeader(
                    date?.let { "${it.toRuDate()}, ${it.dayOfWeek.toFullRu()}" } ?: "Без даты",
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                    trailing = {
                        StatusPill(
                            "${dayMarks.size}",
                            icon = Icons.Rounded.Star,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
            dayMarks.forEachIndexed { index, (subjectName, mark) ->
                item(key = "${date}_$index") {
                    val open = { viewModel.openMarkDetails(mark, subjectName) }
                    MesListItem(
                        headline = subjectName,
                        supporting = mark.typeName,
                        shape = groupShape(index, dayMarks.size),
                        onClick = open,
                        leadingContent = { MarkComp(mark = mark, onClick = open) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
        item {
            Text(
                "Показаны оценки за последний месяц",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Вкладка «Предметы»: карточки + периоды внизу
// ---------------------------------------------------------------------------

@Composable
private fun MarksBySubject(
    state: MarksViewModel.MarksUiState,
    onOpenCalculator: (SubjectMarksData, SubjectPeriod) -> Unit,
) {
    // Периоды из предмета с максимумом периодов
    val periods = remember(state.subjects) {
        state.subjects
            .maxByOrNull { it.periods.size }
            ?.periods
            ?.map { it.title }
            .orEmpty()
            .distinct()
    }
    var currentPeriod by remember(periods) {
        mutableStateOf(
            state.subjects.firstOrNull()?.currentPeriod()?.title ?: periods.firstOrNull() ?: "",
        )
    }
    var finalSelected by remember { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(SubjectSort.ByAverage) }

    Column(modifier = Modifier.fillMaxSize()) {
        Crossfade(
            targetState = finalSelected,
            modifier = Modifier.weight(1f),
            label = "finals_anim",
        ) { isFinals ->
            if (!isFinals) {
                SubjectsList(state, currentPeriod, sort, onOpenCalculator)
            } else {
                FinalsTable(state)
            }
        }
        // Периоды ВНИЗУ + «Итоговые» + сортировка
        if (periods.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                    ) {
                        val options = periods + FINALS
                        options.forEachIndexed { index, title ->
                            val isFinals = title == FINALS
                            val checked = if (isFinals) finalSelected else !finalSelected && title == currentPeriod
                            ToggleButton(
                                checked = checked,
                                onCheckedChange = {
                                    if (isFinals) {
                                        finalSelected = true
                                    } else {
                                        currentPeriod = title
                                        finalSelected = false
                                    }
                                },
                                shapes = when (index) {
                                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                },
                            ) {
                                if (isFinals) {
                                    Icon(
                                        Icons.Rounded.Star,
                                        contentDescription = null,
                                        modifier = Modifier.size(ToggleButtonDefaults.IconSize),
                                    )
                                    Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                                }
                                Text(title, maxLines = 1)
                            }
                        }
                    }
                    if (!finalSelected) SortButton(sort) { sort = it }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}

private const val FINALS = "Итоговые"

@Composable
private fun SortButton(sort: SubjectSort, onSort: (SubjectSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilledTonalIconButton(onClick = { expanded = true }, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "Сортировка")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = MaterialTheme.shapes.large,
        ) {
            SubjectSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.title) },
                    onClick = {
                        onSort(option)
                        expanded = false
                    },
                    leadingIcon = { RadioButton(selected = option == sort, onClick = null) },
                )
            }
        }
    }
}

@Composable
private fun SubjectsList(
    state: MarksViewModel.MarksUiState,
    currentPeriod: String,
    sort: SubjectSort,
    onOpenCalculator: (SubjectMarksData, SubjectPeriod) -> Unit,
) {
    val subjects = remember(state.subjects, currentPeriod, sort) {
        state.subjects
            .filter { s -> s.periods.any { it.title == currentPeriod } }
            .sortedWith(
                when (sort) {
                    SubjectSort.Alphabetical -> compareBy { it.subjectName }
                    SubjectSort.ByAverage -> compareByDescending {
                        it.periods.first { p -> p.title == currentPeriod }
                            .value?.replace(',', '.')?.toDoubleOrNull() ?: -1.0
                    }

                    SubjectSort.ByUpdated -> compareByDescending { s ->
                        s.periods.firstOrNull { p -> p.title == currentPeriod }
                            ?.marks?.maxOfOrNull { it.date?.toEpochDay() ?: 0 } ?: 0
                    }
                },
            )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(subjects, key = { it.subjectId }) { subject ->
            val period = subject.periods.first { it.title == currentPeriod }
            SubjectCard(
                subject,
                period,
                onClick = { onOpenCalculator(subject, period) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun SubjectCard(
    subject: SubjectMarksData,
    period: SubjectPeriod,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MesCard(onClick = onClick, modifier = modifier, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    subject.subjectName,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${period.marks.size} ${pluralRu(period.marks.size, "оценка", "оценки", "оценок")} · калькулятор",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (period.fixedValue != null) {
                FinalChip(period.fixedValue!!)
                Spacer(Modifier.width(6.dp))
            }
            AverageChip(
                value = period.value ?: "—",
                dynamic = period.dynamic ?: subject.dynamic ?: "NONE",
            )
        }
        if (period.marks.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                // LazyRow обрезает края: запас под плашку веса, нависающую над углом.
                contentPadding = PaddingValues(top = 6.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(period.marks, key = { it.id }) { mark ->
                    MarkComp(mark = mark, onClick = onClick)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// «Итоговые»: таблица периодов × предметов
// ---------------------------------------------------------------------------

@Composable
private fun FinalsTable(state: MarksViewModel.MarksUiState) {
    val periods = remember(state.subjects) {
        state.subjects.maxByOrNull { it.periods.size }?.periods.orEmpty()
    }
    val subjects = state.subjects.filter { it.periods.isNotEmpty() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(GroupGap),
    ) {
        // Заголовок: римские цифры периодов
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Предмет",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                periods.forEachIndexed { i, _ ->
                    Box(Modifier.size(40.dp).padding(2.dp), contentAlignment = Alignment.Center) {
                        Text(romanNumeral(i + 1), style = MaterialTheme.typography.labelLargeEmphasized)
                    }
                }
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Star, contentDescription = "Год", tint = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
        itemsIndexed(subjects, key = { _, it -> "final_${it.subjectId}" }) { index, subject ->
            Surface(
                shape = groupShape(index, subjects.size),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        subject.subjectName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    periods.forEach { p ->
                        val mark = subject.periods.firstOrNull { it.title == p.title }
                        Box(Modifier.padding(2.dp)) { MarkSimple(mark?.fixedValue ?: mark?.value, size = 36.dp) }
                    }
                    Box(Modifier.padding(start = 4.dp)) { MarkSimple(subject.yearMark, size = 40.dp, emphasized = true) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Компоненты оценок: форма и тон — из дизайн-системы (markShape / markTone)
// ---------------------------------------------------------------------------

/** Плашка оценки: форма по значению, вес — плашкой поверх угла. */
@Composable
fun MarkComp(
    mark: Mark,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    MarkBadge(
        value = mark.value,
        modifier = modifier,
        large = large,
        weight = mark.weight,
        onClick = onClick,
    )
}

/** Ячейка итоговой оценки (пустая — приглушённый круг). */
@Composable
fun MarkSimple(value: String?, size: Dp = 40.dp, emphasized: Boolean = false) {
    val tone = markTone(value)
    Surface(
        modifier = Modifier.size(size),
        shape = if (emphasized && !value.isNullOrBlank()) markShape(value) else MaterialTheme.shapes.medium,
        color = tone.container,
        contentColor = tone.content,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                value ?: "",
                style = if (emphasized) MaterialTheme.typography.titleSmallEmphasized else MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}

/** Средний балл: «пилюля» с тоном по значению и стрелкой динамики. */
@Composable
fun AverageChip(value: String, dynamic: String) {
    val tone = markTone(value.replace(',', '.').toDoubleOrNull()?.roundToInt()?.toString())
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = tone.container,
        contentColor = tone.content,
    ) {
        Row(
            Modifier.padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when (dynamic) {
                    "UP" -> Icons.Rounded.ArrowDropUp
                    "DOWN" -> Icons.Rounded.ArrowDropDown
                    else -> Icons.Rounded.Remove
                },
                contentDescription = dynamic,
                modifier = Modifier.size(20.dp),
            )
            AnimatedContent(value, label = "average_anim") { v ->
                Text(v, style = MaterialTheme.typography.titleMediumEmphasized)
            }
        }
    }
}

/** Итоговая оценка за период: фигура-печенье с галочкой. */
@Composable
fun FinalChip(fixedValue: String) {
    val tone = markTone(fixedValue)
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = tone.container,
        contentColor = tone.content,
        border = BorderStroke(2.dp, tone.content.copy(alpha = 0.4f)),
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Done, contentDescription = "Итоговая", modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(fixedValue, style = MaterialTheme.typography.titleMediumEmphasized)
        }
    }
}

/** Римские цифры для заголовков периодов. */
private fun romanNumeral(n: Int): String = when (n) {
    1 -> "I"; 2 -> "II"; 3 -> "III"; 4 -> "IV"; 5 -> "V"
    6 -> "VI"; 7 -> "VII"; 8 -> "VIII"; 9 -> "IX"; 10 -> "X"
    else -> n.toString()
}

// ---------------------------------------------------------------------------
// Вкладка «Зачётка» (attestation — college-only)
// ---------------------------------------------------------------------------

@Composable
private fun GradeBookScreen(state: MarksViewModel.MarksUiState, onRetry: () -> Unit) {
    val gradeBook = state.gradeBook
    when {
        gradeBook == null && state.gradeBookError != null -> ScrollableFill {
            ErrorState(title = "Не удалось загрузить зачётку", onRetry = onRetry, details = state.gradeBookError)
        }
        gradeBook == null -> LoadingState(label = "Загружаем зачётку…")
        gradeBook.courses.isEmpty() -> EmptyState(
            icon = Icons.Rounded.WorkspacePremium,
            title = "Зачётка пуста",
            subtitle = "Здесь появятся итоговые аттестации по курсам",
        )

        else -> {
            var course by remember { mutableStateOf(gradeBook.courses.lastOrNull()?.name ?: "") }
            var semester by remember { mutableStateOf("") }
            val current = gradeBook.courses.firstOrNull { it.name == course } ?: gradeBook.courses.last()
            val semesters = current.semesters
            val sem = semesters.firstOrNull { it.name == semester } ?: semesters.lastOrNull()

            Column(Modifier.fillMaxSize()) {
                // Курсы и семестры — связанные группы сверху
                Column(
                    Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (gradeBook.courses.size > 1) {
                        ConnectedChoiceGroup(
                            options = gradeBook.courses.map { it.name },
                            selected = current.name,
                            onSelect = {
                                course = it
                                semester = ""
                            },
                            label = { it },
                        )
                    }
                    if (semesters.size > 1 && sem != null) {
                        ConnectedChoiceGroup(
                            options = semesters.map { it.name },
                            selected = sem.name,
                            onSelect = { semester = it },
                            // МЭШ нумерует семестры сквозь все курсы (2 курс — «3/4 семестр»); показываем по порядку внутри курса.
                            label = { name -> "${semesters.indexOfFirst { it.name == name } + 1} семестр" },
                        )
                    }
                }
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(GroupGap),
                ) {
                    sem?.forms?.forEach { form ->
                        if (form.subjects.isEmpty()) return@forEach
                        item(key = "form_${current.name}_${sem.name}_${form.formName}") {
                            SectionHeader(
                                formNameRu(form.formName),
                                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                            )
                        }
                        itemsIndexed(
                            form.subjects,
                            // Индекс в ключе: у практик бывают одинаковые название и пустая дата.
                            key = { i, it -> "${current.name}_${sem.name}_${form.formName}_${i}_${it.subjectName}" },
                        ) { index, subj ->
                            GradeBookCard(subj, groupShape(index, form.subjects.size))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GradeBookCard(subject: GradeBook.Subject, shape: Shape) {
    val details = listOfNotNull(
        subject.hours?.let { "$it ч." },
        subject.date?.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")),
        subject.teachers.firstOrNull(),
    ).joinToString(" · ")
    MesCard(shape = shape, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(subject.subjectName, style = MaterialTheme.typography.titleMediumEmphasized)
                if (details.isNotEmpty()) {
                    Text(
                        details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                subject.theme?.let {
                    Text(
                        "Тема: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            when {
                subject.value != null -> MarkBadge(subject.value.toString())

                subject.academicDebt -> StatusPill(
                    "Долг",
                    icon = Icons.Rounded.ErrorOutline,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )

                else -> MarkSimple(null)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Вкладка «Годовые» (portfolio final-mark): итоговые оценки за каждый учебный год
// ---------------------------------------------------------------------------

@Composable
private fun AnnualMarksScreen(state: MarksViewModel.MarksUiState, onRetry: () -> Unit) {
    val years = state.finalMarks
    when {
        years == null && state.finalMarksError != null -> ScrollableFill {
            ErrorState(title = "Не удалось загрузить годовые оценки", onRetry = onRetry, details = state.finalMarksError)
        }
        years == null -> LoadingState(label = "Загружаем годовые оценки…")
        years.isEmpty() -> ScrollableFill {
            EmptyState(
                icon = Icons.Rounded.WorkspacePremium,
                title = "Годовых оценок нет",
                subtitle = "Здесь появятся итоговые оценки за учебные годы",
            )
        }

        else -> {
            var selected by rememberSaveable { mutableStateOf(0) }
            val year = years.getOrElse(selected) { years.first() }
            Column(Modifier.fillMaxSize()) {
                if (years.size > 1) {
                    // Старые годы слева, как на шкале времени.
                    val ordered = years.indices.reversed().toList()
                    ConnectedChoiceGroup(
                        options = ordered,
                        selected = years.indexOf(year),
                        onSelect = { selected = it },
                        label = { years[it].shortTitle() },
                        fill = years.size <= 5,
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .then(if (years.size > 5) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
                    )
                }
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(GroupGap),
                ) {
                    item(key = "annual_header_${year.title}_${year.year}") {
                        SectionHeader(
                            listOfNotNull(year.title?.let { "$it учебный год" } ?: year.year?.let { "$it-й год обучения" }, year.level)
                                .joinToString(" · "),
                            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                            trailing = year.average?.let { avg ->
                                {
                                    StatusPill(
                                        "Средний ${"%.2f".format(avg).replace('.', ',')}",
                                        icon = Icons.Rounded.Star,
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    }
                    itemsIndexed(year.marks, key = { i, m -> "annual_${year.title}_${i}_${m.subject}" }) { index, mark ->
                        MesListItem(
                            headline = mark.subject,
                            supporting = mark.gradeSystem?.takeUnless { it == "Пятибалльная" },
                            shape = groupShape(index, year.marks.size),
                            trailingContent = {
                                if (mark.numeric != null) {
                                    MarkBadge(mark.numeric.toString())
                                } else {
                                    val passed = mark.value != "незачёт"
                                    StatusPill(
                                        mark.value,
                                        icon = if (passed) Icons.Rounded.Done else Icons.Rounded.ErrorOutline,
                                        containerColor = if (passed) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                                        contentColor = if (passed) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** «2023-2024» → «23/24». */
private fun FinalMarksYear.shortTitle(): String =
    title?.split('-')?.takeIf { it.size == 2 && it.all { p -> p.length == 4 } }
        ?.joinToString("/") { it.takeLast(2) }
        ?: title
        ?: year?.let { "$it год" }
        ?: "—"

private fun formNameRu(form: String): String = when (form) {
    "EXAM" -> "Экзамены"
    "TEST" -> "Дифференцированные зачёты"
    "PRACTICE" -> "Практика"
    else -> form
}

// ---------------------------------------------------------------------------
// BottomSheet деталей оценки
// ---------------------------------------------------------------------------

@Composable
private fun MarkDetailsSheet(details: MarkDetails, loading: Boolean) {
    val info = listOfNotNull(
        details.controlFormName?.let { Triple(Icons.AutoMirrored.Rounded.Assignment, "Форма контроля", it) },
        details.teacherName?.let { Triple(Icons.Rounded.Person, "Учитель", it) },
        details.date?.let {
            Triple(Icons.Rounded.DateRange, "Дата", it.toRuDate(includeYear = true))
        },
        details.lessonTopic?.let { Triple(Icons.Rounded.Topic, "Тема урока", it) },
    )

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(GroupGap),
    ) {
        // Шапка: большая оценка + предмет
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            val tone = markTone(details.value)
            Surface(Modifier.size(72.dp), shape = markShape(details.value), color = tone.container, contentColor = tone.content) {
                Box(contentAlignment = Alignment.Center) {
                    Text(details.value, style = MaterialTheme.typography.displaySmallEmphasized)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(details.subjectName, style = MaterialTheme.typography.headlineSmallEmphasized)
                if (details.weight > 1) {
                    Text(
                        "Вес ${details.weight}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (loading) LoadingIndicator(Modifier.size(40.dp))
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

        // Комментарий
        details.comment?.takeIf { it.isNotBlank() }?.let {
            SectionHeader("Комментарий", Modifier.padding(top = 8.dp))
            MesCard(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }
        }

        // Критериальные оценки
        if (details.criteria.isNotEmpty()) {
            SectionHeader("Критерии", Modifier.padding(top = 8.dp))
            details.criteria.forEachIndexed { index, c ->
                MesListItem(
                    headline = c.name,
                    shape = groupShape(index, details.criteria.size),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    trailingContent = {
                        Text(c.value, style = MaterialTheme.typography.titleMediumEmphasized)
                    },
                )
            }
        }

        // Распределение оценок класса
        details.classResults?.let { cr ->
            SectionHeader("Оценки класса · ${cr.totalStudents} чел.", Modifier.padding(top = 8.dp))
            MesCard(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                ) {
                    cr.distributions.sortedByDescending { it.value }.forEach { d ->
                        val label = d.value?.toInt()?.toString()
                        val mine = label == details.value
                        val tone = markTone(label)
                        val fraction by animateFloatAsState(
                            d.percentage.coerceIn(0, 100) / 100f,
                            animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
                            label = "bar",
                        )
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("${d.students}", style = MaterialTheme.typography.labelMedium)
                            Box(
                                Modifier
                                    .fillMaxWidth(0.7f)
                                    .height((4 + 80 * fraction).dp)
                                    .background(
                                        if (mine) MaterialTheme.colorScheme.primary else tone.container,
                                        MaterialTheme.shapes.medium,
                                    ),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                label ?: "?",
                                style = if (mine) MaterialTheme.typography.titleMediumEmphasized else MaterialTheme.typography.titleMedium,
                                color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}
