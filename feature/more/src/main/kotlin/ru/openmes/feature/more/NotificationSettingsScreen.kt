package ru.openmes.feature.more

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AlarmOn
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.MeetingRoom
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VideoCall
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.openmes.core.data.AppSettings
import ru.openmes.core.data.SettingsRepository
import ru.openmes.core.designsystem.components.ConnectedChoiceGroup
import ru.openmes.core.designsystem.components.GroupGap
import ru.openmes.core.designsystem.components.MesCard
import ru.openmes.core.designsystem.components.MesListItem
import ru.openmes.core.designsystem.components.SectionHeader
import ru.openmes.core.designsystem.components.groupShape

class NotificationSettingsViewModel(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private fun update(block: suspend SettingsRepository.() -> Unit) = viewModelScope.launch { settingsRepository.block() }

    fun setMarks(enabled: Boolean) = update { setMarksNotifications(enabled) }
    fun setHideMarkValues(enabled: Boolean) = update { setHideMarkValues(enabled) }
    fun setLessons(enabled: Boolean) = update { setLessonReminders(enabled) }
    fun setLessonMinutes(minutes: Int) = update { setLessonReminderMinutes(minutes) }
    fun setDistanceOnly(enabled: Boolean) = update { setLessonRemindersDistanceOnly(enabled) }
    fun setHomework(enabled: Boolean) = update { setHomeworkReminders(enabled) }
    fun setTests(enabled: Boolean) = update { setTestReminders(enabled) }
    fun setEveningHour(hour: Int) = update { setEveningReminderHour(hour) }
    fun setScheduleChanges(enabled: Boolean) = update { setScheduleChangeNotifications(enabled) }
    fun setScheduleChangesDays(days: Int) = update { setScheduleChangesDays(days) }
    fun setScheduleChangesRooms(enabled: Boolean) = update { setScheduleChangesRooms(enabled) }
}

/**
 * Уведомления: новые оценки, начало пар, вечером — ДЗ и контрольные на завтра.
 * [onPreviewLesson] показывает пример напоминания о паре, [onCheckEvening] — запускает вечернюю проверку сейчас.
 */
@Composable
fun NotificationSettingsScreen(
    viewModel: NotificationSettingsViewModel,
    onPreviewLesson: () -> Unit,
    onCheckEvening: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permitted by remember { mutableStateOf(context.notificationsPermitted()) }
    var exactAlarms by remember { mutableStateOf(context.exactAlarmsAllowed()) }
    // Возврат из системных настроек: разрешения могли поменяться.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permitted = context.notificationsPermitted()
        exactAlarms = context.exactAlarmsAllowed()
    }

    // Android 13+: без разрешения уведомления молча не показываются. Включение ждёт ответа на запрос.
    // Что включить после ответа — ключом, чтобы пережить поворот, пока открыт системный диалог.
    var pending by rememberSaveable { mutableStateOf<PendingEnable?>(null) }
    var rationaleBefore by rememberSaveable { mutableStateOf(false) }
    // Отказ показывает плашку «Уведомления запрещены», даже если ничего ещё не включено.
    var denied by rememberSaveable { mutableStateOf(false) }
    fun apply(target: PendingEnable) = when (target) {
        PendingEnable.Marks -> viewModel.setMarks(true)
        PendingEnable.Lessons -> viewModel.setLessons(true)
        PendingEnable.Homework -> viewModel.setHomework(true)
        PendingEnable.Tests -> viewModel.setTests(true)
        PendingEnable.ScheduleChanges -> viewModel.setScheduleChanges(true)
        PendingEnable.PreviewLesson -> onPreviewLesson()
        PendingEnable.CheckEvening -> {
            onCheckEvening()
            Toast.makeText(context, "Проверяем ДЗ и контрольные на завтра…", Toast.LENGTH_SHORT).show()
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        val target = pending
        pending = null
        if (granted) {
            denied = false
            target?.let(::apply)
        } else {
            denied = true
            // Ни до, ни после запроса не нужно объяснение — система больше не показывает диалог
            // («Больше не спрашивать»): разрешить можно только в настройках.
            val rationaleAfter = context.findActivity()
                ?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true
            if (!rationaleBefore && !rationaleAfter) {
                Toast.makeText(context, "Разрешите уведомления для OpenMES в настройках", Toast.LENGTH_LONG).show()
                context.openAppNotificationSettings()
            }
        }
    }
    fun toggle(enabled: Boolean, target: PendingEnable) {
        if (enabled && !context.notificationsPermitted()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                pending = target
                rationaleBefore = context.findActivity()
                    ?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
            // Разрешение есть, но уведомления выключены в системе — включаем и показываем плашку.
            denied = true
        }
        if (enabled) apply(target) else when (target) {
            PendingEnable.Marks -> viewModel.setMarks(false)
            PendingEnable.Lessons -> viewModel.setLessons(false)
            PendingEnable.Homework -> viewModel.setHomework(false)
            PendingEnable.Tests -> viewModel.setTests(false)
            PendingEnable.ScheduleChanges -> viewModel.setScheduleChanges(false)
            PendingEnable.PreviewLesson, PendingEnable.CheckEvening -> Unit
        }
    }

    val anyEnabled = settings.marksNotifications || settings.lessonReminders || settings.homeworkReminders ||
        settings.testReminders || settings.scheduleChangeNotifications

    val changes = buildList<@Composable (Shape) -> Unit> {
        add { shape ->
            SwitchItem(
                icon = Icons.Rounded.EditCalendar,
                title = "Изменения в расписании",
                subtitle = "Отмена, замена и перенос пар, новые пары; проверка примерно раз в 30 минут",
                checked = settings.scheduleChangeNotifications,
                onCheckedChange = { toggle(it, PendingEnable.ScheduleChanges) },
                shape = shape,
            )
        }
        if (settings.scheduleChangeNotifications) {
            add { shape ->
                MesCard(shape = shape) {
                    Text("Следить за изменениями", style = MaterialTheme.typography.titleMedium)
                    ConnectedChoiceGroup(
                        options = listOf(2, 3, 7, 14),
                        selected = settings.scheduleChangesDays,
                        onSelect = viewModel::setScheduleChangesDays,
                        label = {
                            when (it) {
                                2 -> "2 дня"
                                3 -> "3 дня"
                                7 -> "Неделя"
                                else -> "2 нед."
                            }
                        },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            add { shape ->
                SwitchItem(
                    icon = Icons.Rounded.MeetingRoom,
                    title = "Кабинет и преподаватель",
                    subtitle = "Сообщать и о смене кабинета или преподавателя",
                    checked = settings.scheduleChangesRooms,
                    onCheckedChange = viewModel::setScheduleChangesRooms,
                    shape = shape,
                )
            }
        }
    }

    val marks = listOf<@Composable (Shape) -> Unit>(
        { shape ->
            SwitchItem(
                icon = Icons.Rounded.NotificationsActive,
                title = "Новые оценки",
                subtitle = "Проверять дневник в фоне примерно раз в час",
                checked = settings.marksNotifications,
                onCheckedChange = { toggle(it, PendingEnable.Marks) },
                shape = shape,
            )
        },
        { shape ->
            SwitchItem(
                icon = Icons.Rounded.VisibilityOff,
                title = "Скрывать значение оценки",
                subtitle = "В уведомлении будет только предмет",
                checked = settings.hideMarkValues,
                enabled = settings.marksNotifications,
                onCheckedChange = viewModel::setHideMarkValues,
                shape = shape,
            )
        },
    )

    val lessons = buildList<@Composable (Shape) -> Unit> {
        add { shape ->
            SwitchItem(
                icon = Icons.Rounded.AlarmOn,
                title = "Перед парой",
                subtitle = "Предмет, время и кабинет; у дистанционной — кнопка «Подключиться»",
                checked = settings.lessonReminders,
                onCheckedChange = { toggle(it, PendingEnable.Lessons) },
                shape = shape,
            )
        }
        if (settings.lessonReminders) {
            add { shape ->
                MesCard(shape = shape) {
                    Text("За сколько минут", style = MaterialTheme.typography.titleMedium)
                    ConnectedChoiceGroup(
                        options = listOf(5, 10, 15, 30),
                        selected = settings.lessonReminderMinutes,
                        onSelect = viewModel::setLessonMinutes,
                        label = { "$it мин" },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            add { shape ->
                SwitchItem(
                    icon = Icons.Rounded.VideoCall,
                    title = "Только дистанционные",
                    subtitle = "Не напоминать об очных парах",
                    checked = settings.lessonRemindersDistanceOnly,
                    onCheckedChange = viewModel::setDistanceOnly,
                    shape = shape,
                )
            }
            if (!exactAlarms) {
                add { shape ->
                    MesListItem(
                        headline = "Разрешить точное время",
                        supporting = "Без этого Android может прислать напоминание на несколько минут позже",
                        icon = Icons.Rounded.Timer,
                        iconContainerColor = MaterialTheme.colorScheme.errorContainer,
                        iconContentColor = MaterialTheme.colorScheme.onErrorContainer,
                        onClick = { context.openExactAlarmSettings() },
                        shape = shape,
                    )
                }
            }
            add { shape ->
                MesListItem(
                    headline = "Показать пример",
                    supporting = "Как будет выглядеть напоминание",
                    icon = Icons.Rounded.NotificationsActive,
                    onClick = { toggle(true, PendingEnable.PreviewLesson) },
                    shape = shape,
                )
            }
        }
    }

    val evening = buildList<@Composable (Shape) -> Unit> {
        add { shape ->
            SwitchItem(
                icon = Icons.AutoMirrored.Rounded.MenuBook,
                title = "Несделанное ДЗ на завтра",
                subtitle = "Список заданий, не отмеченных выполненными",
                checked = settings.homeworkReminders,
                onCheckedChange = { toggle(it, PendingEnable.Homework) },
                shape = shape,
            )
        }
        add { shape ->
            SwitchItem(
                icon = Icons.Rounded.Quiz,
                title = "Контрольные завтра",
                subtitle = "Контрольные, зачёты и проверочные по расписанию",
                checked = settings.testReminders,
                onCheckedChange = { toggle(it, PendingEnable.Tests) },
                shape = shape,
            )
        }
        if (settings.homeworkReminders || settings.testReminders) {
            add { shape ->
                MesCard(shape = shape) {
                    Text("Во сколько напоминать", style = MaterialTheme.typography.titleMedium)
                    ConnectedChoiceGroup(
                        options = listOf(17, 18, 19, 20, 21),
                        selected = settings.eveningReminderHour,
                        onSelect = viewModel::setEveningHour,
                        label = { "$it:00" },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            add { shape ->
                MesListItem(
                    headline = "Проверить сейчас",
                    supporting = "Уведомление придёт, если на завтра что-то есть",
                    icon = Icons.Rounded.NotificationsActive,
                    onClick = { toggle(true, PendingEnable.CheckEvening) },
                    shape = shape,
                )
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(GroupGap),
    ) {
        if ((anyEnabled || denied) && !permitted) {
            item {
                MesListItem(
                    headline = "Уведомления запрещены",
                    supporting = "Разрешите их в настройках Android, иначе они не будут показываться",
                    icon = Icons.Rounded.NotificationsOff,
                    iconContainerColor = MaterialTheme.colorScheme.errorContainer,
                    iconContentColor = MaterialTheme.colorScheme.onErrorContainer,
                    onClick = { context.openAppNotificationSettings() },
                    shape = groupShape(0, 1),
                )
            }
        }

        item { SectionHeader("Оценки") }
        group(marks)

        item { SectionHeader("Пары", Modifier.padding(top = 12.dp)) }
        group(lessons)

        item { SectionHeader("Расписание", Modifier.padding(top = 12.dp)) }
        group(changes)

        item { SectionHeader("Вечером накануне", Modifier.padding(top = 12.dp)) }
        group(evening)

        item {
            Text(
                "Напоминания о парах берутся из сохранённого расписания — оно обновляется в фоне и при открытии приложения. " +
                    "Экономия заряда на некоторых телефонах может задерживать уведомления: " +
                    "если они опаздывают, отключите её для OpenMES.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
            )
        }
    }
}

/** Что включить, когда пользователь ответит на запрос разрешения. */
private enum class PendingEnable { Marks, Lessons, Homework, Tests, ScheduleChanges, PreviewLesson, CheckEvening }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.notificationsPermitted(): Boolean =
    (
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) &&
        androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()

private fun Context.exactAlarmsAllowed(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

private fun Context.openExactAlarmSettings() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    runCatching {
        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
    }
}

private fun Context.openAppNotificationSettings() {
    runCatching {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }
}
