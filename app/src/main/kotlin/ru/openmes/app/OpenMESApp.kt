package ru.openmes.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import ru.openmes.app.notify.EveningReminders
import ru.openmes.app.notify.LessonReminders
import ru.openmes.app.notify.ScheduleChangesWorker
import ru.openmes.core.data.SessionRepository
import ru.openmes.core.data.SettingsRepository
import ru.openmes.core.data.dataModule
import ru.openmes.core.network.BaseHttpClient
import ru.openmes.core.network.interceptor.OfflineCache
import ru.openmes.core.network.interceptor.TokenAuthenticator
import ru.openmes.core.network.networkModule
import ru.openmes.feature.auth.LoginViewModel
import ru.openmes.feature.homework.HomeworkViewModel
import ru.openmes.feature.marks.MarksViewModel
import ru.openmes.feature.more.AttendanceViewModel
import ru.openmes.feature.more.VisitsViewModel
import ru.openmes.feature.more.FoodViewModel
import ru.openmes.feature.more.NewsDetailViewModel
import ru.openmes.feature.more.NewsViewModel
import ru.openmes.feature.more.ProforientationViewModel
import ru.openmes.feature.more.SchoolInfoViewModel
import ru.openmes.feature.more.MoreViewModel
import ru.openmes.feature.more.CacheSettingsViewModel
import ru.openmes.feature.more.NotificationSettingsViewModel
import ru.openmes.feature.more.StudentCardViewModel
import ru.openmes.feature.schedule.ScheduleViewModel

class OpenMESApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@OpenMESApp)
            modules(
                networkModule,
                dataModule,
                appModule,
            )
        }
        wireCachePolicy()
        wireTokenRefresh()
        wireMarksPolling()
        wireWidgetRefresh()
        wireReminders()
    }

    /** Картинки новостей грузим через общий OkHttp-клиент, а не отдельный, который Coil создал бы сам. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { GlobalContext.get().get<OkHttpClient>(BaseHttpClient) }))
            }
            .build()

    /** Уход приложения в фон — расписание в кэше могло обновиться: перерисовать виджет. */
    private fun wireWidgetRefresh() {
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                CoroutineScope(Dispatchers.IO).launch {
                    ru.openmes.app.widget.ScheduleWidget.refresh(this@OpenMESApp)
                    LessonReminders.reschedule(this@OpenMESApp)
                }
            }
        })
    }

    /** Напоминания о парах и вечерние: будильники переставляются при каждой смене их настроек и при входе в аккаунт. */
    private fun wireReminders() {
        val koin = GlobalContext.get()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        koin.get<SettingsRepository>().settings
            .map { Triple(it.lessonReminders, it.lessonReminderMinutes, it.lessonRemindersDistanceOnly) }
            .distinctUntilChanged()
            .onEach { runCatching { LessonReminders.reschedule(this) } }
            .launchIn(scope)
        koin.get<SettingsRepository>().settings
            .map { Triple(it.homeworkReminders, it.testReminders, it.eveningReminderHour) }
            .distinctUntilChanged()
            .onEach { runCatching { EveningReminders.reschedule(this) } }
            .launchIn(scope)
        koin.get<SettingsRepository>().settings
            .map { it.scheduleChangeNotifications }
            .distinctUntilChanged()
            .onEach { enabled ->
                if (enabled) ScheduleChangesWorker.schedule(this) else ScheduleChangesWorker.cancel(this)
            }
            .launchIn(scope)
        koin.get<SessionRepository>().session
            .map { it is ru.openmes.core.data.Session.LoggedIn }
            .distinctUntilChanged()
            .onEach { runCatching { LessonReminders.reschedule(this) } }
            .launchIn(scope)
    }

    /** Фоновая проверка оценок включается/выключается из настроек. */
    private fun wireMarksPolling() {
        val settingsRepository = GlobalContext.get().get<SettingsRepository>()
        settingsRepository.settings
            .map { it.marksNotifications }
            .distinctUntilChanged()
            .onEach { enabled ->
                if (enabled) MarksPollWorker.schedule(this) else MarksPollWorker.cancel(this)
            }
            .launchIn(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    /**
     * Настройки кэша → OfflineCache и фоновое обновление. Первую политику читаем синхронно:
     * восстановление сессии сразу пишет в кэш, и выключенные разделы не должны туда попасть.
     */
    private fun wireCachePolicy() {
        val koin = GlobalContext.get()
        val settingsRepository = koin.get<SettingsRepository>()
        val offlineCache = koin.get<OfflineCache>()
        offlineCache.policy = runBlocking { settingsRepository.settings.first() }.cachePolicy
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settingsRepository.settings
            .map { it.cachePolicy }
            .distinctUntilChanged()
            .onEach { policy ->
                offlineCache.policy = policy
                offlineCache.cleanup()
            }
            .launchIn(scope)
        settingsRepository.settings
            .map { it.cacheEnabled && it.cacheBackgroundRefresh }
            .distinctUntilChanged()
            .onEach { enabled ->
                if (enabled) CacheRefreshWorker.schedule(this) else CacheRefreshWorker.cancel(this)
            }
            .launchIn(scope)
    }

    /**
     * Подключаем авто-рефреш токенов: TokenAuthenticator (сеть) ← SessionRepository (данные).
     * Делаем это после старта Koin, чтобы разорвать цикл зависимостей.
     */
    private fun wireTokenRefresh() {
        val koin = GlobalContext.get()
        val authenticator = koin.get<TokenAuthenticator>()
        val sessionRepository = koin.get<SessionRepository>()
        authenticator.onUnauthorized = {
            CoroutineScope(Dispatchers.IO).launch {
                sessionRepository.logout()
            }
        }
        authenticator.refreshSuspend = { sessionRepository.refreshTokens() }
    }
}

private val appModule = module {
    viewModel { LoginViewModel(get()) }
    viewModel { ScheduleViewModel(get(), get()) }
    viewModel { MarksViewModel(get(), get(), get()) }
    viewModel { HomeworkViewModel(get(), get()) }
    viewModel { MoreViewModel(get(), get(), get()) }
    viewModel { CacheSettingsViewModel(get(), get()) }
    viewModel { NotificationSettingsViewModel(get()) }
    viewModel { AttendanceViewModel(get(), get()) }
    viewModel { VisitsViewModel(get(), get()) }
    viewModel { StudentCardViewModel(get(), get(), get()) }
    viewModel { FoodViewModel(get(), get()) }
    viewModel { NewsViewModel(get(), get()) }
    viewModel { (id: Long) -> NewsDetailViewModel(id, get()) }
    viewModel { SchoolInfoViewModel(get(), get()) }
    viewModel { ProforientationViewModel(get(), get()) }
}
