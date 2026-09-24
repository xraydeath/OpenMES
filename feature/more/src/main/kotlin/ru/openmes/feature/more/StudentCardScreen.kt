package ru.openmes.feature.more

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.async
import ru.openmes.core.data.CollegeRepository
import ru.openmes.core.designsystem.components.MesCard
import ru.openmes.core.designsystem.components.ScrollableFill
import ru.openmes.core.designsystem.components.MesPullToRefreshBox
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.CoPresent
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Stairs
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import ru.openmes.core.data.DiaryRepository
import ru.openmes.core.data.Session
import ru.openmes.core.data.SessionRepository
import ru.openmes.core.designsystem.components.ErrorState
import ru.openmes.core.designsystem.components.LoadingState
import ru.openmes.core.designsystem.components.GroupGap
import ru.openmes.core.designsystem.components.HeroCard
import ru.openmes.core.designsystem.components.MesListItem
import ru.openmes.core.designsystem.components.SectionHeader
import ru.openmes.core.designsystem.components.ShapeIcon
import ru.openmes.core.designsystem.components.groupShape
import ru.openmes.core.model.StudentCard

class StudentCardViewModel(
    private val sessionRepository: SessionRepository,
    private val diaryRepository: DiaryRepository,
    private val collegeRepository: CollegeRepository,
) : ViewModel() {

    data class StudentCardUiState(
        val card: StudentCard? = null,
        val qr: ImageBitmap? = null,
        val loading: Boolean = true,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(StudentCardUiState())
    val state = _state.asStateFlow()

    init {
        sessionRepository.session
            .onEach { if (it is Session.LoggedIn) refresh() }
            .launchIn(viewModelScope)
    }

    fun refresh() {
        val childId = (sessionRepository.session.value as? Session.LoggedIn)?.currentChild?.id ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            // QR — отдельный POST без офлайн-кэша: его ошибка не прячет сам билет.
            val qr = async {
                runSuspendCatching {
                    val png = collegeRepository.getStudentCardQr(childId)
                    BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap()
                }.getOrNull()
            }
            runSuspendCatching { diaryRepository.getStudentCard(childId) }
                .onSuccess { _state.value = StudentCardUiState(card = it, qr = _state.value.qr, loading = false) }
                .onFailure { e -> _state.value = _state.value.copy(loading = false, error = e.message) }
            qr.await()?.let { _state.value = _state.value.copy(qr = it) }
        }
    }
}

/** Электронный студенческий билет (family/mobile/v1/student-card). */
@Composable
fun StudentCardScreen(viewModel: StudentCardViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val card = state.card

    MesPullToRefreshBox(
        isRefreshing = state.loading && card != null,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            state.loading && card == null -> LoadingState()
            card == null -> ScrollableFill {
                ErrorState(
                    title = "Студенческий билет недоступен",
                    subtitle = "Возможно, колледж ещё не выпустил электронный билет",
                    onRetry = viewModel::refresh,
                    details = state.error,
                )
            }

            else -> {
                val rows = listOf(
                    Triple(Icons.Rounded.Event, "Дата выдачи", card.issueDate),
                    Triple(Icons.Rounded.EventAvailable, "Действителен до", card.validUntil),
                    Triple(Icons.Rounded.CoPresent, "Форма обучения", card.educationForm),
                    Triple(Icons.Rounded.Stairs, "Курс", card.course),
                    Triple(Icons.Rounded.School, "Уровень образования", card.educationLevel),
                    Triple(Icons.Rounded.Work, "Специальность", card.specialty),
                    Triple(Icons.Rounded.Gavel, "Приказ о зачислении", card.enrollmentOrder),
                    Triple(Icons.Rounded.AccountBalance, "Учредитель", card.founderName),
                ).filter { !it.third.isNullOrBlank() }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(GroupGap),
                ) {
                    item { CardHero(card) }
                    state.qr?.let { qr -> item { CardQr(qr) } }
                    if (rows.isNotEmpty()) item { SectionHeader("Сведения", Modifier.padding(top = 12.dp)) }
                    itemsIndexed(rows) { index, (icon, label, value) ->
                        MesListItem(
                            headline = value.orEmpty(),
                            supporting = label,
                            icon = icon,
                            shape = groupShape(index, rows.size),
                        )
                    }
                }
            }
        }
    }
}

/** Лицевая сторона билета: крупный номер, ФИО, колледж и декоративная фигура. */
@Composable
private fun CardHero(card: StudentCard) {
    HeroCard {
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(end = 72.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "СТУДЕНЧЕСКИЙ БИЛЕТ",
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                )
                card.cardNumber?.let {
                    Text("№ $it", style = MaterialTheme.typography.headlineMediumEmphasized)
                }
            }
            ShapeIcon(
                icon = Icons.Rounded.School,
                shape = MaterialShapes.Cookie12Sided.toShape(),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                size = 64.dp,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        Text(
            card.fullName,
            style = MaterialTheme.typography.titleLargeEmphasized,
            modifier = Modifier.padding(top = 20.dp),
        )
        card.schoolName?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
        }
    }
}

/** QR для турникета/проверки: на белом фоне, чтобы читался и в тёмной теме. */
@Composable
private fun CardQr(qr: ImageBitmap) {
    MesCard(containerColor = Color.White, contentColor = Color.Black) {
        Image(
            bitmap = qr,
            contentDescription = "QR-код студенческого билета",
            filterQuality = FilterQuality.None,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .fillMaxWidth(0.7f)
                .aspectRatio(1f),
        )
        Text(
            "Покажите QR-код на входе",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
        )
    }
}
