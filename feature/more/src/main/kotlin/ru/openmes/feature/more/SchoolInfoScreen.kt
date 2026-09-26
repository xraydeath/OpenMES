package ru.openmes.feature.more

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Apartment
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.SupervisorAccount
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import ru.openmes.core.common.runSuspendCatching
import ru.openmes.core.data.CollegeRepository
import ru.openmes.core.data.SessionRepository
import ru.openmes.core.designsystem.components.ErrorState
import ru.openmes.core.designsystem.components.GroupGap
import ru.openmes.core.designsystem.components.HeroCard
import ru.openmes.core.designsystem.components.LoadingState
import ru.openmes.core.designsystem.components.MesListItem
import ru.openmes.core.designsystem.components.MesPullToRefreshBox
import ru.openmes.core.designsystem.components.ScrollableFill
import ru.openmes.core.designsystem.components.SectionHeader
import ru.openmes.core.designsystem.components.StatusPill
import ru.openmes.core.designsystem.components.groupShape
import ru.openmes.core.designsystem.components.openUrl
import ru.openmes.core.model.SchoolInfo

class SchoolInfoViewModel(
    sessionRepository: SessionRepository,
    private val collegeRepository: CollegeRepository,
) : ViewModel() {

    data class SchoolInfoUiState(
        val info: SchoolInfo? = null,
        val loading: Boolean = true,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(SchoolInfoUiState())
    val state = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        // Смена ребёнка: прошлая загрузка отменяется, сведения о чужом колледже не показываются.
        sessionRepository.currentChildIdChanges()
            .onEach { childId ->
                loadJob?.cancel()
                _state.value = SchoolInfoUiState()
                if (childId != null) refresh(force = false)
            }
            .launchIn(viewModelScope)
    }

    fun refresh(force: Boolean = true) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            // Сначала — сохранённое (мгновенно), затем свежее из сети.
            if (_state.value.info == null) {
                collegeRepository.cachedOnly { getSchoolInfo(force = true) }?.let { info ->
                    if (_state.value.info == null) _state.value = _state.value.copy(info = info)
                }
            }
            runSuspendCatching { collegeRepository.getSchoolInfo(force) }
                .onSuccess { _state.value = SchoolInfoUiState(info = it, loading = false) }
                .onFailure { e -> _state.value = _state.value.copy(loading = false, error = e.message) }
        }
    }
}

private data class InfoRow(val icon: ImageVector, val headline: String, val supporting: String, val onClick: (() -> Unit)? = null)

/** О колледже (school_info): руководство, кураторы группы, контакты и корпуса. */
@Composable
fun SchoolInfoScreen(viewModel: SchoolInfoViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val info = state.info
    val context = LocalContext.current

    MesPullToRefreshBox(
        isRefreshing = state.loading && info != null,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            state.loading && info == null -> LoadingState()
            info == null -> ScrollableFill {
                ErrorState(title = "Сведения о колледже недоступны", onRetry = viewModel::refresh, details = state.error)
            }

            else -> {
                fun dial(uri: String) = runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                val people = buildList {
                    info.principal?.let { add(InfoRow(Icons.Rounded.Person, it, "Директор")) }
                    info.curators.forEach { add(InfoRow(Icons.Rounded.SupervisorAccount, it, "Куратор группы")) }
                }
                val contacts = buildList {
                    info.address?.let { a ->
                        add(InfoRow(Icons.Rounded.Place, a, "Адрес") { dial("geo:0,0?q=" + Uri.encode(a)) })
                    }
                    info.phone?.let { p ->
                        add(InfoRow(Icons.Rounded.Call, p, "Телефон") { dial("tel:" + p.filter { it.isDigit() || it == '+' }) })
                    }
                    info.email?.let { e -> add(InfoRow(Icons.Rounded.Email, e, "Почта") { dial("mailto:$e") }) }
                    info.website?.let { w ->
                        add(InfoRow(Icons.Rounded.Language, w, "Сайт") {
                            context.openUrl(if (w.startsWith("http")) w else "https://$w")
                        })
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(GroupGap),
                ) {
                    item {
                        HeroCard {
                            Text(info.name, style = MaterialTheme.typography.titleLargeEmphasized)
                        }
                    }
                    infoGroup("Люди", people)
                    infoGroup("Контакты", contacts)
                    if (info.branches.isNotEmpty()) {
                        item { SectionHeader("Корпуса · ${info.branches.size}", Modifier.padding(top = 12.dp)) }
                        itemsIndexed(info.branches) { index, b ->
                            MesListItem(
                                headline = b.name.ifBlank { b.address.orEmpty() },
                                supporting = b.address?.takeIf { b.name.isNotBlank() },
                                icon = if (b.isMain) Icons.Rounded.AccountBalance else Icons.Rounded.Apartment,
                                iconShape = if (b.isStudentBuilding) MaterialShapes.Cookie9Sided.toShape() else MaterialShapes.Cookie6Sided.toShape(),
                                iconContainerColor = if (b.isStudentBuilding) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                                iconContentColor = if (b.isStudentBuilding) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                                trailingContent = when {
                                    b.isStudentBuilding -> ({ StatusPill("мой") })
                                    b.isMain -> ({ StatusPill("главный") })
                                    else -> null
                                },
                                onClick = b.address?.let { a -> { dial("geo:0,0?q=" + Uri.encode(a)) } },
                                shape = groupShape(index, info.branches.size),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.infoGroup(title: String, rows: List<InfoRow>) {
    if (rows.isEmpty()) return
    item { SectionHeader(title, Modifier.padding(top = 12.dp)) }
    itemsIndexed(rows) { index, row ->
        MesListItem(
            headline = row.headline,
            supporting = row.supporting,
            icon = row.icon,
            onClick = row.onClick,
            shape = groupShape(index, rows.size),
        )
    }
}
