package ru.openmes.feature.more

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.BakeryDining
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsScore
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.openmes.core.data.Session
import ru.openmes.core.designsystem.components.ConnectedChoiceGroup
import ru.openmes.core.designsystem.components.GroupGap
import ru.openmes.core.designsystem.components.HeroCard
import ru.openmes.core.designsystem.components.MesListItem
import ru.openmes.core.designsystem.components.SectionHeader
import ru.openmes.core.designsystem.components.clipToMaterialShape
import ru.openmes.core.designsystem.components.groupShape

/** Раздел в списке «Ещё». */
private data class Service(
    val icon: ImageVector,
    val title: String,
    val subtitle: String? = null,
    val onClick: (() -> Unit)? = null,
)

/** Роадмап переноса из оригинала. */
private val upcomingServices = listOf(
    Service(Icons.Rounded.Balance, "Финансы", "Баланс, пополнение, история"),
    Service(Icons.Rounded.WorkspacePremium, "Портфолио", "Достижения и олимпиады"),
    Service(Icons.Rounded.Extension, "Кружки", "Каталог и запись"),
    Service(Icons.Rounded.Celebration, "Геймификация", "Звёзды, подарки, турниры"),
    Service(Icons.Rounded.QrCode2, "Москвёнок", "QR-проход и идентификаторы"),
    Service(Icons.AutoMirrored.Rounded.Chat, "Чаты", "Сферум (VK/MAX)"),
    Service(Icons.Rounded.Description, "Справки", "Электронные справки"),
    Service(Icons.Rounded.Notifications, "Уведомления", "Настройки пушей"),
    Service(Icons.Rounded.CalendarMonth, "Календарь", "События и мероприятия"),
    Service(Icons.Rounded.Map, "Карта", "Школы и кружки рядом"),
    Service(Icons.Rounded.Person, "Психолог", "Запись к специалисту"),
)

/** Фигуры иконок доступных разделов — по одной на строку, для ритма. */
private val serviceShapes = listOf(
    MaterialShapes.Cookie6Sided,
    MaterialShapes.Sunny,
    MaterialShapes.Cookie9Sided,
)

/**
 * Экран «Ещё» — карта всех разделов оригинального «Колледжа МЭШ»:
 * доступные включены, остальные помечены «скоро» (роадмап переноса).
 */
@Composable
fun MoreScreen(
    viewModel: MoreViewModel,
    onOpenSettings: () -> Unit,
    onOpenAttendance: () -> Unit,
    onOpenStudentCard: () -> Unit,
    onOpenFood: () -> Unit,
    onOpenNews: () -> Unit,
    onOpenSchoolInfo: () -> Unit,
    onOpenProforientation: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val avatar by viewModel.avatar.collectAsStateWithLifecycle()
    val loggedIn = session as? Session.LoggedIn

    val available = listOf(
        Service(Icons.Rounded.SportsScore, "Посещаемость", "Пропуски с начала учебного года", onOpenAttendance),
        Service(Icons.Rounded.Badge, "Студенческий билет", "Электронный билет колледжа", onOpenStudentCard),
        Service(Icons.Rounded.BakeryDining, "Питание", "Меню столовой и буфета", onOpenFood),
        Service(Icons.Rounded.Campaign, "Новости", "Лента school.mos.ru", onOpenNews),
        Service(Icons.Rounded.AccountBalance, "О колледже", "Контакты, кураторы, корпуса", onOpenSchoolInfo),
        Service(Icons.Rounded.Work, "Профориентация", "Тест, отрасли, дни открытых дверей", onOpenProforientation),
        Service(Icons.Rounded.Book, "Библиотека МЭШ", "Учебники и материалы", onOpenLibrary),
        Service(Icons.Rounded.Settings, "Настройки", "Тема, PIN-код, уведомления", onOpenSettings),
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(GroupGap),
    ) {
        item { ProfileCard(loggedIn, avatar, onLogout = viewModel::logout) }

        // Выбор ребёнка (для родителей с несколькими детьми)
        if (loggedIn != null && loggedIn.children.size > 1) {
            item { SectionHeader("Профиль ученика", Modifier.padding(top = 12.dp)) }
            item {
                val current = loggedIn.children.firstOrNull { it.id == loggedIn.currentChild?.id }
                    ?: loggedIn.children.first()
                ConnectedChoiceGroup(
                    options = loggedIn.children,
                    selected = current,
                    onSelect = { viewModel.selectChild(it.id) },
                    label = { it.firstName.ifBlank { it.lastName } },
                    fill = loggedIn.children.size <= 3,
                )
            }
        }

        item { SectionHeader("Разделы", Modifier.padding(top = 12.dp)) }
        itemsIndexed(available) { index, service ->
            MesListItem(
                headline = service.title,
                supporting = service.subtitle,
                icon = service.icon,
                iconShape = serviceShapes[index % serviceShapes.size].toShape(),
                iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = service.onClick,
                shape = groupShape(index, available.size),
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }

        item { SectionHeader("Скоро", Modifier.padding(top = 12.dp)) }
        itemsIndexed(upcomingServices) { index, service ->
            MesListItem(
                headline = service.title,
                supporting = service.subtitle,
                icon = service.icon,
                enabled = false,
                shape = groupShape(index, upcomingServices.size),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            )
        }

        item {
            Text(
                text = "OpenMES — неофициальный открытый клиент.\nВсе разделы оригинала переносятся постепенно — следите за релизами.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
            )
        }
    }
}

@Composable
private fun ProfileCard(
    session: Session.LoggedIn?,
    avatar: ImageBitmap?,
    onLogout: () -> Unit,
) {
    val person = session?.person
    HeroCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clipToMaterialShape(MaterialShapes.Cookie9Sided.toShape())
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                if (avatar != null) {
                    Image(
                        bitmap = avatar,
                        contentDescription = "Аватар",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = person?.firstName?.firstOrNull()?.toString() ?: "?",
                        style = MaterialTheme.typography.headlineMediumEmphasized,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = person?.fullName?.takeIf { it.isNotBlank() } ?: "Профиль mos.ru",
                    style = MaterialTheme.typography.titleLargeEmphasized,
                )
                session?.currentChild?.let { child ->
                    Text(
                        text = listOfNotNull(child.className, child.schoolName).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
        }
        OutlinedButton(
            onClick = onLogout,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.Logout,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text("Выйти")
        }
    }
}
