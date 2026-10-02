package ru.openmes.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ru.openmes.app.notify.Notifications
import ru.openmes.app.notify.currentChildId
import ru.openmes.core.common.humanize
import ru.openmes.core.common.runSuspendCatching
import ru.openmes.core.data.DiaryRepository
import ru.openmes.core.data.NotificationDetails
import ru.openmes.core.data.SettingsRepository
import ru.openmes.core.model.Homework
import ru.openmes.core.model.Mark
import ru.openmes.core.model.SubjectMarksData
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Фоновая проверка дневника (аналог уведомлений OctoDiary-kt, но без пушей МЭШ):
 * раз в час сравниваем с сохранёнными новые оценки, изменение уже поставленной
 * оценки и домашние задания (новые и с изменённым текстом). Первый запуск
 * только запоминает базу, без лавины уведомлений.
 *
 * Оценки и ДЗ молчат независимо: у каждого свой тумблер ([ru.openmes.core.data.AppSettings.marksNotifications]
 * и [ru.openmes.core.data.AppSettings.homeworkChangeNotifications]). Воркер запускается, пока включён хоть один.
 */
class MarksPollWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val diaryRepository: DiaryRepository by inject()
    private val settingsRepository: SettingsRepository by inject()

    override suspend fun doWork(): Result {
        val childId = currentChildId() ?: return Result.success()
        val settings = settingsRepository.settings.first()

        val subjects = runSuspendCatching { diaryRepository.getSubjectMarks(childId) }
            .getOrElse { return Result.retry() }
        // ДЗ необязательны: если этот запрос упал — сравниваем хотя бы оценки,
        // сохранённую базу ДЗ не трогаем (иначе все задания «станут новыми»).
        val homeworks = runSuspendCatching {
            diaryRepository.getHomeworks(childId, LocalDate.now().minusDays(2), LocalDate.now().plusDays(14))
        }.getOrNull()

        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Базу обновляем по обоим спискам даже при выключенном тумблере: включив его позже,
        // ученик получит только то, что появилось действительно после, а не лавину за месяц.
        diffMarks(
            prefs, childId, subjects,
            hideValues = settings.hideMarkValues,
            notify = settings.marksNotifications,
        )
        if (homeworks != null) {
            diffHomeworks(
                prefs, childId, homeworks,
                notify = settings.homeworkChangeNotifications,
            )
        }
        return Result.success()
    }

    /**
     * Новые оценки и изменение значения уже поставленной. База — «id|значение»;
     * записи старого формата (только id, до отслеживания изменений) дают
     * значение null — «неизвестно», изменением не считаем.
     */
    private fun diffMarks(
        prefs: SharedPreferences,
        childId: String,
        subjects: List<SubjectMarksData>,
        hideValues: Boolean,
        notify: Boolean,
    ) {
        val key = "$KEY_MARKS_PREFIX$childId"
        val known = prefs.getStringSet(key, null)
        val knownValues = known.orEmpty().associate { entry ->
            val id = entry.substringBefore(SEP)
            id to if (SEP in entry) entry.substringAfter(SEP) else null
        }
        val marks = subjects.flatMap { subject ->
            subject.periods.flatMap { it.marks }.map { subject.subjectName to it }
        }
        prefs.edit { putStringSet(key, marks.map { "${it.second.id}$SEP${it.second.value}" }.toSet()) }
        if (known == null || !notify) return

        var notified = 0
        for ((subject, mark) in marks) {
            if (notified >= MAX_NOTIFICATIONS) break
            val old = knownValues[mark.id]
            val changed = old != null && old != mark.value
            val text = when {
                mark.id !in knownValues -> markText(mark, hideValues)
                changed ->
                    if (hideValues) "Оценка изменена${mark.daySuffix()}"
                    else "Оценка изменена: $old → ${mark.value}${mark.daySuffix()}"
                else -> null
            } ?: continue
            val details = NotificationDetails(
                subject = subject,
                dayIso = mark.date?.toString(),
                // Скрытые значения — не показываем оценку и в деталях истории.
                mark = if (hideValues) null else if (changed) "$old → ${mark.value}" else mark.value,
            )
            notify(mark.id.hashCode(), subject, text, Notifications.Channel.MARKS, details)
            notified++
        }
    }

    /** Новые ДЗ и правки текста задания; isDone не учитываем — его меняет сам ученик. */
    private fun diffHomeworks(
        prefs: SharedPreferences,
        childId: String,
        homeworks: List<Homework>,
        notify: Boolean,
    ) {
        val key = "$KEY_HOMEWORKS_PREFIX$childId"
        val known = prefs.getStringSet(key, null)
        val knownTasks = known.orEmpty().associate { it.substringBefore(SEP) to it.substringAfter(SEP) }
        prefs.edit { putStringSet(key, homeworks.map { "${it.id}$SEP${it.task}" }.toSet()) }
        if (known == null || !notify) return

        var notified = 0
        for (homework in homeworks) {
            if (notified >= MAX_NOTIFICATIONS) break
            val old = knownTasks[homework.id]
            val text = when {
                old == null -> "Новое задание: ${homework.task.firstLine()}${homework.daySuffix()}"
                old != homework.task -> "Задание изменилось: ${homework.task.firstLine()}${homework.daySuffix()}"
                else -> null
            } ?: continue
            notify(
                homework.id.hashCode(), homework.subjectName, text, Notifications.Channel.HOMEWORK,
                NotificationDetails(subject = homework.subjectName, dayIso = homework.date.toString()),
            )
            notified++
        }
    }

    private fun markText(mark: Mark, hideValues: Boolean): String =
        if (hideValues) {
            "Новая оценка${mark.daySuffix()}"
        } else {
            buildString {
                append("Оценка ${mark.value}")
                mark.weight?.takeIf { it > 1 }?.let { append(" (вес $it)") }
                mark.typeName?.let { append(" · $it") }
                append(mark.daySuffix())
            }
        }

    /** День, к которому относится оценка: «за вчера», «за 25 сентября». */
    private fun Mark.daySuffix(): String =
        date?.let { " · за ${it.humanize().lowercase()}" }.orEmpty()

    /** День, на который задано ДЗ: «на завтра», «на 3 октября». */
    private fun Homework.daySuffix(): String = " · на ${date.humanize().lowercase()}"

    private fun String.firstLine(): String = lineSequence().first().take(80)

    private fun notify(
        id: Int,
        title: String,
        text: String,
        channel: Notifications.Channel,
        details: NotificationDetails? = null,
    ) {
        val builder = Notifications.builder(applicationContext, channel)
            .setContentTitle(title)
            .setContentText(text)
            .setGroup(channel.id)
        Notifications.post(applicationContext, id, builder, details)
    }

    companion object {
        private const val WORK_NAME = "marks_poll"
        private const val PREFS = "marks_poll"
        private const val KEY_MARKS_PREFIX = "known_marks_"
        private const val KEY_HOMEWORKS_PREFIX = "known_homeworks_"
        private const val MAX_NOTIFICATIONS = 10
        private const val SEP = "|"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MarksPollWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            // При повторном включении — снова начать с базы, без лавины старых оценок.
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
        }
    }
}
