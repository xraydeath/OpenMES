package ru.openmes.feature.more

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.NoFood
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import ru.openmes.core.common.toRuDate
import ru.openmes.core.common.toShortRu
import ru.openmes.core.data.FoodRepository
import ru.openmes.core.data.Session
import ru.openmes.core.data.SessionRepository
import ru.openmes.core.designsystem.components.ConnectedChoiceGroup
import ru.openmes.core.designsystem.components.EmptyState
import ru.openmes.core.designsystem.components.ErrorState
import ru.openmes.core.designsystem.components.GroupGap
import ru.openmes.core.designsystem.components.HeroCard
import ru.openmes.core.designsystem.components.LoadingState
import ru.openmes.core.designsystem.components.MesCard
import ru.openmes.core.designsystem.components.MesPullToRefreshBox
import ru.openmes.core.designsystem.components.ScrollableFill
import ru.openmes.core.designsystem.components.SectionHeader
import ru.openmes.core.designsystem.components.StatusPill
import ru.openmes.core.designsystem.components.groupShape
import ru.openmes.core.model.Buffet
import ru.openmes.core.model.Dish
import ru.openmes.core.model.FoodBalance
import ru.openmes.core.model.FoodComplex
import ru.openmes.core.model.FoodDay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class FoodViewModel(
    private val sessionRepository: SessionRepository,
    private val foodRepository: FoodRepository,
) : ViewModel() {

    data class FoodUiState(
        val monday: LocalDate = LocalDate.now().with(DayOfWeek.MONDAY),
        val selected: LocalDate = LocalDate.now(),
        val days: Map<LocalDate, FoodDay> = emptyMap(),
        val balance: FoodBalance? = null,
        /** Организация питания (оператор). */
        val provider: String? = null,
        val loading: Boolean = true,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(FoodUiState())
    val state = _state.asStateFlow()

    /** Уже загруженные недели (по понедельнику) — возврат к ним без загрузки. */
    private val weeks = mutableMapOf<LocalDate, Map<LocalDate, FoodDay>>()

    init {
        sessionRepository.session
            .onEach { if (it is Session.LoggedIn) load(force = false) }
            .launchIn(viewModelScope)
    }

    fun select(date: LocalDate) {
        _state.value = _state.value.copy(selected = date)
    }

    fun shiftWeek(weeks: Long) {
        val s = _state.value
        val monday = s.monday.plusWeeks(weeks)
        val cached = this.weeks[monday]
        _state.value = s.copy(
            monday = monday,
            selected = s.selected.plusWeeks(weeks),
            days = cached.orEmpty(),
            loading = cached == null,
            error = null,
        )
        if (cached == null) load(force = false)
    }

    /** Pull-to-refresh: мимо кэша в памяти. */
    fun refresh() = load(force = true)

    private fun load(force: Boolean) {
        viewModelScope.launch {
            val monday = _state.value.monday
            val sunday = monday.plusDays(6)
            _state.value = _state.value.copy(loading = true, error = null)
            // Баланс и оператор — второстепенные и независимые: подставляются, как только придут,
            // меню их не ждёт.
            launch {
                runSuspendCatching { foodRepository.getBalance() }.getOrNull()
                    ?.let { _state.value = _state.value.copy(balance = it) }
            }
            if (_state.value.provider == null) {
                launch {
                    runSuspendCatching { foodRepository.getProvider(force) }.getOrNull()
                        ?.let { _state.value = _state.value.copy(provider = it) }
                }
            }
            // Сначала — сохранённое меню (мгновенно), затем свежее из сети.
            if (_state.value.days.isEmpty()) {
                foodRepository.cachedOnly {
                    Triple(getMenu(monday, sunday, force = true), getBalance(), getProvider(force = true))
                }?.let { (days, balance, provider) ->
                    val s = _state.value
                    if (s.monday == monday && s.days.isEmpty()) {
                        _state.value = s.copy(
                            days = days.associateBy { it.date },
                            balance = s.balance ?: balance,
                            provider = s.provider ?: provider,
                        )
                    }
                }
            }
            runSuspendCatching { foodRepository.getMenu(monday, sunday, force) }
                .onSuccess { days ->
                    val byDate = days.associateBy { it.date }
                    weeks[monday] = byDate
                    if (_state.value.monday != monday) return@onSuccess
                    _state.value = _state.value.copy(days = byDate, loading = false)
                    prefetchWeek(monday.plusWeeks(1))
                }
                .onFailure { e ->
                    if (_state.value.monday == monday) {
                        _state.value = _state.value.copy(loading = false, error = e.message)
                    }
                }
        }
    }

    /** Следующая неделя — заранее, чтобы листание было без ожидания. */
    private fun prefetchWeek(monday: LocalDate) {
        if (monday in weeks) return
        viewModelScope.launch {
            runSuspendCatching { foodRepository.getMenu(monday, monday.plusDays(6)) }
                .onSuccess { days -> weeks[monday] = days.associateBy { it.date } }
        }
    }
}

private enum class FoodTab(val title: String) {
    Canteen("Столовая"),
    Buffet("Буфет"),
}

/** Питание: меню комплексов столовой и буфета по дням недели, баланс счёта. */
@Composable
fun FoodScreen(viewModel: FoodViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(FoodTab.Canteen) }
    val day = state.days[state.selected]

    Column(Modifier.fillMaxSize()) {
        FoodWeekBar(
            monday = state.monday,
            selected = state.selected,
            hasMenu = { it in state.days },
            onSelect = viewModel::select,
            onShift = viewModel::shiftWeek,
        )
        MesPullToRefreshBox(
            isRefreshing = state.loading && state.days.isNotEmpty(),
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                // Ошибка сети не прячет уже показанное (сохранённое) меню.
                state.error != null && state.days.isEmpty() -> ScrollableFill { ErrorState(onRetry = viewModel::refresh, details = state.error) }
                state.loading && state.days.isEmpty() -> LoadingState()
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(GroupGap),
                ) {
                    item { FoodSummary(state.selected, day, state.balance, state.provider) }
                    item {
                        ConnectedChoiceGroup(
                            options = FoodTab.entries,
                            selected = tab,
                            onSelect = { tab = it },
                            label = { it.title },
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    when (tab) {
                        FoodTab.Canteen -> canteen(day)
                        FoodTab.Buffet -> buffet(day?.buffet)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.canteen(day: FoodDay?) {
    val complexes = day?.complexes.orEmpty()
    if (complexes.isEmpty()) {
        item {
            EmptyState(
                icon = Icons.Rounded.NoFood,
                title = "Меню нет",
                subtitle = "На этот день комплексы не опубликованы",
                modifier = Modifier.padding(top = 32.dp),
            )
        }
        return
    }
    complexes.forEach { complex ->
        item(key = "c_${complex.id}") { ComplexHeader(complex) }
        itemsIndexed(complex.dishes, key = { i, d -> "c_${complex.id}_${d.id}_$i" }) { index, dish ->
            DishItem(dish, shape = groupShape(index, complex.dishes.size), showPrice = false)
        }
    }
}

private fun LazyListScope.buffet(buffet: Buffet?) {
    if (buffet == null || buffet.dishes.isEmpty()) {
        item {
            EmptyState(
                icon = Icons.Rounded.Storefront,
                title = "Ассортимент не опубликован",
                subtitle = buffet?.hoursText()?.let { "Буфет работает $it" } ?: "Буфет на этот день не найден",
                modifier = Modifier.padding(top = 32.dp),
            )
        }
        return
    }
    buffet.dishes.groupBy { it.category ?: "Прочее" }.forEach { (category, dishes) ->
        item(key = "b_$category") { SectionHeader(category, Modifier.padding(top = 12.dp)) }
        itemsIndexed(dishes, key = { i, d -> "b_${d.id}_$i" }) { index, dish ->
            DishItem(dish, shape = groupShape(index, dishes.size), showPrice = true)
        }
    }
}

@Composable
private fun FoodSummary(date: LocalDate, day: FoodDay?, balance: FoodBalance?, provider: String?) {
    HeroCard {
        Text(date.humanize(), style = MaterialTheme.typography.titleMediumEmphasized)
        day?.organization?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
        }
        provider?.let {
            Text(
                "Питание: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
        }
        Row(
            Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            balance?.let {
                StatusPill(
                    text = "Баланс ${formatRub(it.amount)}",
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
            day?.buffet?.hoursText()?.let {
                StatusPill(text = "Буфет $it", icon = Icons.Rounded.Storefront)
            }
        }
    }
}

@Composable
private fun ComplexHeader(complex: FoodComplex) {
    Column(Modifier.padding(top = 16.dp, bottom = 6.dp, start = 4.dp, end = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                complex.kind?.title ?: complex.name,
                style = MaterialTheme.typography.titleMediumEmphasized,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatRub(complex.price),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val tags = buildList {
            if (complex.kind != null && complex.name.isNotBlank()) add(complex.name)
            if (complex.isPreferential) add("льготное")
            if (complex.isPaid) add("платное")
            if (complex.preorderAllowed) add("предзаказ")
        }
        if (tags.isNotEmpty()) {
            Text(
                tags.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Блюдо: название, вес/калорийность; по нажатию — состав и БЖУ. */
@Composable
private fun DishItem(dish: Dish, shape: Shape, showPrice: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val hasDetails = dish.ingredients != null || dish.nutrition() != null
    MesCard(
        onClick = if (hasDetails) ({ expanded = !expanded }) else null,
        shape = shape,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(dish.name, style = MaterialTheme.typography.titleMedium)
                val meta = listOfNotNull(
                    dish.weightGrams?.let { "$it г" },
                    dish.calories?.takeIf { it > 0 }?.let { "${it.roundToInt()} ккал" },
                ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (showPrice && dish.price > 0) {
                Text(
                    formatRub(dish.price),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        if (expanded) {
            dish.ingredients?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            dish.nutrition()?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun FoodWeekBar(
    monday: LocalDate,
    selected: LocalDate,
    hasMenu: (LocalDate) -> Boolean,
    onSelect: (LocalDate) -> Unit,
    onShift: (Long) -> Unit,
) {
    val today = LocalDate.now()
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { onShift(-1) }, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Предыдущая неделя")
            }
            AnimatedContent(weekTitle(monday), label = "food_week", modifier = Modifier.weight(1f)) { title ->
                Text(
                    title,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            FilledTonalIconButton(onClick = { onShift(1) }, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Следующая неделя")
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(7) { i ->
                val date = monday.plusDays(i.toLong())
                val isSelected = date == selected
                val colors = MaterialTheme.colorScheme
                Surface(
                    onClick = { onSelect(date) },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = if (isSelected) MaterialTheme.shapes.large else MaterialTheme.shapes.extraLarge,
                    color = when {
                        isSelected -> colors.primary
                        date == today -> colors.primaryContainer
                        else -> colors.surfaceContainer
                    },
                    contentColor = when {
                        isSelected -> colors.onPrimary
                        date == today -> colors.onPrimaryContainer
                        hasMenu(date) -> colors.onSurface
                        else -> colors.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(date.dayOfWeek.toShortRu(), style = MaterialTheme.typography.labelSmall)
                        Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

private val hoursFormat = DateTimeFormatter.ofPattern("HH:mm")

private fun Buffet.hoursText(): String? =
    if (openAt != null && closeAt != null) "${openAt!!.format(hoursFormat)}–${closeAt!!.format(hoursFormat)}" else null

private fun Dish.nutrition(): String? {
    val parts = listOfNotNull(
        protein?.let { "Б ${it.roundToInt()}" },
        fat?.let { "Ж ${it.roundToInt()}" },
        carbohydrates?.let { "У ${it.roundToInt()}" },
    )
    return parts.takeIf { list -> list.isNotEmpty() && listOf(protein, fat, carbohydrates).any { (it ?: 0.0) > 0 } }
        ?.joinToString(" · ")
}

private fun formatRub(amount: Double): String =
    String.format(Locale.forLanguageTag("ru"), "%.2f ₽", amount).replace(",00 ₽", " ₽")

/** «21–27 сентября» / «29 сентября – 5 октября». */
private fun weekTitle(monday: LocalDate): String {
    val sunday = monday.plusDays(6)
    return if (monday.month == sunday.month) {
        "${monday.dayOfMonth}–${sunday.toRuDate()}"
    } else {
        "${monday.toRuDate()} – ${sunday.toRuDate()}"
    }
}
