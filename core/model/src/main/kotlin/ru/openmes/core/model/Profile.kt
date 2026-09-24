package ru.openmes.core.model

/**
 * Учётная запись человека (студента/родителя) — аналог person в МЭШ.
 */
data class Person(
    val id: String,
    val firstName: String,
    val lastName: String,
    val middleName: String? = null,
    val className: String? = null,
    val schoolName: String? = null,
    val roles: List<String> = emptyList(),
    /** contingent_guid — person_ids в eventcalendar и id для аватаров. */
    val personGuid: String? = null,
) {
    val fullName: String
        get() = listOfNotNull(lastName, firstName, middleName)
            .joinToString(" ")
            .trim()

    val shortName: String
        get() = buildString {
            append(firstName)
            lastName.firstOrNull()?.let { append(" ").append(it).append(".") }
            middleName?.firstOrNull()?.let { append(it).append(".") }
        }
}

/** Роль в системе МЭШ (как в оригинале: parent / student / preschooler). */
enum class MesRole {
    PARENT,
    STUDENT,
    PRESCHOOLER,
    UNKNOWN,
}
