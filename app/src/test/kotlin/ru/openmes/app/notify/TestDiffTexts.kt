package ru.openmes.app.notify

/**
 * Форматы фраз описания изменений из `strings.xml` для юнит-тестов: ресурсов в JVM-тестах нет,
 * поэтому форматы продублированы здесь. Правку строк в `strings.xml` нужно повторить и здесь.
 */
internal val testDiffTexts = DiffTexts { phrase, args ->
    when (phrase) {
        DiffText.REPLACED -> "%1\$s замена: %2\$s → %3\$s".format(*args)
        DiffText.CANCELLED -> "%1\$s отменена: %2\$s".format(*args)
        DiffText.ADDED -> "%1\$s новая пара: %2\$s%3\$s".format(*args)
        DiffText.MOVED -> "перенос %1\$s → %2\$s".format(*args)
        DiffText.NOW_DISTANCE -> "теперь дистанционно"
        DiffText.NOW_ONLINE -> "теперь очно"
        DiffText.ROOM -> "кабинет %1\$s → %2\$s".format(*args)
        DiffText.TEACHER -> "преподаватель: %1\$s".format(*args)
        DiffText.SUMMARY -> "%1\$s %2\$s: %3\$s".format(*args)
        DiffText.WHERE_DISTANCE -> " (дистанционно)"
        DiffText.WHERE_ROOM -> " (каб. %1\$s)".format(*args)
    }
}
