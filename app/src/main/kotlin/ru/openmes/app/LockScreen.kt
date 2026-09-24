package ru.openmes.app

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.fragment.app.FragmentActivity
import ru.openmes.core.designsystem.components.ShapeIcon
import ru.openmes.core.designsystem.components.rememberPressMorphShape
import kotlinx.coroutines.launch

/**
 * Экран блокировки: PIN (4–8 цифр) и, если включено, биометрия.
 * При вводе ≥4 цифр PIN проверяется тихо; ошибка показывается только по «✓».
 */
@Composable
internal fun LockScreen(
    biometricEnabled: Boolean,
    checkPin: suspend (String) -> Boolean,
    onUnlock: () -> Unit,
) {
    val activity = LocalContext.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

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
                        onUnlock()
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
        if (pin.length >= 8) return
        pin += digit
        error = false
        if (pin.length >= 4) {
            val attempt = pin
            scope.launch { if (checkPin(attempt)) onUnlock() }
        }
    }

    fun submit() {
        val attempt = pin
        scope.launch {
            if (checkPin(attempt)) {
                onUnlock()
            } else {
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
            if (error) "Неверный PIN-код" else "Введите PIN-код",
            style = MaterialTheme.typography.headlineSmallEmphasized,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
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
                    BackspaceButton { pin = pin.dropLast(1) }
                }
            }
            KeypadButton("0") { input('0') }
            Box(Modifier.size(KEY_SIZE), contentAlignment = Alignment.Center) {
                if (canUseBiometric && pin.isNotEmpty()) {
                    BackspaceButton { pin = pin.dropLast(1) }
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
