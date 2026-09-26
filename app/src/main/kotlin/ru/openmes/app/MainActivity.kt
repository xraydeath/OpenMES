package ru.openmes.app

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.koin.compose.koinInject
import ru.openmes.core.data.AppSettings
import ru.openmes.core.data.SettingsRepository
import ru.openmes.core.data.ThemeMode
import ru.openmes.core.designsystem.theme.OpenMESTheme

/** FragmentActivity — нужна для BiometricPrompt. */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenMESRoot()
        }
    }
}

/** Через сколько в фоне снова спрашивать PIN. */
private const val RELOCK_TIMEOUT_MS = 60_000L

@Composable
private fun OpenMESRoot() {
    val settingsRepository = koinInject<SettingsRepository>()
    // null — настройки ещё не прочитаны: не показываем контент до решения о блокировке.
    val loaded by settingsRepository.settings.collectAsState(initial = null)
    val settings = loaded ?: AppSettings()

    var locked by rememberSaveable { mutableStateOf(true) }
    var stoppedAt by rememberSaveable { mutableLongStateOf(0L) }
    // Без PIN — разблокировано; так включение PIN в настройках не блокирует сразу.
    LaunchedEffect(loaded?.pinEnabled) { if (loaded?.pinEnabled == false) locked = false }
    // С PIN — ни скриншотов, ни превью в «Недавних»: иначе содержимое видно в обход блокировки.
    val activity = LocalActivity.current
    LaunchedEffect(activity, loaded?.pinEnabled) {
        val secure = loaded?.pinEnabled == true
        activity?.window?.let { window ->
            if (secure) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity?.setRecentsScreenshotEnabled(!secure)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> stoppedAt = SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_START ->
                    if (stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt > RELOCK_TIMEOUT_MS) {
                        locked = true
                    }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    OpenMESTheme(
        darkTheme = darkTheme,
        dynamicColor = settings.dynamicColor,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            if (loaded != null) {
                // Навигация остаётся в композиции: после разблокировки — тот же экран.
                AppNavHost()
                if (settings.pinEnabled && locked) {
                    LockScreen(
                        biometricEnabled = settings.biometricEnabled,
                        checkPin = settingsRepository::checkPin,
                        onUnlock = { locked = false },
                    )
                }
            }
        }
    }
}
