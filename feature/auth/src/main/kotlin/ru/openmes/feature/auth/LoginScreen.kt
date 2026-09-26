package ru.openmes.feature.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.openmes.core.common.runSuspendCatching
import ru.openmes.core.data.Session
import ru.openmes.core.data.SessionRepository
import ru.openmes.core.designsystem.components.GroupGap
import ru.openmes.core.designsystem.components.MesCard
import ru.openmes.core.designsystem.components.MesListItem
import ru.openmes.core.designsystem.components.groupShape

/**
 * ViewModel входа: готовит OAuth-клиент и ссылку, экран открывает её в браузере
 * (Custom Tabs), после входа браузер возвращает в приложение по deeplink
 * diary-po://oauth2redirect.
 */
class LoginViewModel(
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    data class State(
        val preparing: Boolean = false,
        val launchUrl: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val session: StateFlow<Session> = sessionRepository.session

    /** Ошибка прошлой попытки скрыта, пока идёт новая; новое состояние сессии показывает её снова. */
    private val authErrorHidden = MutableStateFlow(false)

    /** Причина последней неудачной авторизации (например, «state mismatch»). */
    val authError: StateFlow<String?> = combine(session, authErrorHidden) { s, hidden ->
        (s as? Session.LoggedOut)?.error?.takeUnless { hidden }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, (session.value as? Session.LoggedOut)?.error)

    init {
        session.onEach { authErrorHidden.value = false }.launchIn(viewModelScope)
    }

    fun startLogin() {
        if (_state.value.preparing) return
        authErrorHidden.value = true
        viewModelScope.launch {
            _state.value = State(preparing = true)
            runSuspendCatching {
                sessionRepository.ensureOAuthClient()
                sessionRepository.buildLoginUrl()
            }.onSuccess { url ->
                _state.value = State(launchUrl = url)
            }.onFailure { e ->
                _state.value = State(error = e.message ?: "Не удалось подготовить вход")
            }
        }
    }

    fun consumeLaunch() {
        if (_state.value.launchUrl != null) _state.value = State()
    }

    /** Ссылку нечем открыть. */
    fun onBrowserMissing() {
        _state.value = State(error = "Нет браузера, чтобы открыть страницу входа mos.ru. Установите браузер и попробуйте снова")
    }

    fun consumeError() {
        if (_state.value.error != null) _state.value = State()
    }
}

/**
 * Экран входа: авторизация в браузере через mos.ru, как в оригинале.
 */
@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onOpenSource: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val authError by viewModel.authError.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Готова ссылка — открываем браузер один раз.
    LaunchedEffect(state.launchUrl) {
        val url = state.launchUrl ?: return@LaunchedEffect
        if (openInBrowser(context, url)) viewModel.consumeLaunch() else viewModel.onBrowserMissing()
    }

    val errorText = state.error ?: authError?.takeUnless { state.preparing }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(64.dp))

        Text(
            text = "OpenMES",
            style = MaterialTheme.typography.headlineLargeEmphasized,
        )
        Text(
            text = "Открытый клиент «Колледжа МЭШ»",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        val features = listOf(
            Icons.AutoMirrored.Rounded.EventNote to "Расписание уроков и каникул",
            Icons.Rounded.School to "Оценки, ДЗ и зачётная книжка",
            Icons.Rounded.TaskAlt to "Отметки о выполнении",
            Icons.Rounded.AutoAwesome to "Material You и expressive-дизайн",
        )
        Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
            features.forEachIndexed { index, (icon, text) ->
                MesListItem(
                    headline = text,
                    icon = icon,
                    iconShape = featureShapes[index].toShape(),
                    iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = groupShape(index, features.size),
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = {
                viewModel.consumeError()
                viewModel.startLogin()
            },
            enabled = !state.preparing,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ButtonDefaults.MediumContainerHeight),
            shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
            contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
        ) {
            if (state.preparing) {
                LoadingIndicator(
                    modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.size(12.dp))
                Text("Готовим вход…", style = MaterialTheme.typography.titleMedium)
            } else {
                Icon(
                    Icons.AutoMirrored.Rounded.Login,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.MediumContainerHeight)),
                )
                Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(ButtonDefaults.MediumContainerHeight)))
                Text(
                    text = "Войти через mos.ru",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        if (errorText != null) {
            Spacer(Modifier.height(12.dp))
            MesCard(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "Откроется официальный портал login.mos.ru.\n" +
                "После входа браузер вернёт вас в приложение.\n" +
                "OpenMES не видит и не хранит ваш пароль.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        TextButton(onClick = onOpenSource, shapes = ButtonDefaults.shapes()) {
            Text("Исходный код · GitHub")
        }

        Spacer(Modifier.height(32.dp))
    }
}

/** Custom Tabs с фолбэком на обычный браузер. @return false, если открыть нечем. */
private fun openInBrowser(context: Context, url: String): Boolean {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, uri)
        return true
    } catch (_: Exception) {
        // Нет Custom Tabs-провайдера (или он упал) — обычный браузер.
    }
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/** Фигуры иконок в списке возможностей. */
private val featureShapes = listOf(
    MaterialShapes.Cookie6Sided,
    MaterialShapes.Sunny,
    MaterialShapes.Clover4Leaf,
    MaterialShapes.Cookie9Sided,
)
