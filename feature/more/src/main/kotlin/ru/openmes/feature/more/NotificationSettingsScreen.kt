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
import ru.openmes.core.designsystem.components.mesSnackbar
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
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.Grade
import androidx.compose.material.icons.rounded.MeetingRoom
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VideoCall
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.ui.res.stringResource
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
import ru.openmes.core.designsystem.theme.Spacing

class NotificationSettingsViewModel(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private fun update(block: suspend SettingsRepository.() -> Unit) = viewModelScope.launch { settingsRepository.block() }

    fun setMarks(enabled: Boolean) = update { setMarksNotifications(enabled) }
    fun setHideMarkValues(enabled: Boolean) = update { setHideMarkValues(enabled) }
    fun setHomeworkChanges(enabled: Boolean) = update { setHomeworkChangeNotifications(enabled) }
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
 * [onPreviewLesson] показывает пример напоминания о паре, [onCheckEvening] — запускает вечернюю проверку сейчас,
 * [onTestNotification] — показывает пример уведомления выбранного типа.
 */
@Composable
fun NotificationSettingsScreen(
    viewModel: NotificationSettingsViewModel,
    onPreviewLesson: () -> Unit,
    onCheckEvening: () -> Unit,
    onTestNotification: (TestNotification) -> Unit,
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
    // Тексты вне composable-колбэков: snackbar показывается из них, а не из отрисовки.
    val checkingEveningText = stringResource(R.string.more_notif_checking_evening)
    val allowHintText = stringResource(R.string.more_notif_allow_hint)
    fun apply(target: PendingEnable) = when (target) {
        PendingEnable.Marks -> viewModel.setMarks(true)
        PendingEnable.HomeworkChanges -> viewModel.setHomeworkChanges(true)
        PendingEnable.Lessons -> viewModel.setLessons(true)
        PendingEnable.Homework -> viewModel.setHomework(true)
        PendingEnable.Tests -> viewModel.setTests(true)
        PendingEnable.ScheduleChanges -> viewModel.setScheduleChanges(true)
        PendingEnable.PreviewLesson -> onPreviewLesson()
        PendingEnable.CheckEvening -> {
            onCheckEvening()
            mesSnackbar.show(checkingEveningText)
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
                // Разрешение выдаётся только в системных настройках — открываем их сразу,
                // плашка напоминает, зачем туда пришли.
                mesSnackbar.show(allowHintText, duration = SnackbarDuration.Long)
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
            PendingEnable.HomeworkChanges -> viewModel.setHomeworkChanges(false)
            PendingEnable.Lessons -> viewModel.setLessons(false)
            PendingEnable.Homework -> viewModel.setHomework(false)
            PendingEnable.Tests -> viewModel.setTests(false)
            PendingEnable.ScheduleChanges -> viewModel.setScheduleChanges(false)
            PendingEnable.PreviewLesson, PendingEnable.CheckEvening -> Unit
        }
    }

    val anyEnabled = settings.marksNotifications || settings.homeworkChangeNotifications ||
        settings.lessonReminders || settings.homeworkReminders ||
        settings.testReminders || settings.scheduleChangeNotifications

    // Подписи групп-переключателей разворачиваем заранее: label — обычная функция, не @Composable.
    val horizonLabels = mapOf(
        2 to stringResource(R.string.more_notif_horizon_2d),
        3 to stringResource(R.string.more_notif_horizon_3d),
        7 to stringResource(R.string.more_notif_horizon_week),
        14 to stringResource(R.string.more_notif_horizon_2w),
    )
    val minutesLabels = listOf(5, 10, 15, 30).associateWith { stringResource(R.string.more_notif_minutes_value, it) }
    val hourLabels = listOf(17, 18, 19, 20, 21).associateWith { stringResource(R.string.more_notif_hour_value, it) }

    val changes = buildList<@Composable (Shape) -> Unit> {
        add { shape ->
            SwitchItem(
                icon = Icons.Rounded.EditCalendar,
                title = stringResource(R.string.more_notif_schedule_changes),
                subtitle = stringResource(R.string.more_notif_schedule_changes_sub),
                checked = settings.scheduleChangeNotifications,
                onCheckedChange = { toggle(it, PendingEnable.ScheduleChanges) },
                shape = shape,
            )
        }
        if (settings.scheduleChangeNotifications) {
            add { shape ->
                MesCard(shape = shape) {
                    Text(
                        stringResource(R.string.more_notif_watch_changes),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    ConnectedChoiceGroup(
                        options = listOf(2, 3, 7, 14),
                        selected = settings.scheduleChangesDays,
                        onSelect = viewModel::setScheduleChangesDays,
                        // label у ConnectedChoiceGroup — обычная функция, подписи разворачиваем заранее.
                        label = { horizonLabels.getValue(it) },
                        modifier = Modifier.padding(top = Spacing.m),
                    )
                }
            }
            add { shape ->
                SwitchItem(
                    icon = Icons.Rounded.MeetingRoom,
                    title = stringResource(R.string.more_notif_room_teacher),
                    subtitle = stringResource(R.string.more_notif_room_teacher_sub),
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
                title = stringResource(R.string.more_notif_marks),
                subtitle = stringResource(R.string.more_notif_marks_sub),
                checked = settings.marksNotifications,
                onCheckedChange = { toggle(it, PendingEnable.Marks) },
                shape = shape,
            )
        },
        { shape ->
            SwitchItem(
                icon = Icons.Rounded.VisibilityOff,
                title = stringResource(R.string.more_notif_hide_marks),
                subtitle = stringResource(R.string.more_notif_hide_marks_sub),
                checked = settings.hideMarkValues,
                enabled = settings.marksNotifications,
                onCheckedChange = viewModel::setHideMarkValues,
                shape = shape,
            )
        },
        { shape ->
            SwitchItem(
                icon = Icons.Rounded.Assignment,
                title = stringResource(R.string.more_notif_homework_changes),
                subtitle = stringResource(R.string.more_notif_homework_changes_sub),
                checked = settings.homeworkChangeNotifications,
                onCheckedChange = { toggle(it, PendingEnable.HomeworkChanges) },
                shape = shape,
            )
        },
    )

    val lessons = buildList<@Composable (Shape) -> Unit> {
        add { shape ->
            SwitchItem(
                icon = Icons.Rounded.AlarmOn,
                title = stringResource(R.string.more_notif_lesson),
                subtitle = stringResource(R.string.more_notif_lesson_sub),
                checked = settings.lessonReminders,
                onCheckedChange = { toggle(it, PendingEnable.Lessons) },
                shape = shape,
            )
        }
        if (settings.lessonReminders) {
            add { shape ->
                MesCard(shape = shape) {
                    Text(
                        stringResource(R.string.more_notif_minutes_label),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    ConnectedChoiceGroup(
                        options = listOf(5, 10, 15, 30),
                        selected = settings.lessonReminderMinutes,
                        onSelect = viewModel::setLessonMinutes,
                        label = { minutesLabels.getValue(it) },
                        modifier = Modifier.padding(top = Spacing.m),
                    )
                }
            }
            add { shape ->
                SwitchItem(
                    icon = Icons.Rounded.VideoCall,
                    title = stringResource(R.string.more_notif_distance_only),
                    subtitle = stringResource(R.string.more_notif_distance_only_sub),
                    checked = settings.lessonRemindersDistanceOnly,
                    onCheckedChange = viewModel::setDistanceOnly,
                    shape = shape,
                )
            }
            if (!exactAlarms) {
                add { shape ->
                    MesListItem(
                        headline = stringResource(R.string.more_notif_exact_time),
                        supporting = stringResource(R.string.more_notif_exact_time_sub),
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
                    headline = stringResource(R.string.more_notif_show_example),
                    supporting = stringResource(R.string.more_notif_show_example_sub),
                    icon = Icons.Rounded.NotificationsActive,
                    onClick = { toggle(true, PendingEnable.PreviewLesson) },
                    shape = shape,
                )
            }
        }
    }

    /** Раздел «Проверка»: кнопки-примеры, каждый тип уведомления можно увидеть сразу. */
    val tests = buildList<@Composable (Shape) -> Unit> {
        add { shape ->
            MesListItem(
                headline = stringResource(R.string.more_notif_test_new_mark),
                supporting = stringResource(R.string.more_notif_test_new_mark_sub),
                icon = Icons.Rounded.Grade,
                onClick = { onTestNotification(TestNotification.NewMark) },
                shape = shape,
            )
        }
        add { shape ->
            MesListItem(
                headline = stringResource(R.string.more_notif_test_mark_changed),
                supporting = stringResource(R.string.more_notif_test_mark_changed_sub),
                icon = Icons.Rounded.Grade,
                onClick = { onTestNotification(TestNotification.MarkChanged) },
                shape = shape,
            )
        }
        add { shape ->
            MesListItem(
                headline = stringResource(R.string.more_notif_test_new_hw),
                supporting = stringResource(R.string.more_notif_test_new_hw_sub),
                icon = Icons.Rounded.Assignment,
                onClick = { onTestNotification(TestNotification.NewHomework) },
                shape = shape,
            )
        }
        add { shape ->
            MesListItem(
                headline = stringResource(R.string.more_notif_test_hw_changed),
                supporting = stringResource(R.string.more_notif_test_hw_changed_sub),
                icon = Icons.Rounded.Assignment,
                onClick = { onTestNotification(TestNotification.HomeworkChanged) },
                shape = shape,
            )
        }
        add { shape ->
            MesListItem(
                headline = stringResource(R.string.more_notif_test_schedule),
                supporting = stringResource(R.string.more_notif_test_schedule_sub),
                icon = Icons.Rounded.EditCalendar,
                onClick = { onTestNotification(TestNotification.ScheduleChanged) },
                shape = shape,
            )
        }
    }

    val evening = buildList<@Composable (Shape) -> Unit> {
        add { shape ->
            SwitchItem(
                icon = Icons.AutoMirrored.Rounded.MenuBook,
                title = stringResource(R.string.more_notif_homework_tomorrow),
                subtitle = stringResource(R.string.more_notif_homework_tomorrow_sub),
                checked = settings.homeworkReminders,
                onCheckedChange = { toggle(it, PendingEnable.Homework) },
                shape = shape,
            )
        }
        add { shape ->
            SwitchItem(
                icon = Icons.Rounded.Quiz,
                title = stringResource(R.string.more_notif_tests_tomorrow),
                subtitle = stringResource(R.string.more_notif_tests_tomorrow_sub),
                checked = settings.testReminders,
                onCheckedChange = { toggle(it, PendingEnable.Tests) },
                shape = shape,
            )
        }
        if (settings.homeworkReminders || settings.testReminders) {
            add { shape ->
                MesCard(shape = shape) {
                    Text(
                        stringResource(R.string.more_notif_hour_label),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    ConnectedChoiceGroup(
                        options = listOf(17, 18, 19, 20, 21),
                        selected = settings.eveningReminderHour,
                        onSelect = viewModel::setEveningHour,
                        label = { hourLabels.getValue(it) },
                        modifier = Modifier.padding(top = Spacing.m),
                    )
                }
            }
            add { shape ->
                MesListItem(
                    headline = stringResource(R.string.more_notif_check_now),
                    supporting = stringResource(R.string.more_notif_check_now_sub),
                    icon = Icons.Rounded.NotificationsActive,
                    onClick = { toggle(true, PendingEnable.CheckEvening) },
                    shape = shape,
                )
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.l, end = Spacing.l, top = Spacing.s, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(GroupGap),
    ) {
        if ((anyEnabled || denied) && !permitted) {
            item {
                MesListItem(
                    headline = stringResource(R.string.more_notif_denied),
                    supporting = stringResource(R.string.more_notif_denied_sub),
                    icon = Icons.Rounded.NotificationsOff,
                    iconContainerColor = MaterialTheme.colorScheme.errorContainer,
                    iconContentColor = MaterialTheme.colorScheme.onErrorContainer,
                    onClick = { context.openAppNotificationSettings() },
                    shape = groupShape(0, 1),
                )
            }
        }

        item { SectionHeader(stringResource(R.string.more_notif_group_marks)) }
        group(marks)

        item { SectionHeader(stringResource(R.string.more_notif_group_lessons), Modifier.padding(top = Spacing.m)) }
        group(lessons)

        item { SectionHeader(stringResource(R.string.more_notif_group_schedule), Modifier.padding(top = Spacing.m)) }
        group(changes)

        item { SectionHeader(stringResource(R.string.more_notif_group_evening), Modifier.padding(top = Spacing.m)) }
        group(evening)

        item { SectionHeader(stringResource(R.string.more_notif_group_check), Modifier.padding(top = Spacing.m)) }
        group(tests)

        item {
            Text(
                stringResource(R.string.more_notif_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.m),
            )
        }
    }
}

/** Что включить, когда пользователь ответит на запрос разрешения. */
private enum class PendingEnable {
    Marks, HomeworkChanges, Lessons, Homework, Tests, ScheduleChanges, PreviewLesson, CheckEvening,
}

/** Типы тестовых уведомлений для раздела «Проверка» в настройках. */
enum class TestNotification { NewMark, MarkChanged, NewHomework, HomeworkChanged, ScheduleChanged }

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
