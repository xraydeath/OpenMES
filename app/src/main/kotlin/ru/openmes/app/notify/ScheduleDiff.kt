package ru.openmes.app.notify

import ru.openmes.core.model.Lesson
import java.time.LocalDate
import java.time.LocalTime

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

/** Изменение в расписании за день [date]. */
data class ScheduleChange(val date: LocalDate, val start: LocalTime?, val text: String)

/**
 * Разница двух снимков. Пару сначала сопоставляем по id, затем (замена предмета — новый id)
 * по дате и времени начала; оставшиеся — отменённые и добавленные.
 */
fun diffSchedules(
    old: List<SlotSnapshot>,
    new: List<SlotSnapshot>,
    includeRooms: Boolean = true,
    includeTeachers: Boolean = true,
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
        changes += compare(o, n, includeRooms, includeTeachers)
    }
    for (n in unmatchedNew.toList()) {
        val o = unmatchedOld.firstOrNull { it.date == n.date && it.start != null && it.start == n.start } ?: continue
        unmatchedOld -= o
        unmatchedNew -= n
        if (o.subject != n.subject) {
            changes += ScheduleChange(n.date, n.start, "${n.start.hm()} замена: ${o.subject} → ${n.subject}")
        } else {
            changes += compare(o, n, includeRooms, includeTeachers)
        }
    }
    unmatchedOld.forEach { o -> changes += ScheduleChange(o.date, o.start, "${o.start.hm()} отменена: ${o.subject}") }
    unmatchedNew.forEach { n -> changes += ScheduleChange(n.date, n.start, "${n.start.hm()} новая пара: ${n.subject}${n.where()}") }
    return changes.sortedWith(compareBy({ it.date }, { it.start }))
}

private fun compare(o: SlotSnapshot, n: SlotSnapshot, includeRooms: Boolean, includeTeachers: Boolean): List<ScheduleChange> {
    val parts = buildList {
        if (o.start != n.start || o.end != n.end) add("перенос ${o.start.hm()} → ${n.start.hm()}")
        if (o.subject != n.subject) add("${o.subject} → ${n.subject}")
        if (o.distance != n.distance) add(if (n.distance) "теперь дистанционно" else "теперь очно")
        if (includeRooms && !n.distance && o.room != n.room && n.room != null) add("кабинет ${o.room ?: "—"} → ${n.room}")
        if (includeTeachers && o.teacher != n.teacher && n.teacher != null) add("преподаватель: ${n.teacher}")
    }
    if (parts.isEmpty()) return emptyList()
    val at = if (o.start != n.start) o.start else n.start
    return listOf(ScheduleChange(n.date, n.start, "${at.hm()} ${n.subject}: ${parts.joinToString(", ")}"))
}

private fun SlotSnapshot.where(): String = when {
    distance -> " (дистанционно)"
    room != null -> " (каб. $room)"
    else -> ""
}

private fun LocalTime?.hm(): String = this?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "—"
