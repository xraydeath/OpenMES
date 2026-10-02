package ru.openmes.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.LocalContext
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import ru.openmes.app.MainActivity
import ru.openmes.app.R
import ru.openmes.core.common.toHM
import ru.openmes.core.model.Lesson
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/** Виджет «Учебный день»: к скольки приходить, сколько пар (уроков) и до скольки. */
class DayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val day = loadWidgetDay()
        WidgetRefresh.schedule(context, day)
        provideContent {
            GlanceTheme { Content(day) }
        }
    }

    @Composable
    private fun Content(day: WidgetDay?) {
        val colors = GlanceTheme.colors
        val context = LocalContext.current
        Column(
            GlanceModifier
                .fillMaxSize()
                .background(colors.widgetBackground)
                .cornerRadius(24.dp)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Text(
                day?.let { dayTitle(context, it.date) } ?: context.getString(R.string.widget_day_label),
                maxLines = 1,
                style = TextStyle(color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold),
            )
            if (day == null) {
                Text(
                    context.getString(R.string.widget_no_saved_schedule),
                    style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp),
                )
                return@Column
            }
            // Кружки и мероприятия (EC/AE/EVENTS) не пары: считаем только плановые, если они есть.
            val lessons = day.lessons.filter { it.source == null || it.source == "PLAN" }.ifEmpty { day.lessons }
            val first = lessons.firstOrNull { it.startTime != null }
            val firstOnSite = lessons.firstOrNull { !it.isDistance && it.startTime != null }
            val end = lessons.mapNotNull { it.endTime }.maxOrNull()
            // Первая пара уже началась — «к 9:00» больше не нужно, важнее, до скольки.
            val started = day.date == LocalDate.now() && first != null && LocalTime.now() >= first.startTime

            Spacer(GlanceModifier.defaultWeight())
            Text(
                when {
                    started && end != null -> context.getString(R.string.widget_until, end.toHM())
                    firstOnSite != null -> context.getString(R.string.widget_by, firstOnSite.startTime!!.toHM())
                    lessons.all { it.isDistance } -> context.getString(R.string.widget_distance_only)
                    else -> "—"
                },
                maxLines = 1,
                style = TextStyle(
                    color = colors.onSurface,
                    fontSize = if (firstOnSite != null || started) 30.sp else 20.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            // «3 пары» крупнее, «6 уроков» под ними; без сдвоенных уроков — одна строка.
            val (main, secondary) = countLines(context, lessons)
            Text(
                main,
                maxLines = 1,
                style = TextStyle(color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            )
            if (secondary != null) {
                Text(secondary, maxLines = 1, style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp))
            }
            Spacer(GlanceModifier.defaultWeight())
            val footer = when {
                started -> null
                // Дистанционные пары до первой очной — чтобы не проспать подключение.
                first != null && first.isDistance && firstOnSite != null ->
                    context.getString(R.string.widget_distance_from, first.startTime!!.toHM())
                firstOnSite == null && first != null -> context.getString(R.string.widget_from, first.startTime!!.toHM())
                else -> null
            }
            Text(
                listOfNotNull(
                    footer,
                    end?.takeUnless { started }?.let { context.getString(R.string.widget_until, it.toHM()) },
                ).joinToString(" · "),
                maxLines = 2,
                style = TextStyle(color = colors.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium),
            )
        }
    }
}

class DayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DayWidget()

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetRefresh.cancelIfUnused(context)
    }
}

/** («3 пары», «6 уроков»), если уроки сдвоены в пары, иначе («4 урока», null). */
private fun countLines(context: Context, lessons: List<Lesson>): Pair<String, String?> {
    val pairs = pairsCount(lessons)
    val lessonsText = context.resources.getQuantityString(R.plurals.widget_lessons, lessons.size, lessons.size)
    if (pairs >= lessons.size) return lessonsText to null
    return context.resources.getQuantityString(R.plurals.widget_pairs, pairs, pairs) to lessonsText
}

/** Пара — подряд идущие уроки одного предмета с переменой не больше 10 минут. */
internal fun pairsCount(lessons: List<Lesson>): Int {
    var count = 0
    var inPair = false
    lessons.forEachIndexed { index, lesson ->
        val prev = lessons.getOrNull(index - 1)
        val joins = !inPair && prev != null && prev.subjectName == lesson.subjectName &&
            prev.endTime != null && lesson.startTime != null &&
            Duration.between(prev.endTime, lesson.startTime).toMinutes() in 0..10
        if (joins) {
            inPair = true
        } else {
            count++
            inPair = false
        }
    }
    return count
}
