package ru.openmes.feature.more

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.openmes.core.data.ApiConsoleRepository
import ru.openmes.core.data.ApiConsoleResponse
import ru.openmes.core.designsystem.components.HeroCard
import ru.openmes.core.designsystem.components.SectionHeader
import ru.openmes.core.designsystem.components.StatusPill
import java.io.File

/** Готовые запросы: финансы и то, для чего ещё нет экранов. */
private val presets = listOf(
    "Транзакции питания" to "api/food/meals/v3/transactions?personId={personGuid}&from={monthAgo}&to={today}",
    "Лимиты трат" to "api/food/meals/v3/clients/expense-constraints?personId={personGuid}",
    "Баланс" to "api/food/meals/v3/clients/balance?clientIds=[{\"personId\":\"{personGuid}\"}]",
    "История платежей" to "api/family/web/v1/payments/history?contract_id={contractId}",
    "Договоры" to "api/contract/payments/v1/contracts/search?personId={personGuid}",
    "Долги" to "api/ej/core/family/v1/academic_debts?student_id={studentId}",
    "Итоговые оценки" to "api/family/mobile/v1/final_marks?student_id={studentId}",
    "Рейтинг" to "api/ej/rating/v1/rank/rankShort?personId={personGuid}&beginDate={monthAgo}&endDate={today}",
)

/** Разведка пишет в logcat только начало ответа — сотня запросов не должна вытеснить буфер. */
private const val PROBE_LOG_CHARS = 1500

/** Больше на экран не выводим — длинный Text тормозит; целиком уходит в «Поделиться». */
private const val SHOWN_CHARS = 60_000

data class ApiConsoleUiState(
    val path: String = presets.first().second,
    val loading: Boolean = false,
    val response: ApiConsoleResponse? = null,
    val error: String? = null,
    /** Прогресс разведки: сколько запросов уже отправлено (null — не идёт). */
    val probeDone: Int? = null,
)

class ApiConsoleViewModel(private val repository: ApiConsoleRepository) : ViewModel() {
    private val _state = MutableStateFlow(ApiConsoleUiState())
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun setPath(path: String) = _state.update { it.copy(path = path) }

    /** Все [probes] подряд; ответы и ошибки — в logcat (OpenMES-API), на экране — последний. */
    fun runProbes() {
        job?.cancel()
        job = viewModelScope.launch {
            probes.forEachIndexed { i, path ->
                _state.update { it.copy(path = path, loading = true, error = null, probeDone = i) }
                runCatching { repository.get(path, logLimit = PROBE_LOG_CHARS) }
                    .onSuccess { r -> _state.update { it.copy(response = r) } }
                    .onFailure { e -> _state.update { it.copy(response = null, error = e.toString()) } }
            }
            _state.update { it.copy(loading = false, probeDone = null) }
        }
    }

    fun send() {
        val path = _state.value.path.takeIf { it.isNotBlank() } ?: return
        job?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            runCatching { repository.get(path) }
                .onSuccess { r -> _state.update { it.copy(loading = false, response = r) } }
                .onFailure { e -> _state.update { it.copy(loading = false, response = null, error = e.toString()) } }
        }
    }
}

@Composable
fun ApiConsoleScreen(viewModel: ApiConsoleViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Запросы идут от вашего аккаунта. В ответах могут быть личные данные — " +
                    "перед отправкой кому-либо просмотрите их.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            OutlinedTextField(
                value = state.path,
                onValueChange = viewModel::setPath,
                label = { Text("Путь от school.mos.ru/") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { viewModel.send() }),
                supportingText = { Text("«!» в начале — без роутинга колледжа. {personGuid} {studentId} {profileId} {contractId} {today} {monday} {sunday} {monthAgo}") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { (title, path) ->
                    SuggestionChip(onClick = { viewModel.setPath(path) }, label = { Text(title) })
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = viewModel::send, enabled = !state.loading) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null)
                    Text("Отправить", Modifier.padding(start = 8.dp))
                }
                FilledTonalButton(onClick = viewModel::runProbes, enabled = !state.loading) {
                    Text("Разведка (${probes.size})")
                }
                if (state.loading) LoadingIndicator()
            }
        }
        state.probeDone?.let { done ->
            item { Text("Разведка: ${done + 1} из ${probes.size}", style = MaterialTheme.typography.bodyMedium) }
        }
        state.error?.let { error ->
            item {
                HeroCard(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    SelectionContainer { Text(error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        state.response?.let { response ->
            item {
                SectionHeader(
                    "Ответ",
                    trailing = {
                        val ok = response.code in 200..299
                        StatusPill(
                            "${response.code} · ${response.elapsedMillis} мс",
                            containerColor = if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                            contentColor = if (ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                        )
                    },
                )
            }
            item {
                SelectionContainer {
                    Text(response.url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { context.copyText(response) }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                        Text("Копировать", Modifier.padding(start = 8.dp))
                    }
                    FilledTonalButton(onClick = { context.shareResponse(state.path, response) }) {
                        Icon(Icons.Rounded.Share, contentDescription = null)
                        Text("Поделиться", Modifier.padding(start = 8.dp))
                    }
                }
            }
            item {
                HeroCard(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    val body = response.body
                    SelectionContainer(Modifier.horizontalScroll(rememberScrollState())) {
                        Text(
                            body.take(SHOWN_CHARS).ifEmpty { "(пустое тело)" },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                        )
                    }
                    if (body.length > SHOWN_CHARS) {
                        Text(
                            "…показано ${SHOWN_CHARS / 1000} тыс. из ${body.length / 1000} тыс. символов — целиком через «Поделиться»",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun ApiConsoleResponse.asText() = "GET $url\n$code · $elapsedMillis мс\n\n$body"

private fun Context.copyText(response: ApiConsoleResponse) {
    val text = response.asText()
    // Буфер обмена идёт через binder (~1 МБ) — огромные ответы только через файл.
    if (text.length > 400_000) {
        Toast.makeText(this, "Ответ слишком большой — используйте «Поделиться»", Toast.LENGTH_LONG).show()
        return
    }
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("API", text))
}

private fun Context.shareResponse(path: String, response: ApiConsoleResponse) {
    val name = path.substringBefore('?').trim('/').replace(Regex("[^A-Za-z0-9_-]+"), "_").takeLast(60)
    val file = File(cacheDir, "exports/api-$name.txt").apply {
        parentFile?.mkdirs()
        writeText(response.asText())
    }
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, "Ответ API"))
}
