package ru.openmes.feature.more

import ru.openmes.core.model.FoodTransaction
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
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
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
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
import ru.openmes.core.common.humanize
import ru.openmes.core.common.runSuspendCatching
import ru.openmes.core.common.toHM
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
import ru.openmes.core.designsystem.theme.Spacing
import ru.openmes.core.model.Buffet
import ru.openmes.core.model.Dish
import ru.openmes.core.model.FoodBalance
import ru.openmes.core.model.FoodComplex
import ru.openmes.core.model.FoodDay
import ru.openmes.feature.more.R
import java.time.DayOfWeek
import java.time.LocalDate
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
        /** Операции по счёту за [TRANSACTIONS_DAYS] дней; null — ещё не загружены. */
        val transactions: List<FoodTransaction>? = null,
        val transactionsFailed: Boolean = false,
        val loading: Boolean = true,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(FoodUiState())
    val state = _state.asStateFlow()

    /** Уже загруженные недели (по ребёнку и понедельнику) — возврат к ним без загрузки. */
    private val weeks = mutableMapOf<Pair<String, LocalDate>, Map<LocalDate, FoodDay>>()

    private var loadJob: Job? = null

    init {
        // Меню и баланс зависят от ребёнка: при смене всё прошлое сбрасывается.
        sessionRepository.currentChildIdChanges()
            .onEach { childId ->
                loadJob?.cancel()
                weeks.clear()
                _state.value = FoodUiState()
                if (childId != null) load(force = false)
            }
            .launchIn(viewModelScope)
    }

    private fun childId() = (sessionRepository.session.value as? Session.LoggedIn)?.currentChild?.id

    fun select(date: LocalDate) {
        _state.value = _state.value.copy(selected = date)
    }

    fun shiftWeek(weeks: Long) {
        val s = _state.value
        val monday = s.monday.plusWeeks(weeks)
        val cached = childId()?.let { this.weeks[it to monday] }
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
        val childId = childId() ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val monday = _state.value.monday
            val sunday = monday.plusDays(6)
            _state.value = _state.value.copy(loading = true, error = null)
            // Баланс и оператор — второстепенные и независимые: подставляются, как только придут,
            // меню их не ждёт.
            launch {
                runSuspendCatching { foodRepository.getBalance() }.getOrNull()
                    ?.let { _state.value = _state.value.copy(balance = it) }
            }
            if (force || _state.value.transactions == null) {
                launch {
                    val today = LocalDate.now()
                    runSuspendCatching { foodRepository.getTransactions(today.minusDays(TRANSACTIONS_DAYS - 1), today) }
                        .onSuccess { _state.value = _state.value.copy(transactions = it, transactionsFailed = false) }
                        .onFailure { _state.value = _state.value.copy(transactionsFailed = true) }
                }
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
                    weeks[childId to monday] = byDate
                    if (_state.value.monday != monday) return@onSuccess
                    _state.value = _state.value.copy(days = byDate, loading = false)
                    prefetchWeek(childId, monday.plusWeeks(1))
                }
                .onFailure { e ->
                    if (_state.value.monday == monday) {
                        _state.value = _state.value.copy(loading = false, error = e.message)
                    }
                }
        }
    }

    /** Следующая неделя — заранее, чтобы листание было без ожидания. */
    private fun prefetchWeek(childId: String, monday: LocalDate) {
        if ((childId to monday) in weeks) return
        viewModelScope.launch {
            runSuspendCatching { foodRepository.getMenu(monday, monday.plusDays(6)) }
                // Ребёнка успели сменить — меню относится к прошлому, в кэш не кладём.
                .onSuccess { days -> if (childId() == childId) weeks[childId to monday] = days.associateBy { it.date } }
        }
    }
}

private enum class FoodTab(@StringRes val title: Int) {
    Canteen(R.string.more_food_tab_canteen),
    Buffet(R.string.more_food_tab_buffet),
    Account(R.string.more_food_tab_account),
}

private const val TRANSACTIONS_DAYS = 30L

/** Пополнение счёта питания. */
private const val TOP_UP_URL = "https://newpay.mos.ru/"

/** Питание: меню комплексов столовой и буфета по дням недели, лицевой счёт и операции. */
@Composable
fun FoodScreen(viewModel: FoodViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(FoodTab.Canteen) }
    val day = state.days[state.selected]
    val uriHandler = LocalUriHandler.current
    // Содержимое LazyColumn — обычная функция, строки для неё разворачиваем здесь.
    val tabLabels = FoodTab.entries.associateWith { stringResource(it.title) }
    val otherCategory = stringResource(R.string.more_food_category_other)
    val accountRowList = accountRows(state.balance)

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
                    contentPadding = PaddingValues(start = Spacing.l, end = Spacing.l, top = Spacing.s, bottom = Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(GroupGap),
                ) {
                    item { FoodSummary(state.selected, day, state.balance, state.provider) }
                    item {
                        ConnectedChoiceGroup(
                            options = FoodTab.entries,
                            selected = tab,
                            onSelect = { tab = it },
                            label = { tabLabels.getValue(it) },
                            modifier = Modifier.padding(top = Spacing.m, bottom = Spacing.xs),
                        )
                    }
                    when (tab) {
                        FoodTab.Canteen -> canteen(day)
                        FoodTab.Buffet -> buffet(day?.buffet, otherCategory)
                        FoodTab.Account -> account(
                            rows = accountRowList,
                            transactions = state.transactions,
                            failed = state.transactionsFailed,
                            onTopUp = { runCatching { uriHandler.openUri(TOP_UP_URL) } },
                        )
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
                title = stringResource(R.string.more_food_empty_title),
                subtitle = stringResource(R.string.more_food_empty_sub),
                modifier = Modifier.padding(top = Spacing.xxl),
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

private fun LazyListScope.buffet(buffet: Buffet?, otherCategory: String) {
    if (buffet == null || buffet.dishes.isEmpty()) {
        item {
            EmptyState(
                icon = Icons.Rounded.Storefront,
                title = stringResource(R.string.more_food_buffet_empty),
                subtitle = buffet?.hoursText()?.let { stringResource(R.string.more_food_buffet_hours, it) }
                    ?: stringResource(R.string.more_food_buffet_missing),
                modifier = Modifier.padding(top = Spacing.xxl),
            )
        }
        return
    }
    buffet.dishes.groupBy { it.category ?: otherCategory }.forEach { (category, dishes) ->
        item(key = "b_$category") { SectionHeader(category, Modifier.padding(top = Spacing.m)) }
        itemsIndexed(dishes, key = { i, d -> "b_${d.id}_$i" }) { index, dish ->
            DishItem(dish, shape = groupShape(index, dishes.size), showPrice = true)
        }
    }
}

private fun LazyListScope.account(
    rows: List<Pair<String, String>>,
    transactions: List<FoodTransaction>?,
    failed: Boolean,
    onTopUp: () -> Unit,
) {
    item { SectionHeader(stringResource(R.string.more_food_account_title), Modifier.padding(top = Spacing.m)) }
    itemsIndexed(rows, key = { _, r -> "acc_${r.first}" }) { index, (title, value) ->
        MesCard(shape = groupShape(index, rows.size), contentPadding = PaddingValues(horizontal = Spacing.l, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    item {
        FilledTonalButton(
            onClick = onTopUp,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.s),
        ) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
            Text(stringResource(R.string.more_food_top_up), Modifier.padding(start = Spacing.s))
        }
    }

    item { SectionHeader(stringResource(R.string.more_food_ops_header, TRANSACTIONS_DAYS), Modifier.padding(top = Spacing.m)) }
    when {
        transactions.isNullOrEmpty() -> item {
            EmptyState(
                icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                title = when {
                    transactions != null -> stringResource(R.string.more_food_ops_empty)
                    failed -> stringResource(R.string.more_food_ops_failed)
                    else -> stringResource(R.string.more_food_ops_loading)
                },
                subtitle = if (transactions != null) stringResource(R.string.more_food_ops_empty_sub) else null,
                modifier = Modifier.padding(top = Spacing.l),
            )
        }
        else -> itemsIndexed(transactions, key = { i, _ -> "tx_$i" }) { index, tx ->
            TransactionItem(tx, groupShape(index, transactions.size))
        }
    }
}

@Composable
private fun TransactionItem(tx: FoodTransaction, shape: Shape) {
    MesCard(shape = shape, contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tx.title ?: tx.type ?: stringResource(R.string.more_food_tx_default),
                    style = MaterialTheme.typography.titleMedium,
                )
                val meta = listOfNotNull(
                    tx.date?.let { "${it.toLocalDate().humanize()}, ${it.toLocalTime().toHM()}" },
                    tx.type?.takeIf { tx.title != null },
                ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            tx.amount?.let {
                Text(
                    formatRub(it),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = Spacing.m),
                )
            }
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
                stringResource(R.string.more_food_provider, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
        }
        Row(
            Modifier.padding(top = Spacing.m),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            balance?.let {
                StatusPill(
                    text = stringResource(R.string.more_food_balance, formatRub(it.amount)),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
            day?.buffet?.hoursText()?.let {
                StatusPill(text = stringResource(R.string.more_food_buffet_pill, it), icon = Icons.Rounded.Storefront)
            }
        }
    }
}

@Composable
private fun ComplexHeader(complex: FoodComplex) {
    Column(Modifier.padding(top = Spacing.l, bottom = 6.dp, start = Spacing.xs, end = Spacing.xs)) {
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
            if (complex.isPreferential) add(stringResource(R.string.more_food_tag_preferential))
            if (complex.isPaid) add(stringResource(R.string.more_food_tag_paid))
            if (complex.preorderAllowed) add(stringResource(R.string.more_food_tag_preorder))
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
    val hasDetails = dish.ingredients != null || dish.nutrition()

    MesCard(
        onClick = if (hasDetails) ({ expanded = !expanded }) else null,
        shape = shape,
        contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(dish.name, style = MaterialTheme.typography.titleMedium)
                val meta = listOfNotNull(
                    dish.weightGrams?.let { stringResource(R.string.more_food_weight, it) },
                    dish.calories?.takeIf { it > 0 }?.let { stringResource(R.string.more_food_calories, it.roundToInt()) },
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
                    modifier = Modifier.padding(start = Spacing.m),
                )
            }
        }
        if (expanded) {
            dish.ingredients?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Spacing.s),
                )
            }
            nutritionText(dish)?.let {
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
    Column(Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { onShift(-1) }, shapes = IconButtonDefaults.shapes()) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.more_food_prev_week_cd),
                )
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
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.more_food_next_week_cd),
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
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

private fun Buffet.hoursText(): String? =
    if (openAt != null && closeAt != null) "${openAt!!.toHM()}–${closeAt!!.toHM()}" else null

/** Есть ли хоть один ненулевой БЖУ — от этого зависит, показывается ли строка «Состав». */
private fun Dish.nutrition(): Boolean =
    listOf(protein, fat, carbohydrates).any { (it ?: 0.0) > 0 }

/** Строки лицевого счёта: подписи и «не задан(о)» — ресурсами, рубли и номер — данными. */
@Composable
private fun accountRows(balance: FoodBalance?): List<Pair<String, String>> = listOfNotNull(
    balance?.contractId?.let {
        stringResource(R.string.more_food_account_title) to stringResource(R.string.more_food_account_number, it)
    },
    stringResource(R.string.more_food_day_limit) to
        (balance?.dayLimit?.let(::formatRub) ?: stringResource(R.string.more_food_not_set_masc)),
    stringResource(R.string.more_food_warn_below) to
        (balance?.lowBalanceThreshold?.let(::formatRub) ?: stringResource(R.string.more_food_not_set_fem)),
)

/** Строка БЖУ: буквы-обозначения и разделитель — ресурсами. */
@Composable
private fun nutritionText(dish: Dish): String? {
    val parts = listOfNotNull(
        dish.protein?.let { stringResource(R.string.more_food_bju_protein, it.roundToInt()) },
        dish.fat?.let { stringResource(R.string.more_food_bju_fat, it.roundToInt()) },
        dish.carbohydrates?.let { stringResource(R.string.more_food_bju_carbs, it.roundToInt()) },
    )
    return parts.takeIf { it.isNotEmpty() && dish.nutrition() }?.joinToString(" · ")
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
