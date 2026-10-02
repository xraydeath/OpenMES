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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.DoorFront
import androidx.compose.material.icons.rounded.BakeryDining
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsScore
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.openmes.core.data.Session
import ru.openmes.feature.more.R
import ru.openmes.core.designsystem.components.ConnectedChoiceGroup
import ru.openmes.core.designsystem.components.GroupGap
import ru.openmes.core.designsystem.components.HeroCard
import ru.openmes.core.designsystem.components.mesDockReservedHeight
import ru.openmes.core.designsystem.components.mesFadeTop
import ru.openmes.core.designsystem.components.MesListItem
import ru.openmes.core.designsystem.components.SectionHeader
import ru.openmes.core.designsystem.components.clipToMaterialShape
import ru.openmes.core.designsystem.components.groupShape
import ru.openmes.core.designsystem.theme.Spacing

/** Раздел в списке «Ещё». */
private data class Service(
    val icon: ImageVector,
    val title: String,
    val subtitle: String? = null,
    val onClick: () -> Unit,
)

/**
 * Предельная ширина контента. На телефоне не действует, на планшете и в альбомной
 * ориентации удерживает колонку читаемой, а нижнюю навигацию — по центру.
 * По гайдлайнам MD3 для Large (1200 dp+) — 840…1040 dp.
 */
/** Фигуры иконок доступных разделов — по одной на строку, для ритма. */
private val serviceShapes = listOf(
    MaterialShapes.Cookie6Sided,
    MaterialShapes.Sunny,
    MaterialShapes.Cookie9Sided,
)

/** Экран «Ещё» — разделы, которые не помещаются в нижнюю навигацию. */
@Composable
fun MoreScreen(
    viewModel: MoreViewModel,
    onOpenSettings: () -> Unit,
    onOpenAttendance: () -> Unit,
    onOpenVisits: () -> Unit,
    onOpenStudentCard: () -> Unit,
    onOpenFood: () -> Unit,
    onOpenNews: () -> Unit,
    onOpenSchoolInfo: () -> Unit,
    onOpenProforientation: () -> Unit,
    onOpenPortfolio: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val avatar by viewModel.avatar.collectAsStateWithLifecycle()
    val loggedIn = session as? Session.LoggedIn

    val available = listOf(
        Service(Icons.Rounded.SportsScore, stringResource(R.string.more_service_attendance), stringResource(R.string.more_service_attendance_sub), onOpenAttendance),
        Service(Icons.Rounded.DoorFront, stringResource(R.string.more_service_visits), stringResource(R.string.more_service_visits_sub), onOpenVisits),
        Service(Icons.Rounded.Badge, stringResource(R.string.more_service_card), stringResource(R.string.more_service_card_sub), onOpenStudentCard),
        Service(Icons.Rounded.BakeryDining, stringResource(R.string.more_service_food), stringResource(R.string.more_service_food_sub), onOpenFood),
        Service(Icons.Rounded.Campaign, stringResource(R.string.more_service_news), stringResource(R.string.more_service_news_sub), onOpenNews),
        Service(Icons.Rounded.AccountBalance, stringResource(R.string.more_service_school), stringResource(R.string.more_service_school_sub), onOpenSchoolInfo),
        Service(Icons.Rounded.WorkspacePremium, stringResource(R.string.more_service_portfolio), stringResource(R.string.more_service_portfolio_sub), onOpenPortfolio),
        Service(Icons.Rounded.Work, stringResource(R.string.more_service_prof), stringResource(R.string.more_service_prof_sub), onOpenProforientation),
        Service(Icons.Rounded.Book, stringResource(R.string.more_service_library), stringResource(R.string.more_service_library_sub), onOpenLibrary),
        Service(Icons.Rounded.Settings, stringResource(R.string.more_service_settings), stringResource(R.string.more_service_settings_sub), onOpenSettings),
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .mesFadeTop(),
        contentPadding = PaddingValues(
            start = Spacing.l,
            end = Spacing.l,
            top = Spacing.s,
            bottom = Spacing.xl + mesDockReservedHeight(),
        ),
        verticalArrangement = Arrangement.spacedBy(GroupGap),
    ) {
        item { ProfileCard(loggedIn, avatar, onLogout = viewModel::logout) }

        // Выбор ребёнка (для родителей с несколькими детьми)
        if (loggedIn != null && loggedIn.children.size > 1) {
            item { SectionHeader(stringResource(R.string.more_child_section), Modifier.padding(top = Spacing.m)) }
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

        item { SectionHeader(stringResource(R.string.more_sections_header), Modifier.padding(top = Spacing.m)) }
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

        item {
            Text(
                text = stringResource(R.string.more_profile_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.xl),
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
            horizontalArrangement = Arrangement.spacedBy(Spacing.l),
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
                        contentDescription = stringResource(R.string.more_avatar_cd),
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
                    text = person?.fullName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.more_profile_mosru),
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
        // Не OutlinedButton: его контур рисуется цветом outline, а на карточке
        // primaryContainer он почти совпадает с фоном — кнопка выглядела частью карточки.
        // Залитая primary на primaryContainer контрастит и остаётся читаемой в обеих темах.
        Button(
            onClick = onLogout,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.padding(top = Spacing.l),
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.Logout,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.more_logout))
        }
    }
}
