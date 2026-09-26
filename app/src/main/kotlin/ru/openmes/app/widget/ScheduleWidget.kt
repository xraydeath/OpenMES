package ru.openmes.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import ru.openmes.app.MainActivity
import ru.openmes.app.notify.upcomingLessons
import ru.openmes.core.common.runSuspendCatching
import ru.openmes.core.common.toHM
import ru.openmes.core.common.toRuDate
import ru.openmes.core.common.toShortRu
import ru.openmes.core.model.Lesson
import java.time.LocalDate
import java.time.LocalTime

/** Виджет «Расписание»: ближайший учебный день (сегодня, пока пары не кончились). */
class ScheduleWidget : GlanceAppWidget() {

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
        Column(
            GlanceModifier
                .fillMaxSize()
                .background(colors.widgetBackground)
                .cornerRadius(24.dp)
                .padding(14.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Text(
                day?.let { dayTitle(it.date) } ?: "Расписание",
                style = TextStyle(color = colors.primary, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.height(8.dp))
            if (day == null) {
                Text(
                    "Нет сохранённого расписания — откройте приложение",
                    style = TextStyle(color = colors.onSurfaceVariant, fontSize = 13.sp),
                )
                return@Column
            }
            val now = LocalTime.now()
            val isToday = day.date == LocalDate.now()
            LazyColumn {
                items(day.lessons, itemId = { it.id.hashCode().toLong() }) { lesson ->
                    val current = isToday && lesson.startTime != null && lesson.endTime != null &&
                        now >= lesson.startTime && now < lesson.endTime
                    val past = isToday && lesson.endTime != null && lesson.endTime!! <= now
                    Column(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Row(
                            GlanceModifier
                                .fillMaxWidth()
                                .background(if (current) colors.primaryContainer else colors.secondaryContainer)
                                .cornerRadius(14.dp)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                lesson.startTime?.toHM() ?: "—",
                                style = TextStyle(
                                    color = if (current) colors.onPrimaryContainer else colors.onSecondaryContainer,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                            )
                            Spacer(GlanceModifier.width(8.dp))
                            Column {
                                Text(
                                    lesson.subjectName,
                                    maxLines = 1,
                                    style = TextStyle(
                                        color = if (current) colors.onPrimaryContainer else colors.onSecondaryContainer,
                                        fontSize = 13.sp,
                                        fontWeight = if (past) FontWeight.Normal else FontWeight.Medium,
                                    ),
                                )
                                val meta = listOfNotNull("Дистанционно".takeIf { lesson.isDistance }, lesson.room)
                                    .joinToString(" · ")
                                if (meta.isNotEmpty()) {
                                    Text(
                                        meta,
                                        maxLines = 1,
                                        style = TextStyle(color = colors.onSurfaceVariant, fontSize = 11.sp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** Перерисовать все экземпляры (после обновления кэша расписания). */
        suspend fun refresh(context: Context) {
            runSuspendCatching { ScheduleWidget().updateAll(context) }
            runSuspendCatching { DayWidget().updateAll(context) }
        }
    }
}

class ScheduleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScheduleWidget()

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetRefresh.cancelIfUnused(context)
    }
}

/** Ближайший учебный день для виджетов. */
internal data class WidgetDay(val date: LocalDate, val lessons: List<Lesson>)

/** Сегодня, пока пары не кончились, иначе следующий день с парами. Сначала кэш (как у экрана — по месяцам), без него — сеть. */
internal suspend fun loadWidgetDay(): WidgetDay? {
    val today = LocalDate.now()
    val now = LocalTime.now()
    val byDate = upcomingLessons().groupBy { it.date }.toSortedMap()
    val (date, dayLessons) = byDate.entries.firstOrNull { (date, list) ->
        date != today || list.any { (it.endTime ?: LocalTime.MAX) > now }
    } ?: return null
    return WidgetDay(date, dayLessons.sortedBy { it.startTime })
}

internal fun dayTitle(date: LocalDate): String {
    val today = LocalDate.now()
    val prefix = when (date) {
        today -> "Сегодня"
        today.plusDays(1) -> "Завтра"
        else -> date.dayOfWeek.toShortRu()
    }
    return "$prefix, ${date.toRuDate()}"
}
