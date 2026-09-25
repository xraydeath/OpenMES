package ru.openmes.core.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import ru.openmes.core.network.api.MealsApi
import ru.openmes.core.network.api.MesApi
import ru.openmes.core.network.api.MeshAuthApi
import ru.openmes.core.network.api.PortalApi
import ru.openmes.core.network.api.SudirApi
import ru.openmes.core.network.interceptor.AuthInterceptor
import ru.openmes.core.network.interceptor.CollegeRouting
import ru.openmes.core.network.interceptor.OfflineCacheInterceptor
import ru.openmes.core.network.interceptor.TokenAuthenticator
import ru.openmes.core.network.interceptor.TokenProvider
import java.util.concurrent.TimeUnit

val BaseHttpClient = named("baseHttpClient")
val AuthHttpClient = named("authHttpClient")

/** MesApi, который читает только офлайн-кэш (без сети) — для мгновенного показа сохранённых данных. */
val CachedMesApi = named("cachedMesApi")
val CachedMealsApi = named("cachedMealsApi")
val CachedPortalApi = named("cachedPortalApi")
private val CachedHttpClient = named("cachedHttpClient")

val networkModule: Module = module {

    single {
        kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }
    }

    // Логгер — отдельный объект, чтобы разместить его ПОСЛЕ AuthInterceptor в цепочке.
    // Тела ответов (в них токены SUDIR/МЭШ) пишем только в debug-сборке.
    single {
        HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.BASIC
            }
            redactHeader("Auth-Token")
            redactHeader("Authorization")
        }
    }

    // Базовый клиент (SUDIR, без МЭШ-заголовков).
    single(BaseHttpClient) {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(get<HttpLoggingInterceptor>())
            .build()
    }

    // Клиент МЭШ: офлайн-кэш → AuthInterceptor → CollegeRouting (роутинг путей колледжа) → логгер.
    // Ретраи 503 (eventcalendar лежит под нагрузкой) с экспоненциальной задержкой.
    // Строим из базового: общие пул соединений и потоки, TLS-сессии к school.mos.ru переиспользуются.
    single(AuthHttpClient) {
        val tokenProvider = get<TokenProvider>()
        get<OkHttpClient>(BaseHttpClient).newBuilder()
            .apply { interceptors().clear() }
            .addInterceptor(OfflineCacheInterceptor(get()))
            .addInterceptor(AuthInterceptor(tokenProvider))
            .addInterceptor(CollegeRouting)
            .addInterceptor(get<HttpLoggingInterceptor>())
            .addInterceptor { chain ->
                var request = chain.request()
                var response = chain.proceed(request)
                var attempt = 0
                while (response.code == 503 && attempt < 2) {
                    response.close()
                    attempt++
                    Thread.sleep(1000L * attempt) // 1с, 2с
                    request = request.newBuilder().build()
                    response = chain.proceed(request)
                }
                response
            }
            .authenticator(get<TokenAuthenticator>())
            .build()
    }

    // TokenAuthenticator.
    single {
        val tokenProvider = get<TokenProvider>()
        TokenAuthenticator(tokenProvider) { }
    }

    // SUDIR.
    single<SudirApi> {
        Retrofit.Builder()
            .baseUrl(MesEnvironment.SUDIR_BASE_URL)
            .client(get<OkHttpClient>(BaseHttpClient))
            .addConverterFactory(get<kotlinx.serialization.json.Json>().asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SudirApi::class.java)
    }

    // school.mos.ru — mesh-auth и mapi.
    single<MeshAuthApi> {
        Retrofit.Builder()
            .baseUrl(MesEnvironment.SCHOOL_BASE_URL)
            .client(get<OkHttpClient>(AuthHttpClient))
            .addConverterFactory(get<kotlinx.serialization.json.Json>().asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MeshAuthApi::class.java)
    }


    single<MesApi> {
        Retrofit.Builder()
            .baseUrl(MesEnvironment.SCHOOL_BASE_URL)
            .client(get<OkHttpClient>(AuthHttpClient))
            .addConverterFactory(get<kotlinx.serialization.json.Json>().asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MesApi::class.java)
    }

    single<MealsApi> {
        Retrofit.Builder()
            .baseUrl(MesEnvironment.SCHOOL_BASE_URL)
            .client(get<OkHttpClient>(AuthHttpClient))
            .addConverterFactory(get<kotlinx.serialization.json.Json>().asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MealsApi::class.java)
    }

    single<PortalApi> {
        Retrofit.Builder()
            .baseUrl(MesEnvironment.SCHOOL_BASE_URL)
            .client(get<OkHttpClient>(AuthHttpClient))
            .addConverterFactory(get<kotlinx.serialization.json.Json>().asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PortalApi::class.java)
    }

    // Клиент только по офлайн-кэшу, без сети: мгновенный показ сохранённого до загрузки.
    single(CachedHttpClient) {
        get<OkHttpClient>(BaseHttpClient).newBuilder()
            .apply { interceptors().clear() }
            .addInterceptor(OfflineCacheInterceptor(get(), cacheOnly = true))
            .build()
    }

    single<MesApi>(CachedMesApi) { cachedRetrofit().create(MesApi::class.java) }
    single<MealsApi>(CachedMealsApi) { cachedRetrofit().create(MealsApi::class.java) }
    single<PortalApi>(CachedPortalApi) { cachedRetrofit().create(PortalApi::class.java) }
}

private fun Scope.cachedRetrofit(): Retrofit = Retrofit.Builder()
    .baseUrl(MesEnvironment.SCHOOL_BASE_URL)
    .client(get<OkHttpClient>(CachedHttpClient))
    .addConverterFactory(get<kotlinx.serialization.json.Json>().asConverterFactory("application/json".toMediaType()))
    .build()
