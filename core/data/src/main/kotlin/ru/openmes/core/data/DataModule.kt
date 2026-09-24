package ru.openmes.core.data

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import ru.openmes.core.network.CachedMesApi
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
        MesDiaryRepository(get(), get(), cached = MesDiaryRepository(get(CachedMesApi), get()))
    }

    single<FoodRepository> { MealsFoodRepository(get<MealsApi>(), get()) }

    single<CollegeRepository> { MesCollegeRepository(get<MesApi>(), get<PortalApi>(), get()) }
}
