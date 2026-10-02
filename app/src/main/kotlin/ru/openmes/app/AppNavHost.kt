package ru.openmes.app

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import ru.openmes.core.data.NotificationHistory
import ru.openmes.core.data.Session
import ru.openmes.core.data.SessionRepository
import ru.openmes.core.designsystem.components.LocalMesEdgeFade
import ru.openmes.core.designsystem.components.LocalPullToRefreshEnabled
import ru.openmes.core.designsystem.components.MesContentMaxWidth
import ru.openmes.core.designsystem.components.MesDock
import ru.openmes.core.designsystem.components.MesBottomScrim
import ru.openmes.core.designsystem.components.MesDockDestination
import ru.openmes.core.designsystem.components.MesSnackbarHost
import ru.openmes.core.designsystem.components.openUrl
import ru.openmes.core.designsystem.theme.Spacing
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
import ru.openmes.feature.more.NotificationHistoryScreen
import ru.openmes.feature.more.PortfolioScreen
import ru.openmes.feature.more.ProforientationScreen
import ru.openmes.feature.more.SchoolInfoScreen
import ru.openmes.feature.more.MoreScreen
import ru.openmes.feature.more.SettingsScreen
import ru.openmes.feature.more.CacheSettingsScreen
import ru.openmes.feature.more.NotificationSettingsScreen
import ru.openmes.app.notify.EveningReminders
import ru.openmes.app.notify.LessonReminders
import ru.openmes.app.notify.Notifications
import ru.openmes.feature.more.StudentCardScreen
import ru.openmes.feature.schedule.ScheduleScreen

private data class TopLevelDestination(
    val route: String,
    @StringRes override val label: Int,
    override val icon: ImageVector,
    override val unread: Boolean = false,
) : MesDockDestination

/** Репозиторий — кнопка «Исходный код» на экране входа. */
private const val SOURCE_URL = "https://github.com/xraydeath/OpenMES"

// Иконки дока скруглённые: в пилюле выбранный пункт отмечает подпись и индикатор, а не
// сменой формы иконки, поэтому она всегда залитая.
private val topLevelDestinations = listOf(
    TopLevelDestination("schedule", R.string.nav_schedule, Icons.Rounded.CalendarMonth),
    TopLevelDestination("marks", R.string.nav_marks, Icons.Rounded.Grade),
    TopLevelDestination("homework", R.string.nav_homework, Icons.AutoMirrored.Rounded.MenuBook),
    // История уведомлений — в доке, а не колокольчиком в шапке: колокольчик был
    // единственным элементом, стоявшим вне сетки экрана, и его ещё надо было
    // отдельно прятать на всех подразделах «Ещё».
    TopLevelDestination(HISTORY_ROUTE, R.string.nav_history, Icons.Rounded.Notifications),
    TopLevelDestination(MORE_ROUTE, R.string.nav_more, Icons.Rounded.Widgets),
)

/** Заголовки экранов для flexible top app bar. */
private val screenTitles = mapOf(
    "schedule" to R.string.title_schedule,
    "marks" to R.string.title_marks,
    "homework" to R.string.title_homework,
    HISTORY_ROUTE to R.string.title_notification_history,
    "more" to R.string.title_more,
    "attendance" to R.string.title_attendance,
    "visits" to R.string.title_visits,
    "student_card" to R.string.title_student_card,
    "food" to R.string.title_food,
    "news" to R.string.title_news,
    "news/{id}" to R.string.title_news_single,
    "school_info" to R.string.title_school_info,
    "proforientation" to R.string.title_proforientation,
    "portfolio" to R.string.title_portfolio,
    "settings" to R.string.title_settings,
    "cache_settings" to R.string.title_cache_settings,
    "notification_settings" to R.string.title_notification_settings,
    "api_console" to R.string.title_api_console,
)

private val authRoutes = setOf("login")

/**
 * Колокольчик истории — только на трёх рабочих вкладках (расписание, оценки, домашка).
 *
 * Раньше это был список запрещённых маршрутов, и под «Ещё» попадали только четыре из
 * десятка подэкранов: колокольчик вылезал то на «Посещениях», то на «Питании». Теперь
 * правило положительное — колокольчик есть везде, кроме самого «Ещё» и всех его потомков,
 * так что новый подэкран автоматически останется чистым.
 */
/** Маршрут «Ещё» — вкладка с разделами, которые не поместились в док. */
private const val MORE_ROUTE = "more"

/**
 * Маршрут истории уведомлений. Живёт в доке рядом с вкладками, но ведёт себя как
 * обычный экран: из него возвращает кнопка «назад» в шапке, а не док.
 */
private const val HISTORY_ROUTE = "notification_history"

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

    // Точка непрочитанного на пункте «История». Список пересобирается только когда
    // меняется сам факт непрочитанного, а не на каждый кадр и не на каждую запись.
    val history = koinInject<NotificationHistory>()
    val historyEntries by history.entries.collectAsStateWithLifecycle()
    val historySeen by history.lastSeenMillis.collectAsStateWithLifecycle()
    val dockDestinations = remember(historyEntries, historySeen) {
        topLevelDestinations.map {
            if (it.route == HISTORY_ROUTE) {
                it.copy(unread = historyEntries.any { entry -> entry.timeMillis > historySeen })
            } else {
                it
            }
        }
    }

    // null на подэкранах: док там не нужен, там своя кнопка «назад» в заголовке.
    // Ищем именно в dockDestinations: у этого списка есть unread, и пункт из
    // topLevelDestinations по равенству с ним не совпал бы — подсветка бы пропала.
    val selectedDestination = dockDestinations.firstOrNull { it.route == currentRoute }
    val showBottomBar = selectedDestination != null
    val title = screenTitles[currentRoute]?.let { stringResource(it) }

    // У каждого экрана своё состояние сворачивания заголовка.
    val appBarState = remember(currentRoute) { TopAppBarState(-Float.MAX_VALUE, 0f, 0f) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(appBarState)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { MesSnackbarHost() },
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
        ) { padding ->
        // Pull-to-refresh — только при полностью развёрнутом заголовке.
        // Лямбда стабильна на маршрут — иначе static local перекомпоновывал бы весь NavHost.
        val refreshEnabled: () -> Boolean = remember(scrollBehavior) {
            { scrollBehavior.state.collapsedFraction == 0f }
        }
        // Растворение контента у верхней кромки: едет на свёрнутости панели, то есть
        // появляется вместе с прокруткой и уходит вместе с ней. Саму полосу рисуют экраны —
        // на своём скролл-контейнере (см. MesEdgeFade).
        val edgeFade: () -> Float = remember(scrollBehavior) {
            { scrollBehavior.state.collapsedFraction.coerceIn(0f, 1f) }
        }
        val offlineCache = koinInject<OfflineCache>()
        val offlineDataTime by offlineCache.offlineDataTime.collectAsStateWithLifecycle()
        CompositionLocalProvider(
            LocalPullToRefreshEnabled provides refreshEnabled,
            LocalMesEdgeFade provides edgeFade,
        ) {
          // На планшете и в альбомной ориентации колонка не растягивается во всю ширину:
          // строки длиннее ~840 dp читать неудобно, а док уехал бы к краям.
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            // Нижний инсет отдаём экранам: контент должен уезжать под док и под системную
            // навигацию, а полоса MesBottomScrim закрывает обоих. Scaffold этот инсет съедал,
            // и навбар оставался голым — без него не видно, что под ним что-то едет.
            // Подэкраны (где дока нет) инсет по-прежнему получают обычным отступом.
            val bottomInset = if (showBottomBar) 0.dp else padding.calculateBottomPadding()
            Column(
              Modifier
                  .padding(
                      top = padding.calculateTopPadding(),
                      bottom = bottomInset,
                  )
                  .fillMaxWidth()
                  .widthIn(max = MesContentMaxWidth),
            ) {
              AnimatedVisibility(offlineDataTime != null && currentRoute !in authRoutes) {
                  OfflineBanner(offlineDataTime ?: 0L)
              }
              NavHost(
                  navController = navController,
                  startDestination = "schedule",
                  modifier = Modifier
                      .weight(1f)
                      .fillMaxWidth(),
                  // M3 fade-through: старый экран быстро гаснет, новый проявляется с лёгким зумом —
                  // без долгого наложения двух экранов (стандартный кроссфейд 700 мс «мерцает»).
                  enterTransition = { FadeThroughIn },
                  exitTransition = { FadeThroughOut },
                  popEnterTransition = { FadeThroughIn },
                  popExitTransition = { FadeThroughOut },
              ) {
                  composable("login") {
                      val context = LocalContext.current
                      LoginScreen(
                          viewModel = koinViewModel(),
                          onOpenSource = { context.openUrl(SOURCE_URL) },
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
                          // Отладочная консоль API — только в debug-сборках.
                          onOpenApiConsole = if (BuildConfig.DEBUG) {
                              { navController.navigate("api_console") }
                          } else null,
                      )
                  }

                  if (BuildConfig.DEBUG) composable("api_console") {
                      ApiConsoleScreen(viewModel = koinViewModel())
                  }

                  composable(HISTORY_ROUTE) {
                      NotificationHistoryScreen(viewModel = koinViewModel())
                  }

                  composable("notification_settings") {
                      val context = LocalContext.current
                      NotificationSettingsScreen(
                          viewModel = koinViewModel(),
                          onPreviewLesson = { LessonReminders.showPreview(context) },
                          onCheckEvening = { EveningReminders.runNow(context) },
                          onTestNotification = { Notifications.postSample(context, it) },
                      )
                  }

                  composable("cache_settings") {
                      CacheSettingsScreen(viewModel = koinViewModel())
                  }
                }
              }
            // Док висит поверх контента, а не занимает отдельную полосу Scaffold: списки
            // верхнего уровня сами оставляют снизу MesDockReservedHeight и потому уезжают
            // под него. Слот bottomBar для этого не годится — он наоборот отодвигает
            // содержимое, и под доком всегда оказывался бы пустой фон.
            if (selectedDestination != null) {
              // Полоса идёт под доком: навбар подсвечен ровно настолько, чтобы сквозь него
              // не читался обрезок карточки, а выше, где контент ещё видно, он тает.
              MesBottomScrim(modifier = Modifier.align(Alignment.BottomCenter))
              MesDock(
                destinations = dockDestinations,
                selected = selectedDestination,
                onSelect = { dest ->
                    when (dest.route) {
                        // История — экран, а не вкладка: её открывают поверх текущей
                        // вкладки, и назад из неё возвращает туда же, откуда пришли.
                        HISTORY_ROUTE -> navController.navigate(HISTORY_ROUTE) {
                            launchSingleTop = true
                        }
                        else -> {
                            // Перед переходом снимаем историю со стека. Иначе
                            // navigateToTopLevel сохранит её вместе с уходящей
                            // вкладкой, и restoreState вернёт историю поверх неё
                            // же — экран откроется сам при нажатии на соседний пункт.
                            navController.popBackStack(HISTORY_ROUTE, inclusive = true)
                            navController.navigateToTopLevel(dest.route)
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
              )
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
                        .padding(start = Spacing.l),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    FilledTonalIconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back_cd),
                        )
                    }
                }
            }
            // Действия — правый верхний угол, как в M3 top app bar: видны и свёрнуто, и развёрнуто.
            // Поле Spacing.l — то же, что у заголовка и у списков, поэтому кнопки шапки
            // встают в одну линию с контентом, а не вылезают правее него.
            Text(
                title,
                style = bigStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(end = Spacing.l)
                    .graphicsLayer {
                        val f = state.collapsedFraction
                        val scale = lerp(1f, endScale, f)
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        val startX = Spacing.l.toPx()
                        // Свёрнуто заголовок уходит за кнопку «назад», но не под неё:
                        // поле, ширина кнопки и ещё одно поле.
                        val endX = if (showBack) (Spacing.l * 2 + BarActionWidth).toPx() else startX
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

/** Ширина кнопки в шапке — стандартная для M3 icon button. */
private val BarActionWidth = 40.dp

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
            .padding(horizontal = Spacing.l, vertical = Spacing.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = 10.dp),
        ) {
            Icon(Icons.Rounded.CloudOff, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.m))
            Text(
                stringResource(R.string.offline_banner, time),
                style = MaterialTheme.typography.labelLarge,
            )
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
