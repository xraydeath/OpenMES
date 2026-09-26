package ru.openmes.core.data

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import okhttp3.OkHttpClient
import ru.openmes.core.network.AuthHttpClient
import ru.openmes.core.network.BaseHttpClient
import ru.openmes.core.network.CachedMealsApi
import ru.openmes.core.network.CachedMesApi
import ru.openmes.core.network.CachedPortalApi
import ru.openmes.core.network.api.MealsApi
import ru.openmes.core.network.api.MeshAuthApi
import ru.openmes.core.network.api.MesApi
import ru.openmes.core.network.api.PortalApi
import ru.openmes.core.network.api.SudirApi
import ru.openmes.core.network.interceptor.OfflineCache
import ru.openmes.core.network.interceptor.TokenProvider
import java.io.File

/** Koin-граф слоя данных (API-инстансы живут в networkModule). */
val dataModule: Module = module {

    single<TokenStore> { KeystoreTokenStore(androidContext()) }
    single<TokenProvider> { TokenProviderImpl(get()) }

    single { OfflineCache(File(androidContext().filesDir, "offline_cache")) }

    single<SettingsRepository> { DataStoreSettingsRepository(androidContext()) }

    single<SessionRepository> {
        SudirSessionRepository(
            sudirApi = get<SudirApi>(),
            meshAuthApi = get<MeshAuthApi>(),
            mesApi = get<MesApi>(),
            tokenStore = get(),
            context = androidContext(),
            offlineCache = get(),
        )
    }

    single<DiaryRepository> {
        MesDiaryRepository(
            get(), get(), offlineCache = get(),
            cached = MesDiaryRepository(get(CachedMesApi), get()),
            httpClient = get<OkHttpClient>(BaseHttpClient),
        )
    }

    single<FoodRepository> {
        MealsFoodRepository(
            get<MealsApi>(), get(),
            cached = MealsFoodRepository(get(CachedMealsApi), get(), memoryCache = false),
            offlineCache = get(),
        )
    }

    single<CollegeRepository> {
        MesCollegeRepository(
            get<MesApi>(), get<PortalApi>(), get(), offlineCache = get(),
            cached = MesCollegeRepository(get(CachedMesApi), get(CachedPortalApi), get(), memoryCache = false),
        )
    }

    single { ApiConsoleRepository(get(AuthHttpClient), get(), get()) }
}
