package de.pabich.buzzer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.pabich.buzzer.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/** Dauer der Start-Animationen. */
const val INTRO_MS = 3_000

/** Anzahl der Varianten: Splash A–C, Rennszenen 1–3. */
const val SPLASH_VARIANTS = 3
const val RACE_VARIANTS = 3

// ---------------------------------------------------------------------- Zeit-Helfer

/** Fortschritt 0..1 von [a] bis [b] (Sekunden). */
private fun seg(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)

private fun easeOut(x: Float) = 1f - (1f - x) * (1f - x)
private fun easeIn(x: Float) = x * x
private fun easeInOut(x: Float) = if (x < .5f) 2 * x * x else 1 - (-2 * x + 2) * (-2 * x + 2) / 2

/** Überschwingen wie „pop“ im Entwurf. */
private fun back(x: Float): Float {
    if (x <= 0f) return 0f
    val c1 = 2.2f
    val c3 = c1 + 1
    val y = x - 1
    return 1 + c3 * y * y * y + c1 * y * y
}

/** Lineare Interpolation zwischen Stützstellen. */
private fun kf(t: Float, times: FloatArray, values: FloatArray): Float {
    if (t <= times.first()) return values.first()
    for (i in 1 until times.size) {
        if (t <= times[i]) {
            val f = (t - times[i - 1]) / (times[i] - times[i - 1])
            return values[i - 1] + (values[i] - values[i - 1]) * f
        }
    }
    return values.last()
}

/** Nach der Animation bleibt das letzte Bild kurz stehen und blendet dann aus. */
const val INTRO_HOLD_MS = 800
const val INTRO_FADE_MS = 600

/** Gesamtdauer bis die Animation verschwunden ist. */
const val INTRO_TOTAL_MS = INTRO_MS + INTRO_HOLD_MS + INTRO_FADE_MS

/**
 * Zeit in Sekunden und Deckkraft. Läuft über die Bildwiederholung (nicht über die Animations-
 * Skalierung des Systems – sonst wäre sie bei „Animationen aus“ sofort vorbei) und beginnt erst,
 * wenn das erste Bild wirklich angezeigt wird. Danach [INTRO_HOLD_MS] stehen, [INTRO_FADE_MS]
 * ausblenden, dann [onDone].
 */
@Composable
private fun introClock(key: Any, onDone: () -> Unit): Pair<Float, Float> {
    val done by rememberUpdatedState(onDone)
    var seconds by remember(key) { mutableFloatStateOf(0f) }
    var alpha by remember(key) { mutableFloatStateOf(1f) }
    LaunchedEffect(key) {
        withFrameNanos { }
        val start = withFrameNanos { it }
        while (true) {
            val ms = (withFrameNanos { it } - start) / 1_000_000f
            seconds = ms / 1000f
            alpha = (1f - (ms - INTRO_MS - INTRO_HOLD_MS) / INTRO_FADE_MS).coerceIn(0f, 1f)
            if (ms >= INTRO_TOTAL_MS) break
        }
        done()
    }
    return seconds to alpha
}

// ---------------------------------------------------------------------- Bausteine

private val Ground = Color(0xFF0A0F2C)
private val RayColor = Color(0xFF1B2563)
private val Gold = Color(0xFFFFC83D)
private val GoldShadow = Color(0xFF7A5A00)
private val Cyan = Color(0xFF3BE3F5)
private val ConfettiColors = listOf(Gold, Cyan, Color(0xFFE53935), Color(0xFF2BE36B), Color(0xFFF4F1E8), Color(0xFFFF8A80), Color(0xFFB388FF))
private val Shirts = listOf(Color(0xFFE53935), Cyan, Gold, Color(0xFF2BE36B), Color(0xFFB388FF), Color(0xFFFF8A80))
private val Skins = listOf(Color(0xFFF2C7A5), Color(0xFFC98E63), Color(0xFF8D5A3B), Color(0xFFF5D7C1), Color(0xFFB67B52))
private val Hairs = listOf(Color(0xFF3B2416), Color(0xFF121212), Color(0xFFC68A2E), Color(0xFF7A3B1A), Color(0xFFE8D6A0))

/** Rotierende Scheinwerfer-Strahlen. */
private fun DrawScope.rays(t: Float, center: Offset) {
    drawRect(Ground)
    val alpha = seg(t, 0f, .4f)
    val r = size.maxDimension * 1.2f
    rotate(t * 26f, center) {
        for (i in 0 until 12) {
            val a0 = (i * 30.0) * PI / 180
            val a1 = (i * 30.0 + 12) * PI / 180
            val p = Path().apply {
                moveTo(center.x, center.y)
                lineTo(center.x + (r * cos(a0)).toFloat(), center.y + (r * sin(a0)).toFloat())
                lineTo(center.x + (r * cos(a1)).toFloat(), center.y + (r * sin(a1)).toFloat())
                close()
            }
            drawPath(p, RayColor.copy(alpha = alpha))
        }
    }
}

private enum class Pose { CHEER, RUN, CROUCH }

/**
 * Mitspieler mit Handy, gezeichnet in einem 46×80-Raster wie im Entwurf. [x], [bottom]: Fußpunkt in
 * Pixeln; [k]: Pixel je Rastereinheit; [phase]: eigener Takt, damit nicht alle gleich zappeln.
 */
private fun DrawScope.figure(x: Float, bottom: Float, k: Float, index: Int, pose: Pose, t: Float, phase: Float = 0f) {
    val shirt = Shirts[index % Shirts.size]
    val skin = Skins[index % Skins.size]
    val hair = Hairs[(index * 3 + 1) % Hairs.size]
    val swing = sin((t + phase) * 2 * PI.toFloat() / 0.64f)
    val wave = sin((t + phase) * 2 * PI.toFloat() / 0.7f)
    val hop = when (pose) {
        Pose.CHEER -> -9f * (0.5f + 0.5f * sin((t + phase) * 2 * PI.toFloat() / 1f))
        Pose.RUN -> -4f * (0.5f + 0.5f * sin((t + phase) * 2 * PI.toFloat() / 0.32f))
        Pose.CROUCH -> 10f
    }
    val lean = when (pose) { Pose.CHEER -> 0f; Pose.RUN -> 8f; Pose.CROUCH -> 22f }
    val legL = when (pose) { Pose.CHEER -> 0f; Pose.RUN -> 38f * swing; Pose.CROUCH -> 60f }
    val legR = when (pose) { Pose.CHEER -> 0f; Pose.RUN -> -38f * swing; Pose.CROUCH -> -20f }
    val armL = when (pose) { Pose.CHEER -> 152f + 17f * wave; Pose.RUN -> -38f * swing; Pose.CROUCH -> -10f }
    val armR = when (pose) { Pose.CHEER -> -152f - 17f * wave; Pose.RUN -> 38f * swing; Pose.CROUCH -> -10f }
    val screen = if (floor((t + phase) / 0.3f).toInt() % 2 == 0) Cyan else Color.White
    withTransform({
        translate(x - 23f * k, bottom - 80f * k + hop * k)
        scale(k, k, Offset.Zero)
        rotate(lean, Offset(23f, 50f))
    }) {
        fun limb(px: Float, angle: Float, leg: Boolean) {
            rotate(angle, Offset(px + (if (leg) 3f else 2.5f), if (leg) 52f else 24.5f)) {
                if (leg) {
                    drawRoundRect(Color(0xFF2A3570), Offset(px, 50f), Size(6f, 24f), CornerRadius(3f))
                    drawRoundRect(Color(0xFF111111), Offset(px - 2f, 71f), Size(10f, 5f), CornerRadius(2.5f))
                } else {
                    drawRoundRect(shirt, Offset(px, 23f), Size(5f, 18f), CornerRadius(2.5f))
                    drawRoundRect(Color(0xFF1A1A2E), Offset(px - 1f, 38f), Size(7f, 10f), CornerRadius(1.5f))
                    drawRoundRect(screen, Offset(px, 39f), Size(5f, 7f), CornerRadius(1f))
                }
            }
        }
        limb(15f, legL, true)
        limb(25f, legR, true)
        drawRoundRect(shirt, Offset(12f, 22f), Size(22f, 31f), CornerRadius(8f))
        limb(7f, armL, false)
        limb(34f, armR, false)
        drawCircle(skin, 10f, Offset(23f, 12f))
        drawPath(Path().apply {
            moveTo(13f, 11f); quadraticBezierTo(14f, 0f, 23f, 1f); quadraticBezierTo(33f, 0f, 33f, 11f)
            quadraticBezierTo(28f, 6f, 23f, 7f); quadraticBezierTo(17f, 6f, 13f, 11f); close()
        }, hair)
        drawCircle(Color(0xFF111111), 1.4f, Offset(19.5f, 12f))
        drawCircle(Color(0xFF111111), 1.4f, Offset(26.5f, 12f))
        drawPath(Path().apply { moveTo(18f, 16f); quadraticBezierTo(23f, 22f, 28f, 16f); close() }, Color(0xFF7A1F1F))
    }
}

/** Fünf jubelnde Mitspieler am unteren Rand. */
private fun DrawScope.crowd(t: Float, seed: Int) {
    val k = size.width / 300f * 1.05f
    val bottom = size.height + 4f
    for (i in 0 until 5) {
        val x = size.width * (0.1f + i * 0.2f)
        val scale = if (i % 2 == 0) k * 1.12f else k
        figure(x, bottom, scale, i + seed, Pose.CHEER, t, phase = i * 0.17f + seed * 0.05f)
    }
    drawRect(
        Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF050817)), startY = size.height - 60f * k, endY = size.height),
        Offset(0f, size.height - 60f * k),
        Size(size.width, 60f * k),
    )
}

private class Bit(val x: Float, val vx: Float, val vy: Float, val spin: Float, val delay: Float, val life: Float, val color: Color)

private fun confettiBits(seed: Int): Pair<List<Bit>, List<Bit>> {
    val r = Random(seed)
    val fall = List(30) { Bit(r.nextFloat(), r.nextFloat() * 60 - 30, 0f, r.nextFloat() * 900 - 450, .3f + r.nextFloat() * 2.4f, 2.2f + r.nextFloat() * 1.6f, ConfettiColors[it % ConfettiColors.size]) }
    val throws = List(35) {
        val hand = it / 7
        Bit(0.1f + hand * 0.2f, r.nextFloat() * 80 - 40, -90 - r.nextFloat() * 120, r.nextFloat() * 720 - 360, .4f + hand * .15f + r.nextFloat() * .3f, 1.4f, ConfettiColors[(hand + it) % ConfettiColors.size])
    }
    return fall to throws
}

/** Konfetti von oben und aus den Händen des Publikums (Endlosschleife ab dem Start). */
private fun DrawScope.confetti(t: Float, bits: Pair<List<Bit>, List<Bit>>) {
    val k = size.width / 270f
    for (b in bits.first) {
        if (t < b.delay) continue
        val p = ((t - b.delay) % b.life) / b.life
        val x = b.x * size.width + b.vx * k * p
        val y = -20f * k + p * (size.height + 40f * k)
        rotate(b.spin * p, Offset(x, y)) { drawRoundRect(b.color, Offset(x, y), Size(7f * k, 12f * k), CornerRadius(2f * k)) }
    }
    val handY = size.height - 96f * k
    for (b in bits.second) {
        if (t < b.delay) continue
        val p = ((t - b.delay) % b.life) / b.life
        val e = 1 - (1 - p) * (1 - p)
        val x = b.x * size.width + b.vx * k * e
        val y = handY + b.vy * k * e
        rotate(b.spin * p, Offset(x, y)) {
            drawRoundRect(b.color.copy(alpha = 1f - p), Offset(x, y), Size(7f * k, 12f * k), CornerRadius(2f * k))
        }
    }
}

@Composable
private fun Mascot(width: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.mascot), contentDescription = null, modifier = modifier.size(width, width * 62f / 64f))
}

@Composable
private fun ShowText(text: String, size: Int, modifier: Modifier = Modifier, color: Color = Gold) {
    val theme = LocalBuzzerTheme.current
    Box(modifier) {
        Text(text, color = GoldShadow, fontFamily = theme.display, fontSize = size.sp, modifier = Modifier.offset(y = 3.dp))
        Text(text, color = color, fontFamily = theme.display, fontSize = size.sp)
    }
}

// ---------------------------------------------------------------------- Splash-Screen

/** Splash beim App-Start: Variante 0 = Hüpfer, 1 = Vorhang auf, 2 = Hau drauf. Tippen überspringt. */
@Composable
fun SplashScreen(variant: Int, onDone: (skipped: Boolean) -> Unit) {
    val (t, fade) = introClock(variant) { onDone(false) }
    val bits = remember(variant) { confettiBits(variant + 7) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade }
            .background(Ground)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDone(true) },
    ) {
        val w = maxWidth
        val h = maxHeight
        when (variant % SPLASH_VARIANTS) {
            0 -> SplashHop(t, w, h)
            1 -> SplashCurtain(t, w, h)
            else -> SplashSlap(t, w, h)
        }
        // Publikum und Konfetti liegen bei allen Varianten vorn (beim Vorhang hinter dem Vorhang).
        if (variant % SPLASH_VARIANTS != 1) {
            Canvas(Modifier.fillMaxSize()) { crowd(t, variant); confetti(t, bits) }
        }
        val tag = when (variant % SPLASH_VARIANTS) { 0 -> "Wer ist am schnellsten?"; 1 -> "Quiz & Buzzer-Show"; else -> "Hau auf den Buzzer!" }
        Text(
            tag,
            color = Color(0xFFF4F1E8),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = h * 0.09f).graphicsLayer {
                alpha = seg(t, 2.0f, 2.5f)
                translationY = (1 - seg(t, 2.0f, 2.5f)) * -24f
            },
        )
        if (variant % SPLASH_VARIANTS == 1) CurtainFront(t, h, bits, variant)
    }
}

@Composable
private fun BoxScope.SplashHop(t: Float, w: Dp, h: Dp) {
    Canvas(Modifier.fillMaxSize()) { rays(t, Offset(size.width / 2, size.height * .45f)) }
    val mascotW = w * 0.55f
    val times = floatArrayOf(0f, .48f, .67f, .87f, 1.01f, 1.15f)
    val ys = floatArrayOf(-2.6f, 0f, -.28f, 0f, -.06f, 0f)
    val sx = floatArrayOf(1f, 1.18f, .92f, 1.08f, .98f, 1f)
    val sy = floatArrayOf(1f, .72f, 1.1f, .9f, 1.02f, 1f)
    Box(Modifier.align(Alignment.TopCenter).padding(top = h * 0.28f).graphicsLayer {
        translationY = kf(t, times, ys) * mascotW.toPx() * .8f
        scaleX = kf(t, times, sx)
        scaleY = kf(t, times, sy)
        transformOrigin = TransformOrigin(.5f, 1f)
    }) { Mascot(mascotW) }
    androidx.compose.foundation.layout.Row(Modifier.align(Alignment.TopCenter).padding(top = h * 0.55f)) {
        "BUZZER".forEachIndexed { i, c ->
            val p = seg(t, 1.15f + i * .09f, 1.6f + i * .09f)
            ShowText(c.toString(), 54, Modifier.graphicsLayer {
                scaleX = back(p); scaleY = back(p); rotationZ = (1 - p) * -25f
            })
        }
    }
}

@Composable
private fun BoxScope.SplashCurtain(t: Float, w: Dp, h: Dp) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Ground)
        // Zwei Scheinwerferkegel schwenken ein.
        val a = seg(t, .6f, 1.3f)
        val sweep = easeInOut(seg(t, .6f, 2.8f))
        for (side in listOf(-1f, 1f)) {
            val top = Offset(size.width / 2 + side * size.width * .42f, -size.height * .05f)
            rotate(side * (35f - 25f * sweep), top) {
                val p = Path().apply {
                    moveTo(top.x - 6f, top.y); lineTo(top.x + 6f, top.y)
                    lineTo(top.x + size.width * .32f, top.y + size.height * 1.1f); lineTo(top.x - size.width * .32f, top.y + size.height * 1.1f); close()
                }
                drawPath(p, Brush.verticalGradient(listOf(Color(0x8CFFE896), Color(0x00FFE896)), startY = top.y, endY = size.height), alpha = a)
            }
        }
    }
    // Glühbirnen-Schild
    val signW = w * 0.8f
    val signH = signW * 0.5f
    val pop = back(seg(t, 1.1f, 1.7f))
    Box(
        Modifier.align(Alignment.TopCenter).padding(top = h * 0.3f).size(signW, signH).graphicsLayer {
            scaleX = pop; scaleY = pop; alpha = if (t < 1.1f) 0f else 1f
        }.clip(RoundedCornerShape(18.dp)).background(Color(0xFF141B45)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(Gold, Offset(2f, 2f), Size(size.width - 4f, size.height - 4f), CornerRadius(18.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(4.dp.toPx()))
            val step = size.width / 11f
            var n = 0
            fun bulb(x: Float, y: Float) {
                val on = t > 1.2f && floor((t - 1.2f) * 10f + n).toInt() % 2 == 0
                drawCircle(Color(0xFFFFF3C4).copy(alpha = if (on) 1f else .25f), 4.5.dp.toPx(), Offset(x, y))
                n++
            }
            var x = step / 2
            while (x < size.width) { bulb(x, 9.dp.toPx()); bulb(size.width - x, size.height - 9.dp.toPx()); x += step }
        }
        ShowText("BUZZER", 54)
    }
    val mini = back(seg(t, 1.6f, 2.1f))
    Box(Modifier.align(Alignment.TopCenter).padding(top = h * 0.3f + signH + 12.dp).graphicsLayer { scaleX = mini; scaleY = mini }) { Mascot(w * 0.28f) }
}

/** Publikum, Konfetti und Vorhang liegen über dem Schild. */
@Composable
private fun BoxScope.CurtainFront(t: Float, h: Dp, bits: Pair<List<Bit>, List<Bit>>, variant: Int) {
    Canvas(Modifier.fillMaxSize()) {
        crowd(t, variant)
        confetti(t, bits)
        val open = easeInOut(seg(t, .3f, 1.4f))
        val half = size.width * .52f
        for (side in listOf(0, 1)) {
            val left = if (side == 0) -open * half else size.width - half + open * half
            var x = 0f
            while (x < half) {
                drawRect(Color(0xFF8E1414), Offset(left + x, 0f), Size(14f, size.height))
                drawRect(Color(0xFFB71C1C), Offset(left + x + 14f, 0f), Size(12f, size.height))
                drawRect(Color(0xFF7A1010), Offset(left + x + 26f, 0f), Size(8f, size.height))
                x += 34f
            }
        }
        // Volant mit Goldkante
        drawRect(Color(0xFF6E0E0E), Offset.Zero, Size(size.width, 26.dp.toPx()))
        drawRect(Gold, Offset(0f, 26.dp.toPx()), Size(size.width, 4.dp.toPx()))
    }
}

@Composable
private fun BoxScope.SplashSlap(t: Float, w: Dp, h: Dp) {
    Canvas(Modifier.fillMaxSize()) {
        rays(t, Offset(size.width / 2, size.height * .45f))
        // Kraftlinien über der herabsausenden Hand
        val sp = seg(t, .5f, 1.0f)
        if (sp in 0.01f..0.99f) {
            val a = if (sp < .4f) sp / .4f else 1 - (sp - .4f) / .6f
            val dy = -40f + 110f * sp
            listOf(10f to 40f, 32f to 70f, 54f to 55f, 76f to 80f, 98f to 45f).forEach { (x, len) ->
                val k = size.width / 270f
                drawRoundRect(Color(0xFFF4F1E8).copy(alpha = a), Offset(size.width / 2 - 50f * k + x * k, size.height * .06f + (10f + (80f - len) / 2 + dy) * k), Size(3f * k, len * k), CornerRadius(2f))
            }
        }
        // Aufprall-Striche
        val im = seg(t, .9f, 1.35f)
        if (im in 0.01f..0.99f) {
            val k = size.width / 270f
            val c = Offset(size.width / 2, size.height * .52f)
            for (r in listOf(-170f, -140f, -110f, -70f, -40f, -10f)) {
                rotate(r, c) { drawRoundRect(Gold.copy(alpha = 1 - im), Offset(c.x + (50f + 60f * im) * k, c.y - 2f * k), Size(26f * k * (.3f + .7f * im), 4f * k), CornerRadius(2f)) }
            }
        }
    }
    val mascotW = w * 0.55f
    val sTimes = floatArrayOf(0f, .88f, .96f, 1.18f, 1.38f, 3f)
    Box(Modifier.align(Alignment.TopCenter).padding(top = h * 0.35f).graphicsLayer {
        scaleX = kf(t, sTimes, floatArrayOf(1f, 1f, 1.3f, .92f, 1f, 1f))
        scaleY = kf(t, sTimes, floatArrayOf(1f, 1f, .55f, 1.1f, 1f, 1f))
        transformOrigin = TransformOrigin(.5f, 1f)
    }) { Mascot(mascotW) }
    // Flache Hand mit Kraftlinien
    val hTimes = floatArrayOf(0f, .64f, .9f, 1.02f, 1.28f, 1.6f)
    val unit = w.value / 270f
    Image(
        painterResource(R.drawable.hand_slap),
        contentDescription = null,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = h * 0.04f).size(w * .56f, w * .26f).graphicsLayer {
            translationX = kf(t, hTimes, floatArrayOf(40f, 30f, 0f, 0f, 0f, 60f)) * unit * density + w.toPx() * .06f
            translationY = kf(t, hTimes, floatArrayOf(-150f, -110f, 112f, 120f, 108f, -170f)) * unit * density
            rotationZ = kf(t, hTimes, floatArrayOf(-28f, -34f, 0f, 0f, 0f, -30f))
            transformOrigin = TransformOrigin(.8f, .5f)
        },
    )
    // Comic-Blase
    val b = back(seg(t, .95f, 1.55f))
    Box(
        Modifier.align(Alignment.TopCenter).padding(top = h * 0.13f).size(w * .66f, w * .4f).graphicsLayer {
            scaleX = b; scaleY = b; rotationZ = -8f - (1 - b) * 12f
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pts = listOf(50 to 0, 60 to 22, 84 to 6, 78 to 32, 100 to 40, 80 to 56, 96 to 80, 70 to 74, 60 to 100, 46 to 78, 22 to 96, 26 to 70, 0 to 66, 18 to 48, 4 to 24, 30 to 28)
            val p = Path()
            pts.forEachIndexed { i, (x, y) ->
                val px = x / 100f * size.width
                val py = y / 100f * size.height
                if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
            }
            p.close()
            drawPath(p, Cyan)
        }
        Text("BZZZT!", color = Ground, fontFamily = LocalBuzzerTheme.current.display, fontSize = 34.sp)
    }
    val title = easeOut(seg(t, 1.6f, 2.1f))
    ShowText("BUZZER", 54, Modifier.align(Alignment.TopCenter).padding(top = h * 0.6f).graphicsLayer {
        alpha = title; translationY = (1 - title) * 60f
    })
}

// ---------------------------------------------------------------------- Spielstart: Rennszenen

/** Rennszene beim Spielstart: 0 = Die Fabel, 1 = Verfolgungsjagd, 2 = Startschuss. */
@Composable
fun RaceIntro(variant: Int, seq: Int, onDone: () -> Unit) {
    val (t, fade) = introClock(seq, onDone)
    val theme = LocalBuzzerTheme.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade }
            .background(Ground)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
    ) {
        val w = maxWidth
        val h = maxHeight
        val v = variant % RACE_VARIANTS
        // Bühne: Himmel, Tribüne mit Publikum, Laufbahn
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF121A4A), Ground)))
            val standsTop = size.height * .18f
            val standsH = size.height * .36f
            var x = 0f
            while (x < size.width) {
                drawRect(Color(0xFF1B2563), Offset(x, standsTop), Size(10f, standsH))
                drawRect(Color(0xFF24307A), Offset(x + 10f, standsTop), Size(10f, standsH))
                x += 20f
            }
            val r = Random(5)
            repeat(140) {
                val c = if (it % 2 == 0) Gold else Cyan
                val twinkle = if ((floor(t * 4) + it).toInt() % 5 == 0) .2f else .6f
                drawCircle(c.copy(alpha = twinkle), 2.5f, Offset(r.nextFloat() * size.width, standsTop + r.nextFloat() * standsH))
            }
            val trackTop = size.height * .54f
            drawRect(Color(0xFF7A2E1C), Offset(0f, trackTop), Size(size.width, size.height - trackTop))
            val shift = if (v == 2) 0f else (t * 100f) % 36f
            x = -shift
            while (x < size.width) { drawRect(Color(0x14000000), Offset(x, trackTop), Size(18f, size.height - trackTop)); x += 36f }
            for (i in 1..3) drawRect(Color(0xBFFFFFFF), Offset(0f, trackTop + (size.height - trackTop) * i / 4.3f), Size(size.width, 3f))
            if (v == 2) drawRect(Color.White, Offset(size.width * .31f, trackTop), Size(6f, size.height - trackTop))
        }
        when (v) {
            0 -> RaceFable(t, w, h)
            1 -> RaceChase(t, w, h)
            else -> RaceStart(t, w, h)
        }
        val bannerAt = when (v) { 0 -> 2.2f; 1 -> 2.4f; else -> 1.55f }
        val b = back(seg(t, bannerAt, bannerAt + .5f))
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = h * .12f).graphicsLayer { scaleX = b; scaleY = b; rotationZ = -3f }
                .clip(RoundedCornerShape(12.dp)).background(Gold).padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            Text(if (v == 1) "QUIZ!" else "LOS!", color = Ground, fontFamily = theme.display, fontSize = 52.sp)
        }
    }
}

/** Fußpunkt-Höhe als Anteil der Bildschirmhöhe (von unten). */
private fun DrawScope.ground(b: Float) = size.height * (1 - b)

/** Hase oder Schildkröte als Emoji; die Emojis schauen nach links, daher für „nach rechts“ gespiegelt. */
@Composable
private fun Animal(emoji: String, x: Dp, bottom: Dp, h: Dp, modifier: Modifier = Modifier, faceRight: Boolean = true) {
    Text(
        emoji,
        fontSize = 50.sp,
        modifier = Modifier.offset(x = x - 30.dp, y = h - bottom - 60.dp).then(modifier).graphicsLayer { scaleX = if (faceRight) -1f else 1f },
    )
}

@Composable
private fun BoxScope.RaceFable(t: Float, w: Dp, h: Dp) {
    Canvas(Modifier.fillMaxSize()) {
        val k = size.width / 270f * 1.25f
        fun cross(start: Float, dur: Float) = -60f * k + seg(t, start, start + dur) * (size.width * 1.2f + 60f * k)
        figure(cross(0f, 2.0f), ground(.30f), k, 0, Pose.RUN, t)
        figure(cross(.3f, 2.6f), ground(.18f), k, 2, Pose.RUN, t, .2f)
        figure(cross(.15f, 2.3f), ground(.06f), k, 1, Pose.RUN, t, .1f)
    }
    // Hase flitzt vorbei und schläft ein, die Schildkröte zieht gemütlich vorbei.
    val hareX = w * (-.2f + 0.82f * easeOut(seg(t, .1f, 1.2f)))
    val asleep = t > 1.2f
    val hop = if (asleep) 0f else -14f * kotlin.math.abs(sin(t * PI.toFloat() / .2f))
    Animal("🐇", hareX, h * .24f, h, Modifier.graphicsLayer { translationY = hop * density; rotationZ = if (asleep) -80f else 0f })
    if (asleep) {
        val z = ((t - 1.3f) % 1f).coerceAtLeast(0f)
        Text("💤", fontSize = 22.sp, modifier = Modifier.offset(x = w * .66f + (18 * z).dp, y = h * .62f - (34 * z).dp).graphicsLayer { alpha = 1 - z })
    }
    val turtleX = w * (-.1f + .86f * seg(t, 0f, 3f))
    Animal("🐢", turtleX, h * .02f, h, Modifier.graphicsLayer { rotationZ = 6f * sin(t * PI.toFloat() / .5f) })
}

@Composable
private fun BoxScope.RaceChase(t: Float, w: Dp, h: Dp) {
    // Die Kette: zwei Spieler, Hase, zwei Spieler, ganz hinten die Schildkröte.
    val members = listOf("p0", "p1", "hare", "p2", "p3", "turtle")
    val spacing = w * .2f
    Canvas(Modifier.fillMaxSize()) {
        val k = size.width / 270f * 1.25f
        val sp = spacing.toPx()
        for (pass in 0..1) {
            val p = if (pass == 0) seg(t, 0f, 1.5f) else seg(t, 1.2f, 2.7f)
            if (p <= 0f || p >= 1f) continue
            val lead = -sp + p * (size.width + sp * (members.size + 1))
            members.forEachIndexed { i, m ->
                if (!m.startsWith("p")) return@forEachIndexed
                val x = lead - i * sp
                val xx = if (pass == 0) x else size.width - x
                withTransform({ if (pass == 1) scale(-1f, 1f, Offset(xx, 0f)) }) {
                    figure(xx, if (pass == 0) ground(.08f) else size.height * .54f, k, i, Pose.RUN, t, i * .1f)
                }
            }
        }
    }
    for (pass in 0..1) {
        val p = if (pass == 0) seg(t, 0f, 1.5f) else seg(t, 1.2f, 2.7f)
        if (p <= 0f || p >= 1f) continue
        val lead = -spacing + (w + spacing * (members.size + 1)) * p
        members.forEachIndexed { i, m ->
            if (m.startsWith("p")) return@forEachIndexed
            val x = lead - spacing * i
            val xx = if (pass == 0) x else w - x
            val bottom = if (pass == 0) h * .08f else h * .46f
            Animal(if (m == "hare") "🐇" else "🐢", xx, bottom, h, Modifier.graphicsLayer {
                translationY = if (m == "hare") -14f * kotlin.math.abs(sin(t * PI.toFloat() / .2f)) * density else 0f
            }, faceRight = pass == 0)
        }
    }
}

@Composable
private fun BoxScope.RaceStart(t: Float, w: Dp, h: Dp) {
    val theme = LocalBuzzerTheme.current
    Canvas(Modifier.fillMaxSize()) {
        val k = size.width / 270f * 1.25f
        val go = 1.6f
        fun sprint(x0: Float) = x0 * size.width + easeIn(seg(t, go, go + 1f)) * size.width * 1.1f
        val pose = if (t < go) Pose.CROUCH else Pose.RUN
        figure(sprint(.20f), ground(.30f), k, 0, pose, t)
        figure(sprint(.18f), ground(.06f), k, 1, pose, t, .15f)
        // Staubwolke beim Start
        val d = seg(t, go, go + .8f)
        if (d in 0.01f..0.99f) {
            for (i in 0 until 6) {
                drawCircle(Color(0xFFF0DCC8).copy(alpha = .55f * (1 - d)), 8f * k * (.4f + 1.8f * d), Offset(size.width * .22f + i * 14f * k - 30f * k * d, ground(.08f) - (i % 2) * 30f * k - 12f * k * d))
            }
        }
    }
    val hareX = w * (.16f + easeIn(seg(t, 1.6f, 2.6f)) * 1.1f)
    Animal("🐇", hareX, h * .2f, h)
    val turtleX = w * (.06f + .14f * seg(t, 1.6f, 3f))
    Animal("🐢", turtleX, h * -.01f, h, Modifier.graphicsLayer { rotationZ = 6f * sin(t * PI.toFloat() / .5f) })
    // Zielflagge wedelt
    Text("🏁", fontSize = 30.sp, modifier = Modifier.offset(x = w * .33f, y = h * .47f).graphicsLayer {
        transformOrigin = TransformOrigin(.1f, 1f)
        rotationZ = if (t in 1.45f..2.45f) 27f * sin((t - 1.45f) * PI.toFloat() / .25f) else -30f
    })
    // „Auf die Plätze … Fertig …“
    for ((text, start) in listOf("Auf die Plätze …" to .1f, "Fertig …" to .85f)) {
        val p = seg(t, start, start + .7f)
        if (p > 0f && p < 1f) {
            Text(text, color = Color(0xFFF4F1E8), fontFamily = theme.display, fontSize = 28.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = h * .13f).graphicsLayer {
                    alpha = if (p < .2f) p / .2f else if (p > .85f) (1 - p) / .15f else 1f
                    val s = .6f + .4f * seg(p, 0f, .2f)
                    scaleX = s; scaleY = s
                })
        }
    }
    val say = seg(t, 2.3f, 2.6f)
    Text("Ich komm ja schon!", color = Ground, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        modifier = Modifier.offset(x = w * .06f, y = h * .62f).graphicsLayer { alpha = say }
            .clip(RoundedCornerShape(10.dp)).background(Color.White).padding(horizontal = 8.dp, vertical = 3.dp))
}
