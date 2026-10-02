package ru.openmes.app.notify

import android.content.Context
import androidx.annotation.StringRes
import ru.openmes.app.R
import ru.openmes.core.common.toHM
import ru.openmes.core.model.Lesson
import java.time.LocalDate
import java.time.LocalTime

/**
 * Тексты описаний изменений расписания.
 *
 * Сравнение снимков — чистая функция без Android, поэтому тексты в неё передаются
 * снаружи: в приложении это ресурсы ([DiffTexts.of]), в юнит-тестах — литералы.
 */
/** Фраза описания изменения. Идентификатор, а не строка: текст берётся из ресурсов. */
enum class DiffText(@StringRes val res: Int) {
    REPLACED(R.string.diff_replaced),
    CANCELLED(R.string.diff_cancelled),
    ADDED(R.string.diff_added),
    MOVED(R.string.diff_moved),
    NOW_DISTANCE(R.string.diff_now_distance),
    NOW_ONLINE(R.string.diff_now_online),
    ROOM(R.string.diff_room),
    TEACHER(R.string.diff_teacher),
    SUMMARY(R.string.diff_summary),
    WHERE_DISTANCE(R.string.diff_where_distance),
    WHERE_ROOM(R.string.diff_where_room),
}

/** Подстановка фраз описания изменений: в приложении — ресурсы, в тестах — литералы. */
fun interface DiffTexts {
    fun text(phrase: DiffText, vararg args: Any): String

    companion object {
        fun of(context: Context): DiffTexts = DiffTexts { phrase, args ->
            context.getString(phrase.res, *args)
        }
    }
}

/** Пара в снимке расписания: только то, изменение чего стоит уведомления. */
data class SlotSnapshot(
    val id: String,
    val date: LocalDate,
    val start: LocalTime?,
    val end: LocalTime?,
    val subject: String,
    val room: String?,
    val teacher: String?,
    val distance: Boolean,
) {
    fun encode(): String = listOf(
        id, date.toString(), start?.toString().orEmpty(), end?.toString().orEmpty(),
        subject, room.orEmpty(), teacher.orEmpty(), if (distance) "1" else "0",
    ).joinToString(SEP) { it.replace(SEP, " ").replace("\n", " ") }

    companion object {
        private const val SEP = "\t"

        fun of(lesson: Lesson) = SlotSnapshot(
            id = lesson.id,
            date = lesson.date,
            start = lesson.startTime,
            end = lesson.endTime,
            subject = lesson.subjectName,
            room = lesson.room?.takeIf { it.isNotBlank() },
            teacher = lesson.teacherName?.takeIf { it.isNotBlank() },
            distance = lesson.isDistance,
        )

        fun decode(line: String): SlotSnapshot? = runCatching {
            val f = line.split(SEP)
            SlotSnapshot(
                id = f[0],
                date = LocalDate.parse(f[1]),
                start = f[2].takeIf { it.isNotEmpty() }?.let(LocalTime::parse),
                end = f[3].takeIf { it.isNotEmpty() }?.let(LocalTime::parse),
                subject = f[4],
                room = f[5].takeIf { it.isNotEmpty() },
                teacher = f[6].takeIf { it.isNotEmpty() },
                distance = f[7] == "1",
            )
        }.getOrNull()
    }
}

/**
 * Изменения в окне [today]..[until] между прошлым снимком и текущим. Сравниваем только дни, что были
 * в прошлом снимке (новый день на краю окна — не «новые пары»), и только в пределах нынешнего окна
 * (окно сократили — дни за краем не «отменены»). null — сервер вернул пустое окно при непустом прошлом:
 * скорее сбой, чем отмена всего; такой ответ не сохраняем и не сообщаем.
 */
fun diffWindow(
    previous: List<SlotSnapshot>,
    current: List<SlotSnapshot>,
    today: LocalDate,
    until: LocalDate,
    includeRooms: Boolean = true,
    includeTeachers: Boolean = true,
    texts: DiffTexts,
): List<ScheduleChange>? {
    val old = previous.filter { !it.date.isBefore(today) && !it.date.isAfter(until) }
    val new = current.filter { !it.date.isBefore(today) && !it.date.isAfter(until) }
    if (new.isEmpty() && old.isNotEmpty()) return null
    val lastDay = previous.maxOfOrNull { it.date } ?: return emptyList()
    return diffSchedules(old, new.filter { !it.date.isAfter(lastDay) }, includeRooms, includeTeachers, texts)
}

/** Изменение в расписании за день [date]. */
data class ScheduleChange(
    val date: LocalDate,
    val start: LocalTime?,
    val text: String,
    val subject: String? = null,
    val teacher: String? = null,
)

/**
 * Разница двух снимков. Пару сначала сопоставляем по id, затем (замена предмета — новый id)
 * по дате и времени начала; оставшиеся — отменённые и добавленные.
 */
fun diffSchedules(
    old: List<SlotSnapshot>,
    new: List<SlotSnapshot>,
    includeRooms: Boolean = true,
    includeTeachers: Boolean = true,
    texts: DiffTexts,
): List<ScheduleChange> {
    val changes = mutableListOf<ScheduleChange>()
    val unmatchedOld = old.toMutableList()
    val unmatchedNew = mutableListOf<SlotSnapshot>()

    for (n in new) {
        val o = unmatchedOld.firstOrNull { it.id == n.id && it.date == n.date } ?: run {
            unmatchedNew += n
            null
        } ?: continue
        unmatchedOld -= o
        changes += compare(o, n, includeRooms, includeTeachers, texts)
    }
    for (n in unmatchedNew.toList()) {
        val o = unmatchedOld.firstOrNull { it.date == n.date && it.start != null && it.start == n.start } ?: continue
        unmatchedOld -= o
        unmatchedNew -= n
        if (o.subject != n.subject) {
            changes += ScheduleChange(
                n.date,
                n.start,
                texts.text(DiffText.REPLACED, n.start.hm(), o.subject, n.subject),
                subject = n.subject, teacher = n.teacher,
            )
        } else {
            changes += compare(o, n, includeRooms, includeTeachers, texts)
        }
    }
    unmatchedOld.forEach { o ->
        changes += ScheduleChange(
            o.date,
            o.start,
            texts.text(DiffText.CANCELLED, o.start.hm(), o.subject),
            subject = o.subject,
            teacher = o.teacher,
        )
    }
    unmatchedNew.forEach { n ->
        changes += ScheduleChange(
            n.date,
            n.start,
            texts.text(DiffText.ADDED, n.start.hm(), n.subject, n.where(texts)),
            subject = n.subject,
            teacher = n.teacher,
        )
    }
    return changes.sortedWith(compareBy({ it.date }, { it.start }))
}

private fun compare(
    o: SlotSnapshot,
    n: SlotSnapshot,
    includeRooms: Boolean,
    includeTeachers: Boolean,
    texts: DiffTexts,
): List<ScheduleChange> {
    val parts = buildList {
        if (o.start != n.start || o.end != n.end) {
            add(texts.text(DiffText.MOVED, o.start.hm(), n.start.hm()))
        }
        if (o.subject != n.subject) add("${o.subject} → ${n.subject}")
        if (o.distance != n.distance) {
            add(texts.text(if (n.distance) DiffText.NOW_DISTANCE else DiffText.NOW_ONLINE))
        }
        if (includeRooms && !n.distance && o.room != n.room && n.room != null) {
            add(texts.text(DiffText.ROOM, o.room ?: "—", n.room))
        }
        if (includeTeachers && o.teacher != n.teacher && n.teacher != null) {
            add(texts.text(DiffText.TEACHER, n.teacher))
        }
    }
    if (parts.isEmpty()) return emptyList()
    val at = if (o.start != n.start) o.start else n.start
    return listOf(
        ScheduleChange(
            n.date,
            n.start,
            texts.text(DiffText.SUMMARY, at.hm(), n.subject, parts.joinToString(", ")),
            subject = n.subject,
            teacher = n.teacher,
        ),
    )
}

private fun SlotSnapshot.where(texts: DiffTexts): String = when {
    distance -> texts.text(DiffText.WHERE_DISTANCE)
    room != null -> texts.text(DiffText.WHERE_ROOM, room)
    else -> ""
}

private fun LocalTime?.hm(): String = this?.toHM() ?: "—"
