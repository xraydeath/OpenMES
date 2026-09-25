package ru.openmes.feature.schedule

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.openmes.core.common.runSuspendCatching
import ru.openmes.core.data.DiaryRepository
import ru.openmes.core.data.Session
import ru.openmes.core.data.SessionRepository
import ru.openmes.core.model.DayKind
import ru.openmes.core.model.Lesson
import ru.openmes.core.model.LessonDetails
import java.time.LocalDate
import java.time.YearMonth

/**
 * Расписание с помесячной загрузкой и кэшем (паттерн OctoDiary-kt ScheduleScreen):
 * события тянутся одним запросом на месяц и кэшируются в [ScheduleUiState.months].
 */
class ScheduleViewModel(
    private val sessionRepository: SessionRepository,
    private val diaryRepository: DiaryRepository,
) : ViewModel() {

    data class ScheduleUiState(
        val selectedDate: LocalDate = LocalDate.now(),
        /** Кэш событий по месяцам. */
        val months: Map<YearMonth, List<Lesson>> = emptyMap(),
        /** Идёт загрузка хотя бы одного месяца. */
        val loading: Boolean = false,
        /** Ручное обновление (pull-to-refresh) — только оно крутит индикатор сверху. */
        val refreshing: Boolean = false,
        val error: String? = null,
        /** Индекс по дням: пересчитывается только при обновлении кэша (см. [withMonths]), а не на каждую страницу. */
        val byDate: Map<LocalDate, List<Lesson>> = emptyMap(),
        /** Календарь дневника: праздники/каникулы. Пусто — ещё не загружен (тогда красное только воскресенье). */
        val dayKinds: Map<LocalDate, DayKind> = emptyMap(),
    ) {
        fun dayKind(date: LocalDate): DayKind = dayKinds[date]
            ?: if (date.dayOfWeek == java.time.DayOfWeek.SUNDAY) DayKind.HOLIDAY else DayKind.WORKDAY

        fun lessonsFor(date: LocalDate): List<Lesson> = byDate[date].orEmpty()

        fun withMonths(months: Map<YearMonth, List<Lesson>>) = copy(
            months = months,
            byDate = months.values.flatten().groupBy { it.date }.mapValues { (_, v) -> v.sortedBy { it.startTime } },
        )
    }

    private val _state = MutableStateFlow(ScheduleUiState())

    private var loadedChildId: String? = null

    /** Месяцы, которые сейчас грузятся: без этого свайп по дням слал одинаковые запросы параллельно. */
    private val inFlight = mutableSetOf<YearMonth>()

    val state = _state.asStateFlow()

    /** BottomSheet деталей урока. */
    var lessonDetails by mutableStateOf<LessonDetails?>(null)
        private set
    var lessonDetailsLoading by mutableStateOf(false)
        private set

    /**
     * Детали уроков, подгруженные заранее (преподаватель, кабинет, статус болезни/освобождения):
     * шторка открывается уже полной, а у карточки урока виден значок статуса.
     */
    private val _detailsById = mutableStateMapOf<Long, LessonDetails>()
    val detailsById: Map<Long, LessonDetails> get() = _detailsById
    private val detailsInFlight = mutableSetOf<Long>()
    private val detailsFailed = mutableSetOf<Long>()
    /** Не больше двух запросов деталей разом — не заваливаем МЭШ. */
    private val detailsPermits = Semaphore(2)

    init {
        sessionRepository.session
            .onEach { session ->
                if (session !is Session.LoggedIn) return@onEach
                // Сменили ребёнка — кэш месяцев чужой, начинаем заново.
                val childId = session.currentChild?.id
                if (childId != loadedChildId) {
                    loadedChildId = childId
                    inFlight.clear()
                    _detailsById.clear()
                    detailsInFlight.clear()
                    detailsFailed.clear()
                    _state.update { it.withMonths(emptyMap()).copy(error = null, dayKinds = emptyMap()) }
                    loadDayKinds(childId)
                }
                ensureMonth(YearMonth.from(_state.value.selectedDate))
            }
            .launchIn(viewModelScope)
    }

    /**
     * Возврат приложения из фона: ViewModel пережила сворачивание, поэтому тихо перезапрашиваем
     * текущий месяц. Первый ON_START — это сам запуск, его покрывает init.
     */
    private val foregroundObserver = object : DefaultLifecycleObserver {
        private var started = false
        private var refreshedAt = 0L

        override fun onStart(owner: LifecycleOwner) {
            val now = SystemClock.elapsedRealtime()
            if (!started || now - refreshedAt > FOREGROUND_REFRESH_GAP_MS) {
                if (started) loadMonth(YearMonth.from(_state.value.selectedDate), force = true)
                started = true
                refreshedAt = now
            }
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(foregroundObserver)
    }

    private var detailsJob: Job? = null

    /** Календарь праздников и каникул на текущий учебный год (сентябрь–август) плюс запас. */
    private fun loadDayKinds(childId: String?) {
        childId ?: return
        val today = LocalDate.now()
        val yearStart = LocalDate.of(if (today.monthValue >= 9) today.year else today.year - 1, 9, 1)
        val yearEnd = yearStart.plusYears(1).minusDays(1)
        viewModelScope.launch {
            // Сначала сохранённый календарь (мгновенно), затем свежий из сети.
            diaryRepository.cachedOnly { getDayKinds(childId, yearStart, yearEnd) }?.let { kinds ->
                if (loadedChildId == childId && _state.value.dayKinds.isEmpty()) _state.update { it.copy(dayKinds = kinds) }
            }
            runSuspendCatching { diaryRepository.getDayKinds(childId, yearStart, yearEnd) }
                .onSuccess { kinds -> if (loadedChildId == childId) _state.update { it.copy(dayKinds = kinds) } }
        }
    }

    /**
     * Открыть детали урока: шторка показывается сразу с данными из расписания,
     * полные детали (корпус, комментарий, статус здоровья) догружаются поверх.
     */
    fun openLessonDetails(lesson: Lesson) {
        val childId = (sessionRepository.session.value as? Session.LoggedIn)?.currentChild?.id ?: return
        val lessonId = lesson.id.toLongOrNull() ?: return
        detailsJob?.cancel()
        // Уже подгружено заранее — показываем сразу полностью, без догрузки.
        _detailsById[lessonId]?.let {
            lessonDetails = it
            lessonDetailsLoading = false
            return
        }
        lessonDetails = LessonDetails(
            id = lessonId,
            subjectName = lesson.subjectName,
            date = lesson.date,
            beginTime = lesson.startTime,
            endTime = lesson.endTime,
            room = lesson.room,
            building = null,
            teacherName = lesson.teacherName,
            comment = null,
            diseaseStatusType = null,
            homework = lesson.homework?.task,
            homeworkDone = lesson.homework?.isDone == true,
            marks = lesson.marks,
        )
        detailsJob = viewModelScope.launch {
            lessonDetailsLoading = true
            runSuspendCatching { diaryRepository.getLessonDetails(childId, lessonId) }
                .onSuccess {
                    _detailsById[lessonId] = it
                    // Шторку могли закрыть, пока шёл запрос, — тогда не открываем заново.
                    if (lessonDetails?.id == lessonId) lessonDetails = it
                }
            lessonDetailsLoading = false
        }
    }

    fun closeLessonDetails() {
        detailsJob?.cancel()
        lessonDetailsLoading = false
        lessonDetails = null
    }

    fun selectDate(date: LocalDate) {
        if (_state.value.selectedDate == date) return
        _state.update { it.copy(selectedDate = date) }
        ensureMonth(YearMonth.from(date))
        // Неделя у края месяца захватывает соседний — подгружаем его заранее.
        if (date.dayOfMonth <= 7) ensureMonth(YearMonth.from(date).minusMonths(1))
        if (date.dayOfMonth > date.lengthOfMonth() - 7) ensureMonth(YearMonth.from(date).plusMonths(1))
        prefetchDetails()
    }

    /** Подгрузка деталей уроков выбранного дня, затем соседних (их видно при свайпе). */
    private fun prefetchDetails(force: Boolean = false) {
        val childId = loadedChildId ?: return
        val selected = _state.value.selectedDate
        if (force) detailsFailed.clear()
        val ids = listOf(selected, selected.plusDays(1), selected.minusDays(1))
            .flatMap { _state.value.lessonsFor(it) }
            .mapNotNull { it.id.toLongOrNull() }
            .filter { it !in detailsInFlight && it !in detailsFailed && (force || it !in _detailsById) }
        ids.forEach { lessonId ->
            detailsInFlight += lessonId
            viewModelScope.launch {
                // Сохранённые детали видны сразу, сеть их затем обновит.
                if (lessonId !in _detailsById) {
                    diaryRepository.cachedOnly { getLessonDetails(childId, lessonId) }?.let {
                        if (loadedChildId == childId && lessonId !in _detailsById) _detailsById[lessonId] = it
                    }
                }
                detailsPermits.withPermit {
                    runSuspendCatching { diaryRepository.getLessonDetails(childId, lessonId) }
                        .onSuccess { if (loadedChildId == childId) _detailsById[lessonId] = it }
                        .onFailure { detailsFailed += lessonId }
                }
                detailsInFlight -= lessonId
            }
        }
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        refreshDetails = true
        loadMonth(YearMonth.from(_state.value.selectedDate), force = true)
    }

    /** После ручного обновления перезапросить и детали уроков. */
    private var refreshDetails = false

    private fun ensureMonth(month: YearMonth) {
        if (_state.value.months.containsKey(month)) return
        loadMonth(month)
    }

    private fun loadMonth(month: YearMonth, force: Boolean = false) {
        val childId = (sessionRepository.session.value as? Session.LoggedIn)?.currentChild?.id
        if (childId == null || month in inFlight || (!force && _state.value.months.containsKey(month))) {
            _state.update { it.copy(refreshing = it.refreshing && inFlight.isNotEmpty()) }
            return
        }
        inFlight += month
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            // Холодный старт: сначала месяц из офлайн-кэша (мгновенно), сеть обновит его ниже.
            if (!_state.value.months.containsKey(month)) {
                diaryRepository.cachedOnly { getMonth(childId, month) }?.let { cached ->
                    if (loadedChildId == childId && !_state.value.months.containsKey(month)) {
                        _state.update { it.withMonths(it.months + (month to cached)) }
                        prefetchDetails()
                    }
                }
            }
            val lessons = runSuspendCatching { diaryRepository.getMonth(childId, month) }.getOrNull()

            if (loadedChildId != childId) return@launch
            inFlight -= month
            val idle = inFlight.isEmpty()
            _state.update { st ->
                if (lessons != null) {
                    st.withMonths(st.months + (month to lessons)).copy(loading = !idle, refreshing = st.refreshing && !idle)
                } else if (month == YearMonth.from(st.selectedDate)) {
                    st.copy(
                        loading = !idle,
                        refreshing = st.refreshing && !idle,
                        error = "Сервер МЭШ временно недоступен — потяни вниз для повтора",
                    )
                } else {
                    // Не удалась только предзагрузка соседнего месяца — экран не ломаем.
                    st.copy(loading = !idle, refreshing = st.refreshing && !idle)
                }
            }
            if (lessons != null) {
                prefetchDetails(force = refreshDetails && month == YearMonth.from(_state.value.selectedDate))
                if (month == YearMonth.from(_state.value.selectedDate)) refreshDetails = false
            }
        }
    }
}

/**
 * Месяц может быть тяжёлым для eventcalendar (503 под нагрузкой):
 * фолбэк — грузим двумя половинами месяца.
 */
private suspend fun DiaryRepository.getMonth(childId: String, month: YearMonth): List<Lesson> =
    runSuspendCatching { getSchedule(childId, month.atDay(1), month.atEndOfMonth()) }
        .recoverCatching {
            val mid = month.atDay(15)
            getSchedule(childId, month.atDay(1), mid) + getSchedule(childId, mid.plusDays(1), month.atEndOfMonth())
        }
        .getOrThrow()

/** Быстрое переключение между приложениями не должно каждый раз дёргать МЭШ. */
private const val FOREGROUND_REFRESH_GAP_MS = 30_000L
