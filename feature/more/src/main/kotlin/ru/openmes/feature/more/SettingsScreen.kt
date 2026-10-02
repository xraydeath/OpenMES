package ru.openmes.feature.more

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.clickable
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.openmes.core.data.ThemeMode
import ru.openmes.core.designsystem.components.ConnectedChoiceGroup
import ru.openmes.core.designsystem.components.GroupGap
import ru.openmes.core.designsystem.components.HeroCard
import ru.openmes.core.designsystem.components.MesCard
import ru.openmes.core.designsystem.components.MesListItem
import ru.openmes.core.designsystem.components.SectionHeader
import ru.openmes.core.designsystem.components.ShapeIcon
import ru.openmes.core.designsystem.components.groupShape
import ru.openmes.core.designsystem.theme.Spacing

/**
 * Настройки: тема (в духе expressive), Material You, уведомления, PIN/биометрия, о приложении.
 */
@Composable
fun SettingsScreen(
    viewModel: MoreViewModel,
    onOpenCache: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenApiConsole: (() -> Unit)?,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pinDialog by remember { mutableStateOf(false) }
    val biometricAvailable = remember {
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }
    if (pinDialog) {
        PinSetupDialog(
            onDismiss = { pinDialog = false },
            onConfirm = { pin ->
                viewModel.setPin(pin)
                pinDialog = false
            },
        )
    }

    // Подписи тем разворачиваем заранее: label у ConnectedChoiceGroup — обычная функция.
    val themeLabels = ThemeMode.entries.associateWith {
        stringResource(
            when (it) {
                ThemeMode.SYSTEM -> R.string.more_settings_theme_system
                ThemeMode.LIGHT -> R.string.more_settings_theme_light
                ThemeMode.DARK -> R.string.more_settings_theme_dark
            },
        )
    }

    // Строки групп собираем списками: форма зависит от позиции в группе.
    val appearance = buildList<@Composable (Shape) -> Unit> {
        add { shape ->
            SwitchItem(
                icon = Icons.Rounded.Palette,
                title = stringResource(R.string.more_settings_dynamic_color),
                subtitle = stringResource(R.string.more_settings_dynamic_color_sub),
                checked = settings.dynamicColor,
                onCheckedChange = viewModel::setDynamicColor,
                shape = shape,
            )
        }
    }
    val security = buildList<@Composable (Shape) -> Unit> {
        add { shape ->
            SwitchItem(
                icon = Icons.Rounded.Pin,
                title = stringResource(R.string.more_settings_pin),
                subtitle = stringResource(R.string.more_settings_pin_sub),
                checked = settings.pinEnabled,
                onCheckedChange = { enabled -> if (enabled) pinDialog = true else viewModel.setPin(null) },
                shape = shape,
            )
        }
        if (biometricAvailable) {
            add { shape ->
                SwitchItem(
                    icon = Icons.Rounded.Fingerprint,
                    title = stringResource(R.string.more_settings_biometric),
                    subtitle = stringResource(R.string.more_settings_biometric_sub),
                    checked = settings.biometricEnabled,
                    enabled = settings.pinEnabled,
                    onCheckedChange = viewModel::setBiometricEnabled,
                    shape = shape,
                )
            }
        }
        if (settings.pinEnabled) {
            add { shape ->
                MesListItem(
                    headline = stringResource(R.string.more_settings_change_pin),
                    icon = Icons.Rounded.Password,
                    onClick = { pinDialog = true },
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
        item { SectionHeader(stringResource(R.string.more_settings_appearance)) }
        item {
            MesCard(shape = groupShape(0, appearance.size + 1)) {
                Text(stringResource(R.string.more_settings_theme), style = MaterialTheme.typography.titleMedium)
                ConnectedChoiceGroup(
                    options = ThemeMode.entries,
                    selected = settings.themeMode,
                    onSelect = viewModel::setThemeMode,
                    // label у ConnectedChoiceGroup — обычная функция, подписи разворачиваем заранее.
                    label = { themeLabels.getValue(it) },
                    icon = {
                        when (it) {
                            ThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto
                            ThemeMode.LIGHT -> Icons.Rounded.LightMode
                            ThemeMode.DARK -> Icons.Rounded.DarkMode
                        }
                    },
                    modifier = Modifier.padding(top = Spacing.m),
                )
            }
        }
        group(appearance, offset = 1)

        item { SectionHeader(stringResource(R.string.more_notif_section), Modifier.padding(top = Spacing.m)) }
        item {
            val enabled = listOfNotNull(
                stringResource(R.string.more_notif_short_marks).takeIf { settings.marksNotifications },
                stringResource(R.string.more_notif_short_homework_changes).takeIf { settings.homeworkChangeNotifications },
                stringResource(R.string.more_notif_short_lessons).takeIf { settings.lessonReminders },
                stringResource(R.string.more_notif_short_schedule).takeIf { settings.scheduleChangeNotifications },
                stringResource(R.string.more_notif_short_homework).takeIf { settings.homeworkReminders },
                stringResource(R.string.more_notif_short_tests).takeIf { settings.testReminders },
            )
            MesListItem(
                headline = stringResource(R.string.more_notif_section),
                supporting = if (enabled.isEmpty()) {
                    stringResource(R.string.more_notif_all_off)
                } else {
                    enabled.joinToString(", ").replaceFirstChar(Char::uppercase)
                },
                icon = Icons.Rounded.NotificationsActive,
                onClick = onOpenNotifications,
                shape = groupShape(0, 1),
            )
        }

        item { SectionHeader(stringResource(R.string.more_settings_security), Modifier.padding(top = Spacing.m)) }
        group(security)

        item { SectionHeader(stringResource(R.string.more_settings_data), Modifier.padding(top = Spacing.m)) }
        item {
            MesListItem(
                headline = stringResource(R.string.more_settings_cache),
                supporting = if (settings.cacheEnabled) {
                    stringResource(R.string.more_settings_cache_on)
                } else {
                    stringResource(R.string.more_settings_cache_off)
                },
                icon = Icons.Rounded.Storage,
                onClick = onOpenCache,
                shape = groupShape(0, 1),
            )
        }

        item { SectionHeader(stringResource(R.string.more_settings_about), Modifier.padding(top = Spacing.m)) }
        item {
            HeroCard(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.l),
                ) {
                    ShapeIcon(
                        icon = Icons.Rounded.School,
                        shape = MaterialShapes.Cookie12Sided.toShape(),
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                        size = 56.dp,
                    )
                    Column {
                        Text(
                            stringResource(R.string.more_settings_app_name),
                            style = MaterialTheme.typography.titleLargeEmphasized,
                        )
                        val version = remember {
                            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
                        }
                        // Скрытый вход в отладочную консоль API: 7 нажатий на версию.
                        // null в релизе — вход закрыт.
                        var versionTaps by remember { mutableIntStateOf(0) }
                        Text(
                            stringResource(R.string.more_settings_version, version.orEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = if (onOpenApiConsole != null) Modifier.clickable(
                                interactionSource = null,
                                indication = null,
                            ) {
                                if (++versionTaps >= 7) {
                                    versionTaps = 0
                                    onOpenApiConsole()
                                }
                            } else Modifier,
                        )
                    }
                }
                Text(
                    stringResource(R.string.more_settings_about_text),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Spacing.l),
                )
                Text(
                    stringResource(R.string.more_settings_about_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = Spacing.s),
                )
            }
        }
    }
}

/** Слитная группа строк; [offset] — сколько элементов группы уже выведено выше. */
internal fun LazyListScope.group(rows: List<@Composable (Shape) -> Unit>, offset: Int = 0) {
    val total = rows.size + offset
    itemsIndexed(rows) { index, row -> row(groupShape(index + offset, total)) }
}

@Composable
internal fun SwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: Shape,
    enabled: Boolean = true,
) {
    // Вся строка — один переключатель: TalkBack видит один элемент «Переключатель, вкл/выкл».
    MesListItem(
        headline = title,
        supporting = subtitle,
        icon = icon,
        enabled = enabled,
        shape = shape,
        modifier = Modifier
            .clip(shape)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                thumbContent = if (checked) {
                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                } else {
                    null
                },
            )
        },
    )
}

/** Ввод PIN дважды (4–8 цифр). */
@Composable
private fun PinSetupDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val tooShort = first.length < 4
    val mismatch = second.isNotEmpty() && second != first

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.more_settings_pin_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedTextField(
                    value = first,
                    onValueChange = { v -> first = v.filter(Char::isDigit).take(8) },
                    label = { Text(stringResource(R.string.more_settings_pin_new)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                OutlinedTextField(
                    value = second,
                    onValueChange = { v -> second = v.filter(Char::isDigit).take(8) },
                    label = { Text(stringResource(R.string.more_settings_pin_repeat)) },
                    singleLine = true,
                    isError = mismatch,
                    supportingText = if (mismatch) {
                        { Text(stringResource(R.string.more_settings_pin_mismatch)) }
                    } else {
                        null
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(first) },
                enabled = !tooShort && second == first,
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(R.string.more_action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.more_action_cancel)) }
        },
        icon = { Icon(Icons.Rounded.Pin, contentDescription = null) },
    )
}
