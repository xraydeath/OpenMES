package ru.openmes.feature.homework

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.android.ext.android.inject
import ru.openmes.core.data.AppSettings
import ru.openmes.core.data.SettingsRepository
import ru.openmes.core.data.ThemeMode
import ru.openmes.core.data.TokenStore
import ru.openmes.core.designsystem.theme.OpenMESTheme
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** Открыть материал (ЭОР) или главную Библиотеки МЭШ во встроенном браузере с авторизацией. */
fun Context.openLibrary(url: String = LibraryActivity.LIBRARY_HOME) {
    startActivity(Intent(this, LibraryActivity::class.java).putExtra(LibraryActivity.EXTRA_URL, url))
}

/**
 * Встроенный браузер для Библиотеки МЭШ (uchebnik.mos.ru).
 *
 * Материалы ДЗ открываются через launcher school.mos.ru → uchebnik.mos.ru/cms/.../launch, а cms
 * без сессии библиотеки отвечает 403 «недоступен в связи с лицензионным ограничением». Токен
 * дневника cms не принимает — нужна отдельная сессия (OAuth school.mos.ru, client_id=library).
 * Поэтому:
 *  1. кладём токен МЭШ в куки school.mos.ru — launcher и /v1/oauth/authorize видят вход;
 *  2. если cms всё же ответила «лицензионным ограничением», идём на uchebnik.mos.ru/authenticate:
 *     SPA библиотеки сама проходит OAuth (со state в своём localStorage) и возвращает на backurl.
 * Куки и localStorage WebView живут между запусками — вход в библиотеку нужен один раз.
 */
class LibraryActivity : ComponentActivity() {

    private val tokenStore: TokenStore by inject()
    private val settingsRepository: SettingsRepository by inject()

    private var webView: WebView? = null
    private var barTitle by mutableStateOf("Библиотека МЭШ")
    private var loadProgress by mutableIntStateOf(0)

    /** Повторная авторизация — одна на запуск, чтобы не зациклиться при реальном ограничении. */
    private var authTried = false
    private var profileRetried = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startUrl = intent.getStringExtra(EXTRA_URL) ?: LIBRARY_HOME
        authTried = savedInstanceState?.getBoolean(STATE_AUTH_TRIED) ?: false
        setupCookies()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val view = webView
                if (view != null && view.canGoBack()) view.goBack() else finish()
            }
        })

        setContent {
            val loaded by settingsRepository.settings.collectAsState(initial = null)
            val settings = loaded ?: AppSettings()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            OpenMESTheme(darkTheme = darkTheme, dynamicColor = settings.dynamicColor) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(barTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            navigationIcon = {
                                IconButton(onClick = ::finish) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Закрыть")
                                }
                            },
                            actions = {
                                IconButton(onClick = { webView?.reload() }) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = "Обновить")
                                }
                                IconButton(onClick = { webView?.url?.let(::openExternal) }) {
                                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Открыть в браузере")
                                }
                            },
                        )
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        AndroidView(
                            factory = { context -> createWebView(context, savedInstanceState, startUrl) },
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (loadProgress in 1..99) {
                            LinearProgressIndicator(
                                progress = { loadProgress / 100f },
                                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                            )
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context, savedInstanceState: Bundle?, startUrl: String): WebView =
        WebView(context).apply {
            // Иначе AndroidView даёт WRAP_CONTENT, и страницы с html{height:100%} (сценарии уроков) получают высоту 0.
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.javaScriptCanOpenWindowsAutomatically = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = LibraryWebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    loadProgress = newProgress
                }

                override fun onReceivedTitle(view: WebView, pageTitle: String?) {
                    if (!pageTitle.isNullOrBlank() && !pageTitle.startsWith("http")) barTitle = pageTitle
                }
            }
            // Файлы из материалов (pdf, doc…) WebView не показывает — качаем сами, с куками библиотеки.
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                download(url, userAgent, contentDisposition, mimeType)
            }

            webView = this
            if (savedInstanceState?.let { restoreState(it) } == null) {
                // Сначала выбираем профиль колледжа — иначе cms сразу отдаст «лицензионное ограничение».
                lifecycleScope.launch {
                    ensureLibraryProfile()
                    loadUrl(startUrl)
                }
            }
        }

    private inner class LibraryWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when (uri.scheme) {
                "http", "https" -> false
                // intent:, mailto:, tel: и т.п. — наружу.
                else -> {
                    openExternal(uri.toString())
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            loadProgress = 1
        }

        override fun onPageFinished(view: WebView, url: String?) {
            loadProgress = 100
            CookieManager.getInstance().flush()
            val uri = url?.let(Uri::parse) ?: return
            if (uri.host != LIBRARY_HOST || uri.path?.startsWith("/cms/") != true) return
            // cms отдаёт страницу ошибки с кодом 403 — сам код WebView не сообщает, смотрим текст.
            view.evaluateJavascript(
                "(function(){var t=document.body?document.body.innerText:'';" +
                    "return t.indexOf('лицензионн')>=0||t.indexOf('Not authorized')>=0||t.indexOf('authentication_error')>=0})()",
            ) { result ->
                if (result != "true") return@evaluateJavascript
                lifecycleScope.launch {
                    when (ensureLibraryProfile()) {
                        ProfileCheck.SWITCHED -> if (!profileRetried) {
                            profileRetried = true
                            view.reload()
                        }
                        ProfileCheck.NO_SESSION -> if (!authTried) {
                            authTried = true
                            view.loadUrl(authenticateUrl(backUrl = url, role = uri.getQueryParameter("role") ?: "student"))
                        }
                        // Сессия есть, профиль верный — материал действительно недоступен.
                        ProfileCheck.OK -> Unit
                    }
                }
            }
        }
    }

    /**
     * Страница /authenticate без code сама вызывает redirectToAuthPage: генерирует state,
     * уходит на school.mos.ru/v1/oauth/authorize (client_id=library) и после входа
     * создаёт сессию (acl session/v2/callback) и возвращает на backurl.
     */
    private fun authenticateUrl(backUrl: String, role: String): String =
        Uri.parse("https://$LIBRARY_HOST/authenticate").buildUpon()
            .appendQueryParameter("backurl", backUrl)
            .appendQueryParameter("role", role)
            .appendQueryParameter("rgn", MOSCOW_REGION)
            .build()
            .toString()

    private enum class ProfileCheck { OK, SWITCHED, NO_SESSION }

    /**
     * В сессии библиотеки у студента колледжа два профиля: школьный student (aupdId 1) и collegian (32).
     * При входе библиотека выбирает student, а материалы колледжа доступны только collegian —
     * отсюда «лицензионное ограничение». Профиль сессии — кука Session-Eom-Profile; список профилей
     * берём из eom-токена (session/v2/refresh) и ставим тот, чья роль совпадает с ролью дневника.
     */
    private suspend fun ensureLibraryProfile(): ProfileCheck = withContext(Dispatchers.IO) {
        runCatching {
            val cookies = CookieManager.getInstance()
            val cookie = cookies.getCookie(LIBRARY_HOME)
            if (cookie == null || "Session-Id-External=" !in cookie) return@runCatching ProfileCheck.NO_SESSION

            val conn = URL("$LIBRARY_HOME$SESSION_REFRESH").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Cookie", cookie)
                conn.setRequestProperty("Accept", "application/json")
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(0)
                val code = conn.responseCode
                conn.headerFields["Set-Cookie"]?.forEach { cookies.setCookie(LIBRARY_HOME, it) }
                if (code == 401 || code == 403) return@runCatching ProfileCheck.NO_SESSION
                if (code !in 200..299) return@runCatching ProfileCheck.OK

                val eom = JSONObject(conn.inputStream.bufferedReader().readText()).optString("accessTokenEom")
                val payload = JSONObject(String(Base64.getUrlDecoder().decode(eom.split('.')[1])))
                val prf = payload.optJSONArray("prf") ?: return@runCatching ProfileCheck.OK
                val profiles = (0 until prf.length()).map { prf.getJSONObject(it) }
                val role = tokenStore.load()?.roleId?.toIntOrNull()
                val wanted = profiles.firstOrNull { role != null && it.optInt("aupdId") == role }
                    ?: profiles.firstOrNull { it.optString("type") == "collegian" }
                    ?: return@runCatching ProfileCheck.OK
                val id = wanted.optString("id").takeIf { it.isNotEmpty() } ?: return@runCatching ProfileCheck.OK

                val current = Regex("Session-Eom-Profile=([^;]+)").find(cookie)?.groupValues?.get(1)?.trim()
                if (current == id) return@runCatching ProfileCheck.OK
                cookies.setCookie(LIBRARY_HOME, "Session-Eom-Profile=$id; Path=/; Secure; HttpOnly; Max-Age=604800")
                cookies.flush()
                ProfileCheck.SWITCHED
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(ProfileCheck.OK)
    }

    /** Токен МЭШ (aupd) — в куки портала, как у веб-версии дневника. */
    private fun setupCookies() {
        val tokens = tokenStore.load() ?: return
        val token = tokens.meshAccessToken ?: return
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        val values = buildList {
            add("aupd_token=$token")
            add("auth_token=$token")
            tokens.profileId?.let { add("profile_id=$it") }
        }
        values.forEach { cookies.setCookie(PORTAL_URL, "$it; Path=/; Secure") }
        cookies.flush()
    }

    /**
     * Скачивание через DownloadManager: внешний браузер не знает кук сессии библиотеки и получил бы 403.
     * С Android 10 — в общие «Загрузки» без разрешений; раньше туда нужен WRITE_EXTERNAL_STORAGE,
     * поэтому — в папку приложения (файл открывается из уведомления о загрузке).
     */
    private fun download(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val uri = Uri.parse(url)
        // blob:/data: DownloadManager не умеет — пусть разбирается система.
        if (uri.scheme != "http" && uri.scheme != "https") {
            openExternal(url)
            return
        }
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        runCatching {
            val request = DownloadManager.Request(uri)
                .setTitle(fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            mimeType?.takeIf { it.isNotBlank() }?.let(request::setMimeType)
            userAgent?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("User-Agent", it) }
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            } else {
                request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            getSystemService(DownloadManager::class.java).enqueue(request)
        }.onSuccess {
            Toast.makeText(this, "Скачивается: $fileName", Toast.LENGTH_SHORT).show()
        }.onFailure {
            // DownloadManager отключён или недоступен — хотя бы через браузер.
            openExternal(url)
        }
    }

    private fun openExternal(url: String) {
        val intent = if (url.startsWith("intent:", ignoreCase = true)) {
            // intent://…#Intent;…;end — разбираем как Chrome: только BROWSABLE-активности,
            // без явного компонента/селектора (иначе страница могла бы запустить что угодно).
            runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull()?.apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            } ?: return
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Приложения нет — запасная ссылка страницы (как делает Chrome), http(s) — прямо здесь.
            val fallback = intent.getStringExtra(EXTRA_BROWSER_FALLBACK_URL)
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            if (fallback != null) {
                webView?.loadUrl(fallback)
            } else {
                Toast.makeText(this, "Нет приложения для открытия ссылки", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            // Битый intent: из страницы.
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_AUTH_TRIED, authTried)
        webView?.saveState(outState)
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val LIBRARY_HOST = "uchebnik.mos.ru"
        const val LIBRARY_HOME = "https://uchebnik.mos.ru/"
        private const val PORTAL_URL = "https://school.mos.ru"
        /** id региона «Москва» из uchebnik.mos.ru/config/config.json (REGIONS). */
        private const val MOSCOW_REGION = "77"
        private const val STATE_AUTH_TRIED = "authTried"
        private const val SESSION_REFRESH = "acl/api/session/v2/refresh"
        private const val EXTRA_BROWSER_FALLBACK_URL = "browser_fallback_url"
    }
}
