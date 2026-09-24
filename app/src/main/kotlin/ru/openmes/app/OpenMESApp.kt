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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import ru.openmes.core.data.SessionRepository
import ru.openmes.core.data.SettingsRepository
import ru.openmes.core.data.dataModule
import ru.openmes.core.network.BaseHttpClient
import ru.openmes.core.network.interceptor.TokenAuthenticator
import ru.openmes.core.network.networkModule
import ru.openmes.feature.auth.LoginViewModel
import ru.openmes.feature.homework.HomeworkViewModel
import ru.openmes.feature.marks.MarksViewModel
import ru.openmes.feature.more.AttendanceViewModel
import ru.openmes.feature.more.FoodViewModel
import ru.openmes.feature.more.NewsDetailViewModel
import ru.openmes.feature.more.NewsViewModel
import ru.openmes.feature.more.ProforientationViewModel
import ru.openmes.feature.more.SchoolInfoViewModel
import ru.openmes.feature.more.MoreViewModel
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
        wireTokenRefresh()
        wireMarksPolling()
        CacheRefreshWorker.schedule(this)
        ApiProbe.runIfRequested(this, GlobalContext.get().get()) // ВРЕМЕННО
    }

    /** Картинки новостей грузим через общий OkHttp-клиент, а не отдельный, который Coil создал бы сам. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { GlobalContext.get().get<OkHttpClient>(BaseHttpClient) }))
            }
            .build()

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
    viewModel { AttendanceViewModel(get(), get()) }
    viewModel { StudentCardViewModel(get(), get(), get()) }
    viewModel { FoodViewModel(get(), get()) }
    viewModel { NewsViewModel(get(), get()) }
    viewModel { (id: Long) -> NewsDetailViewModel(id, get()) }
    viewModel { SchoolInfoViewModel(get(), get()) }
    viewModel { ProforientationViewModel(get(), get()) }
}
