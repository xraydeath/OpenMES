package ru.openmes.feature.more

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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

/**
 * Настройки: тема (в духе expressive), Material You, уведомления, PIN/биометрия, о приложении.
 */
@Composable
fun SettingsScreen(viewModel: MoreViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pinDialog by remember { mutableStateOf(false) }
    val biometricAvailable = remember {
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }
    // Android 13+: без разрешения уведомления молча не показываются.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.setMarksNotifications(true) }
    val enableNotifications: (Boolean) -> Unit = { enabled ->
        val needPermission = enabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needPermission) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.setMarksNotifications(enabled)
        }
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

    // Строки групп собираем списками: форма зависит от позиции в группе.
    val appearance = listOf<@Composable (Shape) -> Unit>(
        { shape ->
            SwitchItem(
                icon = Icons.Rounded.Palette,
                title = "Динамический цвет",
                subtitle = "Material You: палитра из обоев (Android 12+)",
                checked = settings.dynamicColor,
                onCheckedChange = viewModel::setDynamicColor,
                shape = shape,
            )
        },
    )
    val notifications = listOf<@Composable (Shape) -> Unit>(
        { shape ->
            SwitchItem(
                icon = Icons.Rounded.NotificationsActive,
                title = "Новые оценки",
                subtitle = "Проверять дневник в фоне примерно раз в час",
                checked = settings.marksNotifications,
                onCheckedChange = enableNotifications,
                shape = shape,
            )
        },
        { shape ->
            SwitchItem(
                icon = Icons.Rounded.VisibilityOff,
                title = "Скрывать значение оценки",
                subtitle = "В уведомлении будет только предмет",
                checked = settings.hideMarkValues,
                enabled = settings.marksNotifications,
                onCheckedChange = viewModel::setHideMarkValues,
                shape = shape,
            )
        },
    )
    val security = buildList<@Composable (Shape) -> Unit> {
        add { shape ->
            SwitchItem(
                icon = Icons.Rounded.Pin,
                title = "PIN-код",
                subtitle = "При запуске и после минуты в фоне",
                checked = settings.pinEnabled,
                onCheckedChange = { enabled -> if (enabled) pinDialog = true else viewModel.setPin(null) },
                shape = shape,
            )
        }
        if (biometricAvailable) {
            add { shape ->
                SwitchItem(
                    icon = Icons.Rounded.Fingerprint,
                    title = "Биометрия",
                    subtitle = "Разблокировка отпечатком или лицом",
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
                    headline = "Сменить PIN-код",
                    icon = Icons.Rounded.Password,
                    onClick = { pinDialog = true },
                    shape = shape,
                )
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(GroupGap),
    ) {
        item { SectionHeader("Оформление") }
        item {
            MesCard(shape = groupShape(0, appearance.size + 1)) {
                Text("Тема", style = MaterialTheme.typography.titleMedium)
                ConnectedChoiceGroup(
                    options = ThemeMode.entries,
                    selected = settings.themeMode,
                    onSelect = viewModel::setThemeMode,
                    label = {
                        when (it) {
                            ThemeMode.SYSTEM -> "Система"
                            ThemeMode.LIGHT -> "Светлая"
                            ThemeMode.DARK -> "Тёмная"
                        }
                    },
                    icon = {
                        when (it) {
                            ThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto
                            ThemeMode.LIGHT -> Icons.Rounded.LightMode
                            ThemeMode.DARK -> Icons.Rounded.DarkMode
                        }
                    },
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        group(appearance, offset = 1)

        item { SectionHeader("Уведомления", Modifier.padding(top = 12.dp)) }
        group(notifications)

        item { SectionHeader("Безопасность", Modifier.padding(top = 12.dp)) }
        group(security)

        item { SectionHeader("О приложении", Modifier.padding(top = 12.dp)) }
        item {
            HeroCard(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ShapeIcon(
                        icon = Icons.Rounded.School,
                        shape = MaterialShapes.Cookie12Sided.toShape(),
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                        size = 56.dp,
                    )
                    Column {
                        Text("OpenMES", style = MaterialTheme.typography.titleLargeEmphasized)
                        val version = remember {
                            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
                        }
                        Text("Версия ${version.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    "Открытый клиент «Колледжа МЭШ» на Material Design 3 Expressive.\n" +
                        "Данные получаются только с ваших аккаунтов через официальные API school.mos.ru.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    "Не является официальным приложением Департамента образования и науки города Москвы.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** Слитная группа строк; [offset] — сколько элементов группы уже выведено выше. */
private fun LazyListScope.group(rows: List<@Composable (Shape) -> Unit>, offset: Int = 0) {
    val total = rows.size + offset
    itemsIndexed(rows) { index, row -> row(groupShape(index + offset, total)) }
}

@Composable
private fun SwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: Shape,
    enabled: Boolean = true,
) {
    MesListItem(
        headline = title,
        supporting = subtitle,
        icon = icon,
        enabled = enabled,
        shape = shape,
        onClick = { onCheckedChange(!checked) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
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
        title = { Text("PIN-код") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = first,
                    onValueChange = { v -> first = v.filter(Char::isDigit).take(8) },
                    label = { Text("Новый PIN (4–8 цифр)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                OutlinedTextField(
                    value = second,
                    onValueChange = { v -> second = v.filter(Char::isDigit).take(8) },
                    label = { Text("Повторите PIN") },
                    singleLine = true,
                    isError = mismatch,
                    supportingText = if (mismatch) {
                        { Text("PIN-коды не совпадают") }
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
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Отмена") }
        },
        icon = { Icon(Icons.Rounded.Pin, contentDescription = null) },
    )
}
