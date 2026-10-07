package com.os4.musiccover.ui.screen.features

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.os4.musiccover.R
import com.os4.musiccover.ui.util.isInDarkTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.pow

/**
 * 高德's bus and subway trip, shown rather than described, in the islands' own drawing
 * (IslandDemo, SkeuoKit): unlocked, the trip as the super island over the camera, opened into its
 * expanded card and the train on a stop; on the lock screen, the island opened into its card, the
 * train going along the bar a stop at a time; and the island opening on its own at a transfer and
 * at the arrival - ColorOS's strong reminders, the only two that float - and going once the trip
 * is over.
 *
 * Only the islands and their cards: the lock screen page a tap on the trip's island can open is
 * off for now (AmapTransitScene), so it is not drawn. Nor is 小爱建议's ride code, which this
 * page's switch does not govern.
 */
private val PICTURE_H = 236.dp
private const val PICTURE_FILL = 0.9f
private val PICTURE_AIR = PICTURE_H * (1f - PICTURE_FILL) / 2f

@Composable
fun TransitDemo(modifier: Modifier = Modifier) {
    DemoPager(DEMO_PAGES, modifier, pictureAir = PICTURE_AIR) { page, playing, done ->
        DemoPage(page, playing, done)
    }
}

private val DEMO_PAGES = listOf(
    DemoText(R.string.demo_transit_island_title),
    DemoText(R.string.demo_transit_ride_title),
    DemoText(R.string.demo_transit_alert_title),
)

@Composable
private fun DemoPage(page: Int, playing: Boolean, onDone: () -> Unit) {
    val pal = skeuoPalette(isInDarkTheme())
    val measurer = rememberTextMeasurer()
    val clockSp = with(LocalDensity.current) { CLOCK_UNITS.toSp() }
    val scene = remember(page) { TripScene() }
    LaunchedEffect(playing) {
        scene.reset(page)
        if (!playing) return@LaunchedEffect
        when (page) {
            0 -> scene.playIsland()
            1 -> scene.playRide()
            else -> scene.playAlerts()
        }
        onDone()
    }
    Canvas(Modifier.fillMaxWidth().height(PICTURE_H).clipToBounds()) {
        drawScene(scene, page, pal, measurer, clockSp)
    }
}

// ---- the phone and the row, in IslandDemo's units

private const val PW = PHONE_W
private const val PH = PHONE_H
private const val RY = 196f
private const val ISLAND_H = 13f
private const val CLOCK_UNITS = 15f
private val DISC_X = floatArrayOf(12.5f, 87.5f)

private data class TB(val x: Float, val y: Float, val w: Float, val h: Float) {
    val cx get() = x + w / 2f
    val cy get() = y + h / 2f
    val right get() = x + w
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun lerpB(a: TB, b: TB, t: Float) = TB(lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.w, b.w, t), lerp(a.h, b.h, t))
private fun smooth(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** The trip's island alone in the lock screen's row, and the card it opens into (the large template). */
private val PILL = TB(23f, RY - ISLAND_H / 2f, 54f, ISLAND_H)
private val CARD = TB(7f, 138f, 86f, 40f)
private const val CARD_R = 7f

/**
 * Unlocked: the super island over the camera - the line's chip and its words to the left of the
 * hole, the stops left to the right - and its expanded card hanging from the top of the screen.
 */
private val CAMERA = Offset(PW / 2f, 7.4f)
private val SUPER = TB(24f, 2.6f, 52f, 9.6f)
private val SUPER_CARD = TB(4f, 2.6f, 92f, 42f)
private const val SUPER_CARD_R = 12f

/** The progress bar across the card: the template's 12dp bar, the car, pin and flag on it. */
private const val BAR_X0 = 6f
private const val BAR_X1 = 80f
private const val BAR_Y = 30f
private const val BAR_H = 2.6f

/** What the island is saying: the ride, the transfer, the arrival. */
private enum class Leg { RIDE, TRANSFER, ARRIVE }

/** Two lines, so a transfer has somewhere to go: 3号线 in the finger's blue, 8号线 in green. */
private val LINE_B = Color(0xFF34B36B)
private val LINE_NAMES = arrayOf("3", "8")

// ---- motion: the module's springs, as IslandDemo has them

private fun jelly(response: Float, damping: Float): AnimationSpec<Float> =
    spring(dampingRatio = damping, stiffness = (2.0 * PI / response).pow(2).toFloat(), visibilityThreshold = 0.0005f)

private val SHOW = jelly(0.35f, 0.95f)
private val MORPH = jelly(0.38f, 0.86f)
private val GESTURE = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)
/** A train between two stops: away gently, in gently. */
private val HOP = CubicBezierEasing(0.45f, 0f, 0.3f, 1f)

private class TripScene {
    val cam = DemoCamera()
    val finger = Finger()
    /** 0 nothing there, 1 the island: it goes into hiding at 0.6 of itself. */
    val appear = Animatable(1f)
    /** 0 the island, 1 its card. */
    val open = Animatable(0f)
    val press = Animatable(0f)
    /** How far along the ride's bar. */
    val ride = Animatable(0f)
    /** What is said, and what was said before it while one fades into the other. */
    var leg by mutableStateOf(Leg.RIDE)
    var legFrom by mutableStateOf(Leg.RIDE)
    val legMix = Animatable(1f)
    /** Which line the ride is on, and the one it was on, for the same fade. */
    var line by mutableIntStateOf(0)
    var lineFrom by mutableIntStateOf(0)

    suspend fun reset(page: Int) {
        cam.reset()
        finger.reset()
        open.snapTo(0f); press.snapTo(0f); legMix.snapTo(1f); appear.snapTo(1f)
        leg = Leg.RIDE; legFrom = Leg.RIDE
        line = 0; lineFrom = 0
        ride.snapTo(when (page) { 0 -> 0.27f; 1 -> 0.06f; else -> 0.82f })
    }

    /** Says something else, the old words out as the new ones come in. */
    suspend fun say(next: Leg, nextLine: Int = line, ms: Int = 320) {
        legFrom = leg; lineFrom = line
        leg = next; line = nextLine
        legMix.snapTo(0f)
        legMix.animateTo(1f, tween(ms))
    }

    private suspend fun tapAt(x: Float, y: Float) = coroutineScope {
        finger.arrive(x, y, fromDx = 26f, fromDy = 30f)
        finger.press(110)
        press.animateTo(1f, tween(110))
        launch { finger.lift() }
        launch { press.animateTo(0f, SHOW) }
        launch { open.animateTo(1f, MORPH) }
    }

    /**
     * Unlocked, on the home screen: the super island tapped open into its expanded card, the train
     * on to the next stop, and the card swiped back up into the island.
     */
    suspend fun playIsland() {
        delay(300)
        cam.focus(50f, 30f, 2f, 650)
        tapAt(SUPER.cx, SUPER.cy)
        delay(350)
        ride.animateTo(0.5f, tween(650, easing = HOP))
        delay(800)
        val at = SUPER_CARD.cy + 6f
        finger.arrive(50f, at, fromDx = 26f, fromDy = 30f)
        finger.press()
        finger.slide(50f, at - 16f, 300) { open.animateTo(0.7f, tween(300, easing = GESTURE)) }
        coroutineScope {
            launch { finger.lift(ripple = false) }
            launch { open.animateTo(0f, MORPH) }
        }
        delay(500)
        cam.wide(700)
        delay(300)
    }

    /** The ride, a stop at a time along the bar, past the stop the card marks half way. */
    suspend fun playRide() {
        delay(300)
        cam.focus(50f, 168f, 1.9f, 650)
        tapAt(PILL.cx, RY)
        delay(300)
        for (to in floatArrayOf(0.27f, 0.5f, 0.73f)) {
            ride.animateTo(to, tween(650, easing = HOP))
            delay(380)
        }
        delay(200)
        finger.arrive(50f, CARD.cy, fromDx = 30f, fromDy = -20f)
        finger.press()
        finger.slide(50f, CARD.cy + 18f, 340) { open.animateTo(0.7f, tween(340, easing = GESTURE)) }
        coroutineScope {
            launch { finger.lift(ripple = false) }
            launch { open.animateTo(0f, MORPH) }
        }
        delay(500)
        cam.wide(700)
        delay(300)
    }

    /**
     * Nobody touches it: the transfer opens the card by itself, says which line to change to and
     * folds back with the new line in the island; the arrival opens it again, the train at the
     * flag, and the island goes once the trip is done.
     */
    suspend fun playAlerts() {
        delay(300)
        cam.focus(50f, 168f, 1.9f, 650)
        delay(250)
        say(Leg.TRANSFER, ms = 200)
        open.animateTo(1f, MORPH)
        delay(1300)
        open.animateTo(0f, MORPH)
        ride.snapTo(0.12f)
        say(Leg.RIDE, nextLine = 1)
        delay(700)
        ride.animateTo(1f, tween(500))
        say(Leg.ARRIVE, ms = 200)
        open.animateTo(1f, MORPH)
        delay(1300)
        open.animateTo(0f, MORPH)
        delay(350)
        appear.animateTo(0f, SHOW)
        delay(400)
        cam.wide(700)
        delay(300)
    }
}

// ---- drawing

private fun DrawScope.drawScene(sc: TripScene, page: Int, pal: SkeuoPalette, measurer: TextMeasurer,
                                clockSp: TextUnit) {
    val (camS, camO) = sc.cam.view(size.width, size.height, fill = PICTURE_FILL)
    withTransform({
        translate(camO.x, camO.y)
        scale(camS, camS, Offset.Zero)
    }) {
        drawPhoneScreen(pal)
        val screen = Path().apply { addRoundRect(RoundRect(0f, 0f, PW, PH, CornerRadius(PHONE_CORNER))) }
        clipPath(screen) {
            if (page == 0) {
                drawHome(pal, measurer)
                drawSuperIsland(sc, pal, measurer)
            } else {
                val clock = measurer.measure("08:15", TextStyle(color = pal.frame, fontSize = clockSp,
                    fontWeight = FontWeight.Bold))
                drawText(clock, topLeft = Offset(PW / 2f - clock.size.width / 2f, 18f))
                drawRoundRect(pal.pill, Offset(PW / 2f - 13f, 18f + clock.size.height + 2f), Size(26f, 2.6f),
                    CornerRadius(1.3f))
                for (x in DISC_X) {
                    drawCircle(pal.pill, ISLAND_H / 2f, Offset(x, RY))
                    drawCircle(pal.line, ISLAND_H * 0.16f, Offset(x, RY), style = Stroke(0.9f))
                }
                drawTrip(sc, pal, measurer)
            }
        }
        drawPhoneFrame(pal)
    }
    drawFinger(sc.finger, pal) { x, y -> Offset(camO.x + x * camS, camO.y + y * camS) }
}

/** The home screen as wireframe: the status bar, four rows of apps with their names, the dock. */
private fun DrawScope.drawHome(pal: SkeuoPalette, measurer: TextMeasurer) {
    val time = measurer.measure("08:15", TextStyle(color = pal.frame, fontSize = 4.2f.toSp(),
        fontWeight = FontWeight.SemiBold))
    drawText(time, topLeft = Offset(9f, CAMERA.y - time.size.height / 2f))
    drawRoundRect(pal.frame, Offset(PW - 17f, CAMERA.y - 1.5f), Size(7f, 3f), CornerRadius(0.9f),
        style = Stroke(0.5f))
    drawRoundRect(pal.frame, Offset(PW - 16.2f, CAMERA.y - 0.8f), Size(4.4f, 1.6f), CornerRadius(0.5f))
    val icon = 14f
    val xs = floatArrayOf(11.5f, 32.5f, 53.5f, 74.5f)
    for (row in 0 until 4) {
        val y = 58f + row * 26f
        for (x in xs) {
            drawRoundRect(pal.pill, Offset(x, y), Size(icon, icon), CornerRadius(4f))
            drawRoundRect(pal.line, Offset(x + 3f, y + icon + 2.4f), Size(icon - 6f, 1.4f), CornerRadius(0.7f),
                alpha = 0.6f)
        }
    }
    for (i in 0 until 2) {
        drawCircle(pal.frame.copy(alpha = if (i == 0) 0.8f else 0.3f), 0.9f, Offset(PW / 2f - 2f + i * 4f, 170f))
    }
    for (x in xs) drawRoundRect(pal.pill, Offset(x, 182f), Size(icon, icon), CornerRadius(4f))
}

/** The super island is black whatever the theme, and what is on it is white. */
private fun nightOf(pal: SkeuoPalette) = SkeuoPalette(
    frame = Color.White.copy(alpha = 0.45f), pill = Color(0xFF0E0E0E), pillOn = Color.White.copy(alpha = 0.16f),
    line = Color.White.copy(alpha = 0.85f), accent = pal.accent, screen = Color.Black, dark = true,
)

/** The super island between its capsule over the camera and its expanded card. */
private fun DrawScope.drawSuperIsland(sc: TripScene, pal: SkeuoPalette, measurer: TextMeasurer) {
    val night = nightOf(pal)
    val m = sc.open.value
    val raw = lerpB(SUPER, SUPER_CARD, m.coerceIn(-0.03f, 1.03f))
    val p = sc.press.value * 0.06f
    val b = TB(raw.cx - raw.w * (1f - p) / 2f, raw.y, raw.w * (1f - p), raw.h * (1f - p))
    val r = lerp(b.h / 2f, SUPER_CARD_R, smooth(0f, 0.6f, m)).coerceAtMost(b.h / 2f)
    drawRoundRect(night.pill, Offset(b.x, b.y), Size(b.w, b.h), CornerRadius(r))
    val clip = Path().apply { addRoundRect(RoundRect(b.x, b.y, b.right, b.y + b.h, CornerRadius(r))) }
    clipPath(clip) {
        val small = 1f - smooth(0f, 0.4f, m)
        if (small > 0.01f) local(b, SUPER) { superContent(night, measurer, small) }
        val big = smooth(0.55f, 1f, m)
        if (big > 0.01f) {
            // The card's own layout, a little in from the expanded card's rounder corners.
            val inner = TB(b.x + 3f * m, b.y + 1f * m, b.w - 6f * m, b.h - 2f * m)
            local(inner, CARD) { cardContent(sc, night, measurer, Leg.RIDE, 0, big) }
        }
    }
    // The camera, through whatever is over it.
    drawCircle(Color.Black, 2.2f, CAMERA)
    drawCircle(Color.White.copy(alpha = 0.12f), 2.2f, CAMERA, style = Stroke(0.4f))
}

/** The capsule's two halves either side of the hole, in its own 52 x 9.6. */
private fun DrawScope.superContent(night: SkeuoPalette, measurer: TextMeasurer, a: Float) {
    val h = SUPER.h
    chip(night, measurer, 0, 1.8f, 1.8f, 9f, h - 3.6f, a)
    words(night, 12.6f, 2.9f, 9.5f, 1.6f, a)
    words(night, 12.6f, 5.4f, 6.5f, 1.3f, 0.6f * a)
    // The stops left, to the right of the camera.
    words(night, SUPER.w - 13.5f, 3.4f, 9f, 1.7f, a)
    words(night, SUPER.w - 13.5f, 5.9f, 5.5f, 1.3f, 0.5f * a)
}

/** The lock screen's island between its pill and its card, pressed, or going. */
private fun DrawScope.drawTrip(sc: TripScene, pal: SkeuoPalette, measurer: TextMeasurer) {
    val shown = sc.appear.value
    if (shown <= 0.003f) return
    val m = sc.open.value
    val raw = lerpB(PILL, CARD, m.coerceIn(-0.03f, 1.03f))
    val p = sc.press.value * 0.05f
    val b = TB(raw.cx - raw.w * (1f - p) / 2f, raw.cy - raw.h * (1f - p) / 2f, raw.w * (1f - p), raw.h * (1f - p))
    val grow = lerp(0.6f, 1f, shown)
    val alpha = shown.coerceIn(0f, 1f)
    withTransform({ scale(grow, grow, Offset(b.cx, b.cy)) }) {
        val r = lerp(b.h / 2f, CARD_R, smooth(0f, 0.6f, m)).coerceAtMost(b.h / 2f)
        drawRoundRect(pal.pill, Offset(b.x, b.y), Size(b.w, b.h), CornerRadius(r), alpha = alpha)
        val clip = Path().apply { addRoundRect(RoundRect(b.x, b.y, b.right, b.y + b.h, CornerRadius(r))) }
        clipPath(clip) {
            val mix = sc.legMix.value
            val pillA = alpha * (1f - smooth(0f, 0.45f, m))
            val cardA = alpha * smooth(0.55f, 1f, m)
            for ((leg, line, a) in listOf(Triple(sc.legFrom, sc.lineFrom, 1f - mix), Triple(sc.leg, sc.line, mix))) {
                if (a <= 0.01f) continue
                if (pillA > 0.01f) local(b, PILL) { pillContent(pal, measurer, leg, line, pillA * a) }
                if (cardA > 0.01f) local(b, CARD) { cardContent(sc, pal, measurer, leg, line, cardA * a) }
            }
        }
    }
}

/** Draws in `home`'s own coordinates, 0,0 its corner, carried to wherever `box` is now. */
private fun DrawScope.local(box: TB, home: TB, block: DrawScope.() -> Unit) {
    withTransform({
        translate(box.x, box.y)
        scale(box.w / home.w, box.h / home.h, Offset.Zero)
    }) { block() }
}

private fun lineColour(pal: SkeuoPalette, line: Int) = if (line == 0) pal.accent else LINE_B

/** A line's chip: its number in white on its colour (AmapTransitIsland.chip). */
private fun DrawScope.chip(pal: SkeuoPalette, measurer: TextMeasurer, line: Int, x: Float, y: Float, w: Float,
                           h: Float, a: Float) {
    drawRoundRect(lineColour(pal, line), Offset(x, y), Size(w, h), CornerRadius(h * 0.26f), alpha = a)
    val t = measurer.measure(LINE_NAMES[line], TextStyle(color = Color.White.copy(alpha = a),
        fontSize = (h * 0.72f).toSp(), fontWeight = FontWeight.Bold))
    drawText(t, topLeft = Offset(x + w / 2f - t.size.width / 2f, y + h / 2f - t.size.height / 2f))
}

/** A short bar standing for words. */
private fun DrawScope.words(pal: SkeuoPalette, x: Float, y: Float, w: Float, h: Float, a: Float) =
    drawRoundRect(pal.line, Offset(x, y), Size(w, h), CornerRadius(h / 2f), alpha = a)

/** The arrival's flag, on its pole. */
private fun DrawScope.flag(colour: Color, x: Float, foot: Float, h: Float, a: Float) {
    drawLine(colour, Offset(x, foot), Offset(x, foot - h), h * 0.12f, StrokeCap.Round, alpha = a)
    val f = Path().apply {
        moveTo(x, foot - h); lineTo(x + h * 0.62f, foot - h * 0.78f); lineTo(x, foot - h * 0.56f); close()
    }
    drawPath(f, colour, alpha = a)
}

private fun DrawScope.arrow(pal: SkeuoPalette, x: Float, y: Float, s: Float, a: Float) {
    val p = Path().apply { moveTo(x - s * 0.5f, y - s); lineTo(x + s * 0.5f, y); lineTo(x - s * 0.5f, y + s) }
    drawPath(p, pal.frame, alpha = a, style = Stroke(s * 0.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** The capsule's halves (template_v2's imageTextInfoLeft / Right), in the pill's own 54 x 13. */
private fun DrawScope.pillContent(pal: SkeuoPalette, measurer: TextMeasurer, leg: Leg, line: Int, a: Float) {
    val h = ISLAND_H
    when (leg) {
        Leg.RIDE -> {
            chip(pal, measurer, line, 2.4f, 2.4f, 10f, h - 4.8f, a)
            words(pal, 15.5f, 4.1f, 17f, 1.9f, a)
            words(pal, 15.5f, 7.6f, 11f, 1.6f, 0.6f * a)
            // The stops left, the right half's words.
            words(pal, PILL.w - 12f, 5.5f, 7.5f, 1.9f, 0.8f * a)
        }
        Leg.TRANSFER -> {
            chip(pal, measurer, 0, 2.4f, 3.2f, 7.5f, h - 6.4f, a)
            arrow(pal, 11.4f, h / 2f, 2.2f, a)
            chip(pal, measurer, 1, 15f, 2.4f, 10f, h - 4.8f, a)
            words(pal, 28f, 5.5f, 14f, 1.9f, a)
        }
        Leg.ARRIVE -> {
            drawCircle(lineColour(pal, line), h * 0.34f, Offset(h / 2f, h / 2f), alpha = a)
            flag(Color.White, h / 2f - 1f, h / 2f + 2.6f, 5.2f, a)
            words(pal, 13f, 4.1f, 18f, 1.9f, a)
            words(pal, 13f, 7.6f, 11f, 1.6f, 0.6f * a)
        }
    }
}

/**
 * The card, in its own 86 x 40: the picture, the title and a line under it, and what the leg
 * adds - the ride's bar with its car, stop and flag, the transfer's two lines, the arrival's bar
 * run out to the flag.
 */
private fun DrawScope.cardContent(sc: TripScene, pal: SkeuoPalette, measurer: TextMeasurer, leg: Leg, line: Int,
                                  a: Float) {
    val colour = lineColour(pal, line)
    when (leg) {
        Leg.ARRIVE -> {
            drawCircle(colour, 5.5f, Offset(9.5f, 9.5f), alpha = a)
            flag(Color.White, 8.3f, 12.6f, 6.4f, a)
        }
        Leg.TRANSFER -> chip(pal, measurer, 0, 4f, 4f, 11f, 11f, a)
        Leg.RIDE -> chip(pal, measurer, line, 4f, 4f, 11f, 11f, a)
    }
    words(pal, 19f, 5f, 32f, 2.6f, a)
    words(pal, 19f, 10.5f, 22f, 1.9f, 0.7f * a)
    when (leg) {
        Leg.RIDE, Leg.ARRIVE -> {
            val at = if (leg == Leg.ARRIVE) 1f else sc.ride.value
            bar(pal, colour, at, a)
            // The stop half way, and the flag at the end.
            val mid = lerp(BAR_X0, BAR_X1, 0.5f)
            val passed = at >= 0.5f
            drawCircle(if (passed) colour else pal.frame, 1.6f, Offset(mid, BAR_Y - 2.4f), alpha = a)
            drawLine(if (passed) colour else pal.frame, Offset(mid, BAR_Y - 1.2f), Offset(mid, BAR_Y), 0.7f, alpha = a)
            flag(if (at >= 0.999f) colour else pal.frame, BAR_X1 - 0.4f, BAR_Y, 5f, a)
            car(colour, lerp(BAR_X0, BAR_X1, at.coerceAtMost(0.94f)), a)
        }
        Leg.TRANSFER -> {
            // From the line it is on to the one to change to, the second the larger.
            chip(pal, measurer, 0, 19f, 22f, 12f, 9f, a)
            arrow(pal, 36f, 26.5f, 2.6f, a)
            chip(pal, measurer, 1, 41f, 21f, 15f, 11f, a)
            words(pal, 60f, 25.5f, 18f, 2.2f, 0.8f * a)
        }
    }
}

/** The track, filled in the line's colour up to `at`. */
private fun DrawScope.bar(pal: SkeuoPalette, colour: Color, at: Float, a: Float) {
    drawRoundRect(pal.line, Offset(BAR_X0, BAR_Y), Size(BAR_X1 - BAR_X0, BAR_H), CornerRadius(BAR_H / 2f),
        alpha = 0.55f * a)
    val w = (BAR_X1 - BAR_X0) * at.coerceIn(0f, 1f)
    if (w > 0.1f) drawRoundRect(colour, Offset(BAR_X0, BAR_Y), Size(w, BAR_H), CornerRadius(BAR_H / 2f), alpha = a)
}

/** The train on the bar: the line's colour gone a quarter darker, its windows lit. */
private fun DrawScope.car(colour: Color, x: Float, a: Float) {
    val body = lerpColor(colour, Color.Black, 0.25f)
    val w = 9f
    val h = 5f
    val top = BAR_Y + BAR_H / 2f - h / 2f
    drawRoundRect(body, Offset(x - w / 2f, top), Size(w, h), CornerRadius(h / 2f), alpha = a)
    drawRoundRect(Color.White, Offset(x - w / 2f + 1.6f, top + 1.2f), Size(w - 3.6f, 1.4f), CornerRadius(0.7f),
        alpha = 0.75f * a)
}
