package ru.openmes.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import ru.openmes.core.designsystem.components.ShapeIcon
import ru.openmes.core.designsystem.components.rememberPressMorphShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Экран блокировки: PIN (4–8 цифр) и, если включено, биометрия.
 * При вводе ≥4 цифр PIN проверяется тихо; ошибка показывается только по «✓».
 * Неверная комбинация засчитывается попыткой, когда её отправили или стёрли (дописать цифру — не попытка);
 * после [FREE_PIN_ATTEMPTS] попыток ввод блокируется на растущее время (см. [PinAttempts]).
 */
@Composable
internal fun LockScreen(
    biometricEnabled: Boolean,
    checkPin: suspend (String) -> Boolean,
    onUnlock: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    val attempts = remember { PinAttempts(context) }
    var lockedUntil by remember { mutableLongStateOf(attempts.lockedUntil()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lockedOut = lockedUntil > now
    LaunchedEffect(lockedUntil) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= lockedUntil) break
            delay(1_000)
        }
    }
    // Комбинация, уже проверенная тихо и неверная, но ещё не засчитанная попыткой.
    var wrongPin by remember { mutableStateOf<String?>(null) }
    fun fail() {
        attempts.registerFailure()
        lockedUntil = attempts.lockedUntil()
        now = System.currentTimeMillis()
    }
    fun unlock() {
        attempts.reset()
        wrongPin = null
        onUnlock()
    }
    // Ушли с экрана с неверной комбинацией — тоже попытка (иначе перезапуск обходил бы счётчик).
    DisposableEffect(Unit) {
        onDispose { if (wrongPin != null) attempts.registerFailure() }
    }

    val canUseBiometric = remember(biometricEnabled) {
        biometricEnabled && activity != null &&
            BiometricManager.from(activity).canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }
    val showBiometric = {
        if (activity != null) {
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        unlock()
                    }
                },
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Вход в OpenMES")
                    .setNegativeButtonText("PIN-код")
                    .setAllowedAuthenticators(BIOMETRIC_WEAK)
                    .build(),
            )
        }
    }
    LaunchedEffect(canUseBiometric) { if (canUseBiometric) showBiometric() }
    // «Назад» не должен листать навигацию под блокировкой — просто сворачиваем.
    BackHandler { activity?.moveTaskToBack(true) }

    fun input(digit: Char) {
        if (lockedOut || pin.length >= 8) return
        pin += digit
        wrongPin = null
        error = false
        if (pin.length >= 4) {
            val attempt = pin
            scope.launch {
                when {
                    checkPin(attempt) -> unlock()
                    pin == attempt -> wrongPin = attempt
                    // Пока проверяли, комбинацию стёрли или заменили — попытка всё равно была.
                    !pin.startsWith(attempt) -> fail()
                }
            }
        }
    }

    fun erase() {
        if (pin.isNotEmpty() && wrongPin == pin) fail()
        wrongPin = null
        pin = pin.dropLast(1)
    }

    fun submit() {
        if (lockedOut) return
        val attempt = pin
        scope.launch {
            if (checkPin(attempt)) {
                unlock()
            } else {
                fail()
                wrongPin = null
                error = true
                pin = ""
            }
        }
    }

    // Лёгкое «потряхивание» индикатора при ошибке.
    val shake = remember { Animatable(0f) }
    LaunchedEffect(error) {
        if (error) {
            repeat(3) {
                shake.animateTo(12f, tween(40))
                shake.animateTo(-12f, tween(40))
            }
            shake.animateTo(0f, tween(40))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            // Поглощаем касания, чтобы они не доходили до контента под блокировкой.
            .clickable(interactionSource = null, indication = null) {}
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ShapeIcon(
            icon = Icons.Rounded.Lock,
            shape = MaterialShapes.Cookie9Sided.toShape(),
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
            size = 72.dp,
            iconSize = 32.dp,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            when {
                lockedOut -> "Слишком много попыток"
                error -> "Неверный PIN-код"
                else -> "Введите PIN-код"
            },
            style = MaterialTheme.typography.headlineSmallEmphasized,
            color = if (error || lockedOut) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (lockedOut) {
            val seconds = (lockedUntil - now + 999) / 1_000
            Spacer(Modifier.height(8.dp))
            Text(
                "Повторите через %d:%02d".format(seconds / 60, seconds % 60),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(20.dp))
        // Индикатор: введённые цифры — «печенье», пустые места (до 4) — точки.
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(24.dp)
                .graphicsLayer { translationX = shake.value * density },
        ) {
            repeat(maxOf(4, pin.length)) { index ->
                val filled = index < pin.length
                val size by animateDpAsState(if (filled) 20.dp else 10.dp, label = "pin_dot")
                Box(
                    Modifier
                        .size(size)
                        .background(
                            color = when {
                                error -> MaterialTheme.colorScheme.error
                                filled -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = if (filled) PIN_SHAPES[index % PIN_SHAPES.size].toShape() else CircleShape,
                        ),
                )
            }
        }
        Spacer(Modifier.height(40.dp))

        val rows = listOf("123", "456", "789")
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { digit -> KeypadButton(digit.toString()) { input(digit) } }
            }
            Spacer(Modifier.height(16.dp))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(KEY_SIZE), contentAlignment = Alignment.Center) {
                if (canUseBiometric) {
                    FilledTonalIconButton(
                        onClick = showBiometric,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(Icons.Rounded.Fingerprint, contentDescription = "Биометрия")
                    }
                } else if (pin.isNotEmpty()) {
                    BackspaceButton(::erase)
                }
            }
            KeypadButton("0") { input('0') }
            Box(Modifier.size(KEY_SIZE), contentAlignment = Alignment.Center) {
                if (canUseBiometric && pin.isNotEmpty()) {
                    BackspaceButton(::erase)
                } else if (pin.length >= 4) {
                    FilledIconButton(
                        onClick = ::submit,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = "Войти")
                    }
                }
            }
        }
        if (canUseBiometric && pin.length >= 4) {
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = ::submit,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.heightIn(min = ButtonDefaults.MediumContainerHeight),
                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.MediumContainerHeight)))
                Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(ButtonDefaults.MediumContainerHeight)))
                Text("Войти", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Сколько неверных попыток без паузы. */
internal const val FREE_PIN_ATTEMPTS = 5
private const val BASE_LOCKOUT_MS = 30_000L
private const val MAX_LOCKOUT_MS = 30 * 60_000L

/** Пауза после [failures] неверных попыток: 30 с после пятой, дальше вдвое больше за каждую, но не больше 30 минут. */
internal fun pinLockoutMillis(failures: Int): Long =
    if (failures < FREE_PIN_ATTEMPTS) 0L
    else (BASE_LOCKOUT_MS shl (failures - FREE_PIN_ATTEMPTS).coerceAtMost(6)).coerceAtMost(MAX_LOCKOUT_MS)

/** Счётчик неверных PIN-кодов; в SharedPreferences, чтобы перезапуск приложения его не сбрасывал. */
internal class PinAttempts(context: Context) {

    private val prefs = context.getSharedPreferences("pin_attempts", Context.MODE_PRIVATE)

    /** До какого момента (System.currentTimeMillis) ввод закрыт; в прошлом — открыт. */
    fun lockedUntil(): Long =
        // Часы перевели назад — дольше максимальной паузы всё равно не держим.
        prefs.getLong(KEY_LOCKED_UNTIL, 0L).coerceAtMost(System.currentTimeMillis() + MAX_LOCKOUT_MS)

    fun registerFailure() {
        val failures = prefs.getInt(KEY_FAILURES, 0) + 1
        val lockout = pinLockoutMillis(failures)
        prefs.edit {
            putInt(KEY_FAILURES, failures)
            putLong(KEY_LOCKED_UNTIL, if (lockout > 0) System.currentTimeMillis() + lockout else 0L)
        }
    }

    fun reset() {
        prefs.edit { clear() }
    }

    private companion object {
        const val KEY_FAILURES = "failures"
        const val KEY_LOCKED_UNTIL = "locked_until"
    }
}

private val KEY_SIZE = 76.dp
private val PIN_SHAPES = listOf(
    MaterialShapes.Cookie4Sided,
    MaterialShapes.Sunny,
    MaterialShapes.Clover4Leaf,
    MaterialShapes.Pill,
    MaterialShapes.Cookie6Sided,
    MaterialShapes.Flower,
    MaterialShapes.Gem,
    MaterialShapes.Heart,
)

/** Клавиша: круг, при нажатии морфится в скруглённый квадрат. */
@Composable
private fun KeypadButton(label: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = Modifier.size(KEY_SIZE),
        shape = rememberPressMorphShape(interactionSource, corner = KEY_SIZE / 2, pressedCorner = 20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        interactionSource = interactionSource,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.headlineMediumEmphasized)
        }
    }
}

@Composable
private fun BackspaceButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(56.dp)) {
        Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = "Стереть")
    }
}
