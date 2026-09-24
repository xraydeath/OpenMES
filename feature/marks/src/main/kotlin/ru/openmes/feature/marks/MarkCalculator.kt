package ru.openmes.feature.marks

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.openmes.core.designsystem.components.ConnectedChoiceGroup
import ru.openmes.core.designsystem.components.HeroCard
import ru.openmes.core.designsystem.components.MarkBadge
import ru.openmes.core.designsystem.components.ShapeIcon
import ru.openmes.core.designsystem.components.markShape
import ru.openmes.core.designsystem.components.markTone
import ru.openmes.core.model.Mark
import ru.openmes.core.model.SubjectPeriod
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Калькулятор оценок (перенос MarkCalculator из OctoDiary-kt):
 * тап по оценке убирает её, «+5/+4/+3/+2» или строка «5 4^2 3» (^ — вес) добавляют.
 * Средний — взвешенный, округление до сотых; итоговая — математическое округление.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MarkCalculator(
    subjectName: String,
    period: SubjectPeriod,
    keyboardMode: Boolean,
    onKeyboardModeChange: (Boolean) -> Unit,
) {
    var marks by remember(period) { mutableStateOf(period.marks) }
    var counter by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }

    fun add(new: List<Pair<Int, Int>>) {
        marks = marks + new.map { (value, weight) ->
            counter++
            Mark(id = "calc_$counter", value = value.toString(), weight = weight)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(
                icon = Icons.Rounded.Calculate,
                shape = MaterialShapes.Cookie9Sided.toShape(),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                size = 48.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Калькулятор оценок", style = MaterialTheme.typography.titleLargeEmphasized)
                Text(
                    "$subjectName, ${period.title.lowercase()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalIconButton(onClick = { marks = period.marks }, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Сбросить")
            }
        }

        // Итог: средний + округлённая
        val valid = marks.isValidForCalc()
        HeroCard(
            containerColor = if (valid) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
            contentColor = if (valid) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
        ) {
            if (!valid) {
                Text(
                    "Уберите нечисловые оценки (тапом), чтобы посчитать средний",
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else if (marks.isEmpty()) {
                Text("Оценок нет — добавьте их ниже", style = MaterialTheme.typography.bodyLarge)
            } else {
                val average = marks.weightedAverage()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Средний балл", style = MaterialTheme.typography.labelLarge)
                        AnimatedContent("%.2f".format(average), label = "calc_avg") { v ->
                            Text(v, style = MaterialTheme.typography.displayMediumEmphasized)
                        }
                        val base = period.value
                        if (base != null) {
                            Text(
                                "было $base",
                                style = MaterialTheme.typography.bodyMedium,
                                color = LocalContentColor.current.copy(alpha = 0.72f),
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MarkBadge(average.roundToInt().toString(), large = true)
                        Text("итог", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        if (marks.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                marks.forEach { mark ->
                    MarkComp(mark = mark) { marks = marks.filter { it.id != mark.id } }
                }
            }
            Text(
                "Тап по оценке — убрать её",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ConnectedChoiceGroup(
            options = listOf(false, true),
            selected = keyboardMode,
            onSelect = onKeyboardModeChange,
            label = { if (it) "Клавиатура" else "Кнопки" },
            icon = { if (it) Icons.Rounded.Keyboard else Icons.Rounded.Apps },
        )

        AnimatedContent(keyboardMode, label = "calc_mode") { keyboard ->
            if (!keyboard) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf(5, 4, 3, 2).forEach { value ->
                        AddMarkButton(value) { add(listOf(value to 1)) }
                    }
                }
            } else {
                val submit = {
                    add(parseMarks(text))
                    text = ""
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    label = { Text("Добавить оценки") },
                    supportingText = { Text("Через пробел, вес через ^: 5 4^2 3") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    trailingIcon = {
                        FilledIconButton(onClick = submit, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.ArrowUpward, contentDescription = "Добавить")
                        }
                    },
                )
            }
        }

        OutlinedButton(
            onClick = { marks = emptyList() },
            enabled = marks.isNotEmpty(),
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text("Очистить все")
        }
    }
}

/** Кнопка «+оценка»: форма и тон — как у плашки этой оценки. */
@Composable
private fun AddMarkButton(value: Int, onClick: () -> Unit) {
    val tone = markTone(value.toString())
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.85f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "add_scale",
    )
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(64.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = markShape(value.toString()),
        color = tone.container,
        contentColor = tone.content,
        interactionSource = interactionSource,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(value.toString(), style = MaterialTheme.typography.titleLargeEmphasized)
            }
        }
    }
}

private fun List<Mark>.isValidForCalc(): Boolean = all { it.value.trim().toIntOrNull() != null }

private fun List<Mark>.weightedAverage(): Double {
    val totalWeight = sumOf { (it.weight ?: 1).coerceAtLeast(1) }
    if (totalWeight == 0) return 0.0
    val sum = sumOf { it.value.trim().toInt() * (it.weight ?: 1).coerceAtLeast(1) }
    return round(sum.toDouble() / totalWeight * 100) / 100
}

/** Стрелка чипа: выше исходного среднего периода — UP. */
private fun dynamicOf(average: Double, original: String?): String {
    val base = original?.replace(',', '.')?.toDoubleOrNull() ?: return "NONE"
    return if (average >= base) "UP" else "DOWN"
}

/** «5 4^2 3» → [(5,1), (4,2), (3,1)]; мусор пропускается. */
internal fun parseMarks(input: String): List<Pair<Int, Int>> =
    input.trim().split(Regex("[\\s,;]+")).mapNotNull { token ->
        val parts = token.split('^')
        val value = parts[0].toIntOrNull()?.takeIf { it in 1..5 } ?: return@mapNotNull null
        val weight = when (parts.size) {
            1 -> 1
            2 -> parts[1].toIntOrNull()?.takeIf { it in 1..10 } ?: return@mapNotNull null
            else -> return@mapNotNull null
        }
        value to weight
    }
