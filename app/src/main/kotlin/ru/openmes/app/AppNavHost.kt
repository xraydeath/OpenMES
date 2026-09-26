package ru.openmes.app

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Grade
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Grade
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.util.Consumer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import ru.openmes.core.data.Session
import ru.openmes.core.data.SessionRepository
import ru.openmes.core.designsystem.components.LocalPullToRefreshEnabled
import ru.openmes.core.network.interceptor.OfflineCache
import ru.openmes.feature.auth.LoginScreen
import ru.openmes.feature.homework.HomeworkScreen
import ru.openmes.feature.homework.openLibrary
import ru.openmes.feature.marks.MarksScreen
import ru.openmes.feature.more.ApiConsoleScreen
import ru.openmes.feature.more.AttendanceScreen
import ru.openmes.feature.more.VisitsScreen
import ru.openmes.feature.more.FoodScreen
import ru.openmes.feature.more.NewsDetailScreen
import ru.openmes.feature.more.NewsScreen
import ru.openmes.feature.more.PortfolioScreen
import ru.openmes.feature.more.ProforientationScreen
import ru.openmes.feature.more.SchoolInfoScreen
import ru.openmes.feature.more.MoreScreen
import ru.openmes.feature.more.SettingsScreen
import ru.openmes.feature.more.CacheSettingsScreen
import ru.openmes.feature.more.NotificationSettingsScreen
import ru.openmes.app.notify.EveningReminders
import ru.openmes.app.notify.LessonReminders
import ru.openmes.feature.more.StudentCardScreen
import ru.openmes.feature.schedule.ScheduleScreen

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val topLevelDestinations = listOf(
    TopLevelDestination("schedule", "Расписание", Icons.Outlined.CalendarMonth, Icons.Rounded.CalendarMonth),
    TopLevelDestination("marks", "Оценки", Icons.Outlined.Grade, Icons.Rounded.Grade),
    TopLevelDestination("homework", "Домашка", Icons.AutoMirrored.Rounded.MenuBook, Icons.AutoMirrored.Rounded.MenuBook),
    TopLevelDestination("more", "Ещё", Icons.Outlined.Widgets, Icons.Rounded.Widgets),
)

/** Заголовки экранов для flexible top app bar. */
private val screenTitles = mapOf(
    "schedule" to "Расписание",
    "marks" to "Оценки",
    "homework" to "Домашние задания",
    "more" to "Ещё",
    "attendance" to "Посещаемость",
    "visits" to "Проходы",
    "student_card" to "Студенческий билет",
    "food" to "Питание",
    "news" to "Новости",
    "news/{id}" to "Новость",
    "school_info" to "О колледже",
    "proforientation" to "Профориентация",
    "portfolio" to "Портфолио",
    "settings" to "Настройки",
    "cache_settings" to "Кэш и офлайн",
    "notification_settings" to "Уведомления",
    "api_console" to "Консоль API",
)

private val authRoutes = setOf("login")

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val sessionRepository = koinInject<SessionRepository>()
    val session by sessionRepository.session.collectAsStateWithLifecycle()

    // Deeplink из браузера: diary-po://oauth2redirect?code=…&state=…
    AuthDeepLinkHandler(sessionRepository)

    // Реакция на смену сессии: логаут → логин, вход → главная
    LaunchedEffect(session) {
        val currentRoute = navController.currentDestination?.route
        when (session) {
            is Session.LoggedOut -> if (currentRoute !in authRoutes) {
                navController.navigate("login") { popUpTo(0) { inclusive = true } }
            }

            is Session.LoggedIn -> if (currentRoute == null || currentRoute in authRoutes) {
                navController.navigate("schedule") { popUpTo(0) { inclusive = true } }
            }

            Session.Loading -> Unit
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in topLevelDestinations.map { it.route }
    val title = screenTitles[currentRoute]

    // У каждого экрана своё состояние сворачивания заголовка.
    val appBarState = remember(currentRoute) { TopAppBarState(-Float.MAX_VALUE, 0f, 0f) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(appBarState)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (title != null) {
                CollapsingTopBar(
                    title = title,
                    showBack = !showBottomBar,
                    onBack = { navController.navigateUp() },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        bottomBar = {
            if (showBottomBar) {
                ShortNavigationBar {
                    topLevelDestinations.forEach { destination ->
                        val selected = currentRoute == destination.route
                        ShortNavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigateToTopLevel(destination.route)
                            },
                            icon = {
                                Icon(
                                    if (selected) destination.selectedIcon else destination.icon,
                                    contentDescription = null,
                                )
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // Pull-to-refresh — только при полностью развёрнутом заголовке.
        // Лямбда стабильна на маршрут — иначе static local перекомпоновывал бы весь NavHost.
        val refreshEnabled: () -> Boolean = remember(scrollBehavior) {
            { scrollBehavior.state.collapsedFraction == 0f }
        }
        val offlineCache = koinInject<OfflineCache>()
        val offlineDataTime by offlineCache.offlineDataTime.collectAsStateWithLifecycle()
        CompositionLocalProvider(LocalPullToRefreshEnabled provides refreshEnabled) {
          Column(Modifier.padding(padding)) {
            AnimatedVisibility(offlineDataTime != null && currentRoute !in authRoutes) {
                OfflineBanner(offlineDataTime ?: 0L)
            }
            NavHost(
                navController = navController,
                startDestination = "schedule",
                modifier = Modifier.weight(1f),
                // M3 fade-through: старый экран быстро гаснет, новый проявляется с лёгким зумом —
                // без долгого наложения двух экранов (стандартный кроссфейд 700 мс «мерцает»).
                enterTransition = { FadeThroughIn },
                exitTransition = { FadeThroughOut },
                popEnterTransition = { FadeThroughIn },
                popExitTransition = { FadeThroughOut },
            ) {
                composable("login") {
                    LoginScreen(
                        viewModel = koinViewModel(),
                        onOpenSource = { /* TODO: ссылка на репозиторий */ },
                    )
                }

                composable("schedule") {
                    ScheduleScreen(viewModel = koinViewModel())
                }

                composable("marks") {
                    MarksScreen(viewModel = koinViewModel())
                }

                composable("homework") {
                    HomeworkScreen(viewModel = koinViewModel())
                }

                composable("more") {
                    val context = LocalContext.current
                    MoreScreen(
                        viewModel = koinViewModel(),
                        onOpenSettings = { navController.navigate("settings") },
                        onOpenAttendance = { navController.navigate("attendance") },
                        onOpenVisits = { navController.navigate("visits") },
                        onOpenStudentCard = { navController.navigate("student_card") },
                        onOpenFood = { navController.navigate("food") },
                        onOpenNews = { navController.navigate("news") },
                        onOpenSchoolInfo = { navController.navigate("school_info") },
                        onOpenProforientation = { navController.navigate("proforientation") },
                        onOpenPortfolio = { navController.navigate("portfolio") },
                        onOpenLibrary = { context.openLibrary() },
                    )
                }

                composable("attendance") {
                    AttendanceScreen(viewModel = koinViewModel())
                }

                composable("visits") {
                    VisitsScreen(viewModel = koinViewModel())
                }

                composable("student_card") {
                    StudentCardScreen(viewModel = koinViewModel())
                }

                composable("food") {
                    FoodScreen(viewModel = koinViewModel())
                }

                composable("news") {
                    NewsScreen(
                        viewModel = koinViewModel(),
                        onOpenNews = { id -> navController.navigate("news/$id") },
                    )
                }

                composable(
                    "news/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                ) { entry ->
                    val id = entry.arguments?.getLong("id") ?: 0L
                    NewsDetailScreen(viewModel = koinViewModel { parametersOf(id) })
                }

                composable("school_info") {
                    SchoolInfoScreen(viewModel = koinViewModel())
                }

                composable("proforientation") {
                    ProforientationScreen(viewModel = koinViewModel())
                }

                composable("portfolio") {
                    PortfolioScreen(viewModel = koinViewModel())
                }

                composable("settings") {
                    SettingsScreen(
                        viewModel = koinViewModel(),
                        onOpenCache = { navController.navigate("cache_settings") },
                        onOpenNotifications = { navController.navigate("notification_settings") },
                        onOpenApiConsole = { navController.navigate("api_console") },
                    )
                }

                composable("api_console") {
                    ApiConsoleScreen(viewModel = koinViewModel())
                }

                composable("notification_settings") {
                    val context = LocalContext.current
                    NotificationSettingsScreen(
                        viewModel = koinViewModel(),
                        onPreviewLesson = { LessonReminders.showPreview(context) },
                        onCheckEvening = { EveningReminders.runNow(context) },
                    )
                }

                composable("cache_settings") {
                    CacheSettingsScreen(viewModel = koinViewModel())
                }
            }
          }
        }
    }
}

/**
 * Заголовок вкладки: один и тот же текст плавно уменьшается и уезжает в строку
 * свёрнутой панели (и обратно), а не перещёлкивается между двумя заголовками.
 * Всё анимируется в фазах layout/draw — без рекомпозиции на каждый кадр прокрутки.
 */
@Composable
private fun CollapsingTopBar(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val state = scrollBehavior.state
    val density = LocalDensity.current
    val collapsedPx = with(density) { CollapsedBarHeight.toPx() }
    val expandedPx = with(density) { ExpandedBarHeight.toPx() }
    SideEffect {
        if (state.heightOffsetLimit != collapsedPx - expandedPx) state.heightOffsetLimit = collapsedPx - expandedPx
    }
    val bigStyle = MaterialTheme.typography.headlineMediumEmphasized
    val smallStyle = MaterialTheme.typography.titleLargeEmphasized
    val endScale = smallStyle.fontSize.value / bigStyle.fontSize.value

    Surface(color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier
                .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val height = (expandedPx + state.heightOffset).roundToInt().coerceIn(collapsedPx.roundToInt(), expandedPx.roundToInt())
                    val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                    layout(placeable.width, height) { placeable.place(0, 0) }
                },
        ) {
            if (showBack) {
                Box(
                    Modifier
                        .height(CollapsedBarHeight)
                        .padding(start = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    FilledTonalIconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                    }
                }
            }
            Text(
                title,
                style = bigStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(end = 16.dp)
                    .graphicsLayer {
                        val f = state.collapsedFraction
                        val scale = lerp(1f, endScale, f)
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        val startX = 16.dp.toPx()
                        val endX = if (showBack) 64.dp.toPx() else 16.dp.toPx()
                        translationX = lerp(startX, endX, f)
                        // Развёрнуто: отступ снизу 20dp; свёрнуто: центр строки 64dp.
                        val bottomExpanded = 20.dp.toPx()
                        val bottomCollapsed = CollapsedBarHeight.toPx() / 2 - size.height / 2
                        translationY = -lerp(bottomExpanded, bottomCollapsed, f)
                    },
            )
        }
    }
}

private val FadeThroughIn = fadeIn(tween(210, delayMillis = 90, easing = LinearOutSlowInEasing)) +
    scaleIn(tween(210, delayMillis = 90, easing = LinearOutSlowInEasing), initialScale = 0.96f)
private val FadeThroughOut = fadeOut(tween(90, easing = FastOutLinearInEasing))

private val CollapsedBarHeight = 64.dp
private val ExpandedBarHeight = 120.dp

/** Плашка «нет связи»: данные показаны из кэша, с временем их сохранения. */
@Composable
private fun OfflineBanner(savedAt: Long) {
    val time = remember(savedAt) {
        val dateTime = Instant.ofEpochMilli(savedAt).atZone(ZoneId.systemDefault())
        val pattern = if (dateTime.toLocalDate() == LocalDate.now()) "HH:mm" else "d MMM, HH:mm"
        dateTime.format(DateTimeFormatter.ofPattern(pattern, Locale.forLanguageTag("ru")))
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(Icons.Rounded.CloudOff, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text("Нет связи · данные от $time", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Приём OAuth-deeplink (diary-po://oauth2redirect): начальный intent
 * (приложение запущено ссылкой) и onNewIntent (приложение было живо).
 */
@Composable
private fun AuthDeepLinkHandler(sessionRepository: SessionRepository) {
    val activity = LocalContext.current as? ComponentActivity ?: return
    // Переживает пересоздание Activity и смерть процесса: система вернёт исходный intent со ссылкой.
    val launchLinkHandled = androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(false)
    }

    LaunchedEffect(activity) {
        val intent = activity.intent
        // Запуск из «Недавних» повторно доставляет старый intent с уже использованным code.
        val fromHistory = intent != null &&
            (intent.flags and android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (!launchLinkHandled.value && !fromHistory) {
            intent?.data
                ?.takeIf { it.scheme == "diary-po" }
                ?.let { sessionRepository.handleDeepLink(it.toString()) }
        }
        launchLinkHandled.value = true
        intent?.data = null // не обрабатываем повторно
    }

    DisposableEffect(activity) {
        val listener = Consumer<android.content.Intent> { intent ->
            intent?.data
                ?.takeIf { it.scheme == "diary-po" }
                ?.let { sessionRepository.handleDeepLink(it.toString()) }
        }
        activity.addOnNewIntentListener(listener)
        onDispose { activity.removeOnNewIntentListener(listener) }
    }
}

private fun NavHostController.navigateToTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
