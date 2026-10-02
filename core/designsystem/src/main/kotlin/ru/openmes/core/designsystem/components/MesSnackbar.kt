@file:OptIn(ExperimentalMaterial3Api::class)

package ru.openmes.core.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import ru.openmes.core.designsystem.theme.Spacing

/** Сообщение для [MesSnackbar]: текст, необязательное действие и как долго его держать. */
@Stable
data class MesMessage(
    val text: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val duration: SnackbarDuration = SnackbarDuration.Short,
)

/**
 * Шина сообщений приложения.
 *
 * Экраны шлют сообщения сюда, а [MesSnackbarHost] в корневом `Scaffold` показывает их.
 * Нужен потому, что экраны лежат в разных модулях и не должны прокидывать колбэки
 * показа сообщения через навигацию: вместо этого подписка одна на всё приложение.
 *
 * Сообщения, показанные до появления хоста, не теряются — буфер безграничный,
 * иначе раннее сообщение (например, ошибка загрузки на старте) исчезло бы молча.
 */
@Stable
class MesSnackbarBus internal constructor() {
    private val messages = MutableSharedFlow<MesMessage>(
        replay = 8,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /** Сообщения, которые host должен показать. Только для чтения. */
    val flow: SharedFlow<MesMessage> = messages

    /** Показать [message]; вызов безопасен из любого потока и не блокирует его. */
    fun show(message: MesMessage) {
        if (!messages.tryEmit(message)) {
            // Буфер переполнен (килл-зона редких сообщений) — отдаём в отдельную корутину.
            scope?.launch { messages.emit(message) }
        }
    }

    fun show(
        text: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        duration: SnackbarDuration = SnackbarDuration.Short,
    ) = show(MesMessage(text, actionLabel, onAction, duration))

    private var scope: CoroutineScope? = null

    internal fun bind(scope: CoroutineScope) {
        this.scope = scope
    }
}

/** Шина сообщений на всё приложение. */
val mesSnackbar = MesSnackbarBus()

/**
 * Хост snackbar для корневого `Scaffold`.
 *
 * Ставится один раз на всё приложение; `Scaffold` сам разводит его с нижней
 * панелью навигации через insets. Плашка выравнивается по левому краю и не
 * растягивается на всю ширину — так она не перекрывает контент целиком.
 */
@Composable
fun MesSnackbarHost(
    modifier: Modifier = Modifier,
    bus: MesSnackbarBus = mesSnackbar,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(bus) { bus.bind(scope) }

    LaunchedEffect(bus, snackbarHostState) {
        bus.flow.collect { message ->
            val result = snackbarHostState.showSnackbar(
                message = message.text,
                actionLabel = message.actionLabel,
                withDismissAction = message.actionLabel != null,
                duration = message.duration,
            )
            if (result == SnackbarResult.ActionPerformed) message.onAction?.invoke()
        }
    }

    Box(modifier.fillMaxSize()) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = modifier
                .align(Alignment.BottomStart)
                .systemBarsPadding()
                .padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.s),
        ) { data ->
            Snackbar(
                snackbarData = data,
                shape = MaterialTheme.shapes.extraLarge,
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                actionColor = MaterialTheme.colorScheme.inversePrimary,
            )
        }
    }
}
