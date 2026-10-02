package ru.openmes.core.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.openmes.core.designsystem.theme.backdropOr

/**
 * Сила растворения контента у верхней кромки: 0 — не растворяется, 1 — растворяется полностью.
 *
 * Задаётся оболочкой приложения (по свёрнутости верхней панели) и читается экранами, которые
 * вешают [mesFadeTop] на свой скролл-контейнер. Пока значение нулевое, полоса не рисуется
 * вовсе: в покое верх списка и так пустой, и иначе полоса съедала бы верх первого элемента.
 */
val LocalMesEdgeFade = compositionLocalOf<() -> Float> { { 0f } }

/** Высота полосы, в которой контент тает. */
val MesEdgeFadeHeight: Dp = 28.dp

/**
 * Растворяет содержимое у верхней кромки вместо обрезки по ней.
 *
 * Без этого уходящая строка просто исчезает посреди букв: у скролл-контейнера своя кромка,
 * и прокрутка выглядит как гильотина. Полоса цвета фона — того же, чем залит экран, — идёт от
 * непрозрачного у кромки к нулю у низа, и строка тает ровно там, где раньше ломалась.
 *
 * Вешать надо именно на скролл-контейнер, а не на верх его области: над списком у многих
 * экранов стоит неподвижная шапка (полоса дней, вкладки), и градиент на её месте замылил бы
 * кнопки, а до списка снизу не дотянулся бы вовсе.
 *
 * Снизу растворение делает не он, а [MesBottomScrim]: там полоса должна накрывать ещё и док,
 * и системную навигацию, а это уже не свойство одного списка.
 *
 * Всё считается в фазе draw: чтение силы здесь инвалидирует только перерисовку, а не
 * перекомпозицию экрана.
 */
@Composable
fun Modifier.mesFadeTop(fadeHeight: Dp = MesEdgeFadeHeight): Modifier {
    val background = backdropOr(MaterialTheme.colorScheme.background)
    val strengthOf = LocalMesEdgeFade.current
    return drawWithContent {
        drawContent()
        val fadePx = fadeHeight.toPx()
        val strength = strengthOf().coerceIn(0f, 1f)
        if (fadePx <= 0f || strength <= 0f) return@drawWithContent
        drawRect(
            brush = Brush.verticalGradient(
                0f to background.copy(alpha = strength),
                1f to background.copy(alpha = 0f),
                startY = 0f,
                endY = fadePx,
            ),
            size = Size(size.width, fadePx),
        )
    }
}

/**
 * Нижняя полоса растворения — на док и на системную навигацию сразу, как в ADHDium
 * (`MainShell.bottomScrimHeight`).
 *
 * Смысл в том, что контент уходит под оба, и полоса закрывает их обоих: навбар из-за этого
 * остаётся светлее карточки, которая под ним едет, а не показывает её обрезок.
 *
 * Ставится оболочкой приложения поверх содержимого и дока одним слоем: док непрозрачен и
 * перекрывает полосу, так что под ним она всё равно не нужна.
 *
 * Со включённым фоном полоса обрывается на верхней кромке пилюли и до низа экрана уже не
 * идёт. Причина — арифметическая: сама полоса полупрозрачна, и если бы она продолжалась
 * под доком, то под пилюлей оказались бы две полупрозрачные подложки одна на другой, а от
 * фотографии между ними осталось бы 0.85 × 0.85 ≈ 2 %. Дальше низа снимок идёт к самому
 * краю экрана — так же, как в ADHDium, где при выбранном фоне градиентов у баров нет вовсе.
 */
@Composable
fun MesBottomScrim(modifier: Modifier = Modifier) {
    val background = backdropOr(MaterialTheme.colorScheme.background)
    val density = LocalDensity.current
    val navigationBars = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val dockTopPx = with(density) { (navigationBars + MesDockTopInset).toPx() }
    val heightPx = with(density) { (navigationBars + MesDockTopInset + MesEdgeFadeHeight).toPx() }
    Box(
        modifier
            .fillMaxWidth()
            .height(with(density) { heightPx.toDp() })
            .drawBehind {
                // Стоп-колор ровно на кромке дока: выше — градиент, ниже — ровный фон
                // до самого низа вместе с навбаром, иначе сквозь край пилюли просвечивали бы строки.
                val opaqueAt = (dockTopPx / heightPx).coerceIn(0f, 1f)
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to background.copy(alpha = 0f),
                            opaqueAt to background,
                            1f to background,
                        ),
                        startY = 0f,
                        endY = heightPx,
                    ),
                    size = Size(size.width, heightPx),
                )
            },
    )
}