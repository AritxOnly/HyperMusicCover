package com.os4.musiccover.ui.screen.features

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.os4.musiccover.ui.util.isInDarkTheme
import com.os4.musiccover.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The lock screen islands, shown rather than described: a phone drawn in outline, and on it
 * what the row of islands really does - switched under a swipe, opened into a card, exchanged
 * with the card and turned round by taps that come faster than anything can land, and pulled
 * back down; the full-screen pages three of them open, one after another; and the stack island
 * spreading out with the whole row as the list, and folded back.
 *
 * Drawn the way HyperOS draws its 趣味拟物 haptics cards (see SkeuoKit): the phone as a line,
 * the screen as grey wireframe, a blue dot for the finger that presses with a halo and lifts with
 * a ring. The motion is ours: every spring is the one the module runs on the lock screen
 * (MiniPlayerRuntime: CHANGE, APPEAR, SHOW, the card morph's), so what plays here is what the
 * phone does, at its own pace.
 */
/**
 * The picture's box, the camera fill inside it, and what that leaves empty under the phone. The
 * pager centres the title under the phone rather than under the box, so it is handed the air.
 */
private val PICTURE_H = 236.dp
private const val PICTURE_FILL = 0.9f
private val PICTURE_AIR = PICTURE_H * (1f - PICTURE_FILL) / 2f

@Composable
fun IslandDemo(modifier: Modifier = Modifier) {
    DemoPager(DEMO_PAGES, modifier, pictureAir = PICTURE_AIR) { page, playing, done ->
        DemoPage(page, playing, done)
    }
}

private val DEMO_PAGES = listOf(
    DemoText(R.string.demo_island_switch_title),
    DemoText(R.string.demo_island_immersive_title),
    DemoText(R.string.demo_island_stack_title),
)

@Composable
private fun DemoPage(page: Int, playing: Boolean, onDone: () -> Unit) {
    val pal = skeuoPalette(isInDarkTheme())
    val measurer = rememberTextMeasurer()
    val clockSp = with(LocalDensity.current) { CLOCK_UNITS.toSp() }
    val scene = remember(page) { Scene() }
    LaunchedEffect(playing) {
        scene.reset(page)
        if (!playing) return@LaunchedEffect
        when (page) {
            0 -> scene.playMain()
            1 -> scene.playImmersive()
            else -> scene.playStack()
        }
        onDone()
    }
    Canvas(Modifier.fillMaxWidth().height(PICTURE_H).clipToBounds()) {
        drawScene(scene, page, pal, measurer, clockSp)
    }
}

// ---- the phone, in SkeuoKit's units: 100 wide, as tall as the phone's screen is for its width

private const val PW = PHONE_W
private const val PH = PHONE_H
private const val RY = 196f
private const val ISLAND_H = 13f
private const val CLOCK_UNITS = 15f
private val DISC_X = floatArrayOf(12.5f, 87.5f)

/** A box in the phone's units. */
private data class B(val x: Float, val y: Float, val w: Float, val h: Float) {
    val cx get() = x + w / 2f
    val cy get() = y + h / 2f
    val right get() = x + w
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun lerpB(a: B, b: B, t: Float) = B(lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.w, b.w, t), lerp(a.h, b.h, t))
private fun circle(cx: Float, cy: Float, d: Float) = B(cx - d / 2f, cy - d / 2f, d, d)
private fun smooth(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** A box pressed in about its centre by `k` of itself. */
private fun pressedBy(b: B, k: Float): B =
    if (k == 0f) b else B(b.cx - b.w * (1f - k) / 2f, b.cy - b.h * (1f - k) / 2f, b.w * (1f - k), b.h * (1f - k))

/** The pill alone in the row, and beside a small island: the row's group stays centred. */
private val PILL = B(23f, RY - ISLAND_H / 2f, 54f, ISLAND_H)
private val PILL_SMALL = B(23f, RY - ISLAND_H / 2f, 38.5f, ISLAND_H)
private val SMALL = circle(PILL_SMALL.right + 2.5f + ISLAND_H / 2f, RY, ISLAND_H)
private val NOTE_CARD = B(7f, 150f, 86f, 26f)
/** The countdown's row: taller than a message's, the time large and its bar and buttons under it. */
private val TIMER_CARD = B(7f, 136f, 86f, 40f)
private val TIMER_ART = B(TIMER_CARD.x + 4f, TIMER_CARD.y + 4f, 10f, 10f)
private val NOTE_ART = B(NOTE_CARD.x + 3.5f, NOTE_CARD.y + 3.5f, 9f, 9f)
private val MEDIA_CARD = B(7f, 124f, 86f, 54f)
private val MEDIA_ART = B(MEDIA_CARD.x + 4.5f, MEDIA_CARD.y + 4.5f, 17f, 17f)
/** 高德's focus row: the turn arrow large on the left, the distance and the road beside it. */
private val NAV_CARD = B(7f, 144f, 86f, 32f)
private val NAV_ART = B(NAV_CARD.x + 4f, NAV_CARD.y + 4f, 24f, 24f)
/** A mail's row: the subject, and two lines of what it says. */
private val MAIL_CARD = B(7f, 144f, 86f, 32f)
private val MAIL_ART = B(MAIL_CARD.x + 3.5f, MAIL_CARD.y + 3.5f, 10f, 10f)

/** Which island: only its mark tells them apart - the one colour on the screen is the finger's. */
private enum class Kind { MUSIC, TIMER, MESSAGE, NAV, MAIL, STACK }

/**
 * The stack island's list, as the lock screen lays it out when the row spreads: the media card at
 * the top, then the ordinary notifications, each a message's row.
 */
private val LIST_MEDIA = B(7f, 44f, 86f, 54f)
private val LIST_NOTES = List(3) { B(7f, 101f + it * 29f, 86f, 26f) }

/** What is behind the lock screen on the full-screen page: the cover, 高德's map, the countdown's page. */
private const val BG_COVER = 0
private const val BG_MAP = 1
private const val BG_COUNTDOWN = 2

// ---- motion: the module's own springs, response (s) and damping as MiniPlayerRuntime has them

private fun jelly(response: Float, damping: Float): AnimationSpec<Float> =
    spring(dampingRatio = damping, stiffness = (2.0 * PI / response).pow(2).toFloat(), visibilityThreshold = 0.0005f)

private val CHANGE = jelly(0.4f, 0.82f)
private val APPEAR = jelly(0.5f, 0.7f)
private val SHOW = jelly(0.35f, 0.95f)
private val MORPH = jelly(0.38f, 0.86f)
private val GESTURE = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)
/** The super island's pulse curve (sinInOut). */
private val SIN_IN_OUT = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
/** ImmersiveHost's page fade: the cover's crossfade time, ease-out cubic. */
private val PAGE_FADE = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

private class Scene {
    /** Where the page is looking: the whole phone, or close in on what is happening. */
    val cam = DemoCamera()
    val finger = Finger()
    /** The second finger, for taps that come before the first one's ring has run out. */
    val finger2 = Finger()
    /** The finger's pull on the pill, -1..1: it narrows and leans as islandDrag has it. */
    val drag = Animatable(0f)
    /**
     * The first page's stage: 0 before the swipe, 1 the swipe's springs, 3 the pill opening into
     * its card, 4 the exchange the taps keep turning round, 5 the card pulled back into its island.
     */
    var stage by mutableIntStateOf(0)
    val sw = Animatable(0f)
    val ghost = Animatable(0f)
    val emerge = Animatable(0f)
    /**
     * The first page's three islands from the tap on, each on springs of its own, and who holds
     * which place: the pill's, the small island's, and the card. Places are the taps' to go by -
     * a tap belongs to whoever holds the place, landed or still on its way there.
     */
    val timer = Pose(Kind.TIMER)
    val message = Pose(Kind.MESSAGE)
    val mail = Pose(Kind.MAIL)
    val poses = listOf(timer, message, mail)
    var seatPill: Pose = message
    var seatSmall: Pose = mail
    var expanded: Pose? = null
    /**
     * The exchanges on both pages: which one is running on the full-screen page (0 none yet), its
     * progress - one spring for both islands, as MiniPlayerRuntime's Exchange leads with one
     * morph and has the other follow it - and the two places' presses.
     */
    var exStage by mutableIntStateOf(0)
    val ex = Animatable(0f)
    val pressPill = Animatable(0f)
    val pressSmall = Animatable(0f)
    /** The full-screen page: the page going, the page coming, how far it has come in. */
    var bgFrom by mutableIntStateOf(BG_COVER)
    var bgTo by mutableIntStateOf(BG_COVER)
    val bgMix = Animatable(1f)
    /** The countdown's run through the page, 0..1. */
    val tick = Animatable(0f)
    /**
     * The stack island's page: how far the row is spread out as the list (0 the row, 1 the list),
     * the one progress every island and the clock ride on, as the lock screen's Spread has the
     * stack's scroll; and the small island's swell for a message come in.
     */
    val spread = Animatable(0f)
    val pulse = Animatable(0f)

    suspend fun reset(page: Int) {
        cam.reset()
        finger.reset(); finger2.reset(); drag.snapTo(0f)
        stage = 0
        for (a in listOf(sw, ghost, emerge, ex, pressPill, pressSmall, tick, spread, pulse)) a.snapTo(0f)
        exStage = 0
        bgFrom = BG_COVER; bgTo = BG_COVER
        bgMix.snapTo(1f)
    }

    /**
     * The first page, in one run.
     *
     * The swipe: two islands and a third behind them, switched left - the small island grows into
     * the pill on CHANGE, the old one shrinks into the middle on SHOW, the next slides out from
     * under the pill's end on APPEAR (SWAP_NEXT).
     *
     * The tap: the new pill opens into its card; the small island beside it grows into the place
     * it left, and the next comes out from under the pill's end into the small place.
     *
     * The interruption, three islands at once: taps faster than anything lands, on either place.
     * Each goes to whoever holds the place tapped, landed or still on its way there; that one
     * goes up as its card and the one out - still going up, or open - comes back into the place,
     * every one of them from where it is at the speed it has (MiniPlayerRuntime.tapDuringExchange).
     * Two fingers take turns, so each tap's ring runs its course. Plain notifications all three:
     * a tap on the music opens the cover and one on the countdown its page, which is the next
     * page's story, not this one's.
     *
     * The pull: the card left open is pulled down into the small island's place, and the island
     * there goes into hiding where it is, as the plugin's SmallIslandToHidden does.
     */
    suspend fun playMain() {
        delay(300)
        // One close-up that holds the row and the cards, for the whole page.
        cam.focus(50f, 168f, 1.9f, 650)
        finger.arrive(52f, RY, fromDx = 26f, fromDy = -30f)
        finger.press()
        finger.slide(30f, RY, 380) { drag.animateTo(1f, tween(380, easing = GESTURE)) }
        stage = 1
        coroutineScope {
            launch { finger.lift() }
            launch { drag.animateTo(0f, CHANGE) }
            launch { sw.animateTo(1f, CHANGE) }
            launch { ghost.animateTo(1f, SHOW) }
            launch { emerge.animateTo(1f, APPEAR) }
        }
        delay(250)
        // From the tap on, each island is on springs of its own.
        timer.snap(PILL_SMALL, 0f)
        message.snap(SMALL, 0f)
        mail.snap(circle(PILL_SMALL.right - ISLAND_H / 2f, RY, ISLAND_H * 0.6f), 0f, shown = 0f)
        stage = 3
        finger.arrive(PILL_SMALL.cx, RY, fromDx = 24f, fromDy = -22f)
        finger.press(110)
        timer.press.animateTo(1f, tween(120))
        coroutineScope {
            launch { timer.press.animateTo(0f, CHANGE) }
            launch { finger.lift() }
            launch { timer.to(TIMER_CARD, 1f, MORPH) }
            launch { delay(60); message.to(PILL_SMALL, 0f, CHANGE) }
            launch { delay(60); mail.vis.animateTo(1f, tween(140)) }
            launch { delay(60); mail.to(SMALL, 0f, APPEAR) }
        }
        expanded = timer
        seatPill = message
        seatSmall = mail
        delay(250)
        coroutineScope {
            // Which place each tap lands on (true: the small island's), how long before it, and
            // where on the island: uneven, as hands are.
            val small = booleanArrayOf(false, true, false, false, true)
            val waits = longArrayOf(0L, 40L, 20L, 70L, 30L)
            val dx = floatArrayOf(0f, 1f, -3f, 2.5f, -1f)
            val dy = floatArrayOf(0f, -1.2f, 1f, -0.6f, 1.2f)
            for (i in small.indices) {
                delay(waits[i])
                val place = if (small[i]) SMALL else PILL_SMALL
                val f = if (i % 2 == 0) finger else finger2
                f.arrive(place.cx + dx[i], RY + dy[i], fromDx = if (i % 2 == 0) 12f else -12f, fromDy = -14f, ms = 110)
                f.press(50)
                val tapped = if (small[i]) seatSmall else seatPill
                val out = expanded ?: continue
                launch { tapped.press.animateTo(1f, tween(50)); tapped.press.animateTo(0f, SHOW) }
                launch { f.lift() }
                launch { tapped.to(cardBox(tapped.kind), 1f, MORPH) }
                launch { out.to(place, 0f, MORPH) }
                if (small[i]) seatSmall = out else seatPill = out
                expanded = tapped
            }
        }
        delay(450)
        val back = expanded ?: return
        val card = cardBox(back.kind)
        val hiding = seatSmall
        finger.arrive(50f, card.cy, fromDx = 30f, fromDy = -20f)
        finger.press()
        finger.slide(50f, card.cy + 18f, 340) { back.to(lerpB(card, SMALL, 0.28f), 0.72f, tween(340, easing = GESTURE)) }
        coroutineScope {
            launch { finger.lift(ripple = false) }
            launch { back.to(SMALL, 0f, MORPH) }
            launch { hiding.vis.animateTo(0f, tween(260)) }
        }
        seatSmall = back
        expanded = null
        delay(500)
        cam.wide(700)
        delay(300)
    }

    /**
     * The full-screen page: the cover up with the media card, 高德's navigation and the countdown
     * in the row. Each tap is an exchange as on the first page, and the page it opens takes the
     * lock screen's background - the later one wins (ImmersiveHost, "the cover and an immersive
     * page are one background") - crossfading over the one before: the map in from 1.1 as
     * AmapNavScene grows it, the countdown's blurred wallpaper and disc, the cover again.
     */
    suspend fun playImmersive() = coroutineScope {
        launch { tick.animateTo(1f, tween(7000, easing = LinearEasing)) }
        delay(500)
        open(1, PILL_SMALL, pressPill, BG_MAP, fromDx = 26f, fromDy = -30f)
        delay(1000)
        open(2, SMALL, pressSmall, BG_COUNTDOWN, fromDx = 20f, fromDy = -26f)
        delay(1200)
        open(3, PILL_SMALL, pressPill, BG_COVER, fromDx = -22f, fromDy = -28f)
        delay(1000)
    }

    /**
     * The stack island's page: a message comes in and the small island swells for it (the super
     * island's 1.1 pulse); a tap spreads the whole row out as the list - the music up into the
     * media card, the stack island into its rows, the nearer first - with the clock going small
     * as it comes; a pull down on the list folds it all back, the pull followed and the rest on
     * its own, the further rows home first.
     */
    suspend fun playStack() {
        delay(500)
        pulse.animateTo(1f, tween(200, easing = SIN_IN_OUT))
        delay(100)
        pulse.animateTo(0f, tween(200, easing = SIN_IN_OUT))
        delay(350)
        finger.arrive(SMALL.cx, RY, fromDx = 20f, fromDy = -26f)
        finger.press(110)
        pressSmall.animateTo(1f, tween(110))
        coroutineScope {
            launch { finger.lift() }
            launch { pressSmall.animateTo(0f, SHOW) }
            launch { spread.animateTo(1f, MORPH) }
        }
        delay(1100)
        val at = LIST_NOTES[1].cy
        finger.arrive(50f, at, fromDx = 30f, fromDy = -20f)
        finger.press()
        finger.slide(50f, at + 26f, 380) { spread.animateTo(0.62f, tween(380, easing = GESTURE)) }
        coroutineScope {
            launch { finger.lift(ripple = false) }
            launch { spread.animateTo(0f, MORPH) }
        }
        delay(900)
    }

    private suspend fun open(n: Int, at: B, pressAt: Animatable<Float, AnimationVector1D>, bg: Int, fromDx: Float, fromDy: Float) {
        finger.arrive(at.cx, RY, fromDx = fromDx, fromDy = fromDy)
        finger.press(110)
        pressAt.animateTo(1f, tween(110))
        exStage = n
        ex.snapTo(0f)
        bgFrom = bgTo
        bgTo = bg
        bgMix.snapTo(0f)
        coroutineScope {
            launch { finger.lift() }
            launch { pressAt.animateTo(0f, SHOW) }
            launch { ex.animateTo(1f, MORPH) }
            launch { bgMix.animateTo(1f, tween(420, easing = PAGE_FADE)) }
        }
    }
}

// ---- drawing

private class Camera(val s: Float, val o: Offset) {
    fun map(x: Float, y: Float) = Offset(o.x + x * s, o.y + y * s)
}

private fun DrawScope.drawScene(sc: Scene, page: Int, pal: SkeuoPalette, measurer: TextMeasurer, clockSp: TextUnit) {
    val (camS, camO) = sc.cam.view(size.width, size.height, fill = PICTURE_FILL)
    val cam = Camera(camS, camO)
    withTransform({
        translate(cam.o.x, cam.o.y)
        scale(cam.s, cam.s, Offset.Zero)
    }) {
        drawPhoneScreen(pal)
        val screen = Path().apply { addRoundRect(RoundRect(0f, 0f, PW, PH, CornerRadius(PHONE_CORNER))) }
        clipPath(screen) {
            if (page == 1) {
                drawImmersive(sc, pal, measurer, clockSp)
            } else {
                // The lock screen as wireframe: the clock in the outline's grey, a bar for the date.
                // On the stack island's page it goes small as the list comes, as the lock screen's does.
                val k = if (page == 2) lerp(1f, 0.55f, sc.spread.value.coerceIn(0f, 1f)) else 1f
                val clock = measurer.measure("19:30", TextStyle(color = pal.frame, fontSize = clockSp,
                    fontWeight = FontWeight.Bold))
                withTransform({ scale(k, k, Offset(PW / 2f, 18f)) }) {
                    drawText(clock, topLeft = Offset(PW / 2f - clock.size.width / 2f, 18f))
                }
                drawRoundRect(pal.pill, Offset(PW / 2f - 13f * k, 18f + (clock.size.height + 2f) * k),
                    Size(26f * k, 2.6f * k), CornerRadius(1.3f * k))
                drawDiscs(pal)
                if (page == 2) drawStack(sc, pal) else drawMain(sc, pal)
            }
        }
        drawPhoneFrame(pal)
    }
    drawFinger(sc.finger, pal) { x, y -> cam.map(x, y) }
    drawFinger(sc.finger2, pal) { x, y -> cam.map(x, y) }
}

/** The flashlight and the camera either side of the row. */
private fun DrawScope.drawDiscs(pal: SkeuoPalette) {
    for (x in DISC_X) {
        drawCircle(pal.pill, ISLAND_H / 2f, Offset(x, RY))
        drawCircle(pal.line, ISLAND_H * 0.16f, Offset(x, RY), style = Stroke(0.9f))
    }
}

/** An island: its pill, its picture, and its words where there is room for them. */
private fun DrawScope.drawIsland(pal: SkeuoPalette, kind: Kind, box: B, glass: Float = 1f, content: Float = 1f,
                                 scale: Float = 1f, playButton: Boolean = kind == Kind.MUSIC,
                                 icon: Boolean = true) {
    if (glass <= 0.001f && content <= 0.001f) return
    val b = if (scale == 1f) box else B(box.cx - box.w * scale / 2f, box.cy - box.h * scale / 2f, box.w * scale, box.h * scale)
    val r = b.h / 2f
    drawRoundRect(pal.pill, Offset(b.x, b.y), Size(b.w, b.h), CornerRadius(r), alpha = glass)
    // 1 a bare circle (the small island), 0 the pill with room for its words.
    val round = 1f - ((b.w - b.h) / (b.h * 0.8f)).coerceIn(0f, 1f)
    val inset = b.h * 0.17f
    val side = b.h - inset * 2f
    val ix = lerp(b.x + inset, b.cx - side / 2f, round)
    val a = content.coerceIn(0f, 1f)
    val clip = Path().apply { addRoundRect(RoundRect(b.x, b.y, b.right, b.y + b.h, CornerRadius(r))) }
    clipPath(clip) {
        if (icon) {
            val ia = a.coerceAtLeast(glass * 0.9f)
            // Round, as the pill's own artwork is (MiniPlayerView clips it to a circle).
            drawCircle(pal.line, side / 2f, Offset(ix + side / 2f, b.cy), alpha = ia)
            drawGlyph(pal, kind, ix + side / 2f, b.cy, side, ia)
        }
        val words = a * (1f - round)
        if (words > 0.01f) {
            val tx = ix + side + inset * 1.4f
            val room = b.right - tx - (if (playButton) b.h * 0.9f else inset * 2f)
            if (room > 1f) {
                drawRoundRect(pal.line, Offset(tx, b.cy - 2.6f), Size(room * 0.62f, 1.9f), CornerRadius(0.95f),
                    alpha = words)
                drawRoundRect(pal.line, Offset(tx, b.cy + 0.9f), Size(room * 0.42f, 1.6f), CornerRadius(0.8f),
                    alpha = 0.6f * words)
            }
            if (playButton) {
                val px = b.right - b.h * 0.55f
                val tri = Path().apply {
                    moveTo(px - 1.3f, b.cy - 1.9f); lineTo(px + 1.9f, b.cy); lineTo(px - 1.3f, b.cy + 1.9f); close()
                }
                drawPath(tri, pal.frame, alpha = words)
            }
        }
    }
}

/** The picture's own mark, knocked out of its circle: a note, a clock face, a speech bubble, a turn. */
private fun DrawScope.drawGlyph(pal: SkeuoPalette, kind: Kind, cx: Float, cy: Float, side: Float, alpha: Float) {
    val c = pal.screen.copy(alpha = alpha)
    val u = side / 10f
    when (kind) {
        Kind.MUSIC -> {
            drawCircle(c, 1.4f * u, Offset(cx - 1f * u, cy + 2f * u))
            drawLine(c, Offset(cx + 0.3f * u, cy + 2f * u), Offset(cx + 0.3f * u, cy - 3f * u), 0.9f * u)
            drawLine(c, Offset(cx + 0.3f * u, cy - 3f * u), Offset(cx + 2.4f * u, cy - 2.2f * u), 0.9f * u, StrokeCap.Round)
        }
        Kind.TIMER -> {
            drawCircle(c, 3.1f * u, Offset(cx, cy + 0.3f * u), style = Stroke(0.9f * u))
            drawLine(c, Offset(cx, cy + 0.3f * u), Offset(cx, cy - 1.6f * u), 0.9f * u, StrokeCap.Round)
            drawLine(c, Offset(cx, cy + 0.3f * u), Offset(cx + 1.3f * u, cy + 1.1f * u), 0.9f * u, StrokeCap.Round)
        }
        Kind.MESSAGE -> {
            drawRoundRect(c, Offset(cx - 3f * u, cy - 2.4f * u), Size(6f * u, 4.4f * u), CornerRadius(1.4f * u))
            val tail = Path().apply {
                moveTo(cx - 1.6f * u, cy + 1.8f * u); lineTo(cx - 2.2f * u, cy + 3.4f * u); lineTo(cx - 0.2f * u, cy + 1.9f * u); close()
            }
            drawPath(tail, c)
        }
        Kind.MAIL -> {
            drawRoundRect(c, Offset(cx - 3.2f * u, cy - 2.3f * u), Size(6.4f * u, 4.6f * u), CornerRadius(0.8f * u),
                style = Stroke(0.8f * u))
            val flap = Path().apply {
                moveTo(cx - 2.8f * u, cy - 1.8f * u); lineTo(cx, cy + 0.4f * u); lineTo(cx + 2.8f * u, cy - 1.8f * u)
            }
            drawPath(flap, c, style = Stroke(0.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Kind.STACK -> {
            // The apps' pictures together, four to a folder, as AppsIcon draws them.
            val q = 2.2f * u
            for (i in 0 until 4) {
                val ox = if (i % 2 == 0) -q - 0.3f * u else 0.3f * u
                val oy = if (i < 2) -q - 0.3f * u else 0.3f * u
                drawRoundRect(c, Offset(cx + ox, cy + oy), Size(q, q), CornerRadius(0.7f * u))
            }
        }
        Kind.NAV -> {
            // A right turn ahead, as 高德's focus_large_icon draws its next manoeuvre.
            val turn = Path().apply {
                moveTo(cx - 1.4f * u, cy + 3.4f * u)
                lineTo(cx - 1.4f * u, cy - 0.6f * u)
                quadraticTo(cx - 1.4f * u, cy - 1.8f * u, cx - 0.2f * u, cy - 1.8f * u)
                lineTo(cx + 1.6f * u, cy - 1.8f * u)
            }
            drawPath(turn, c, style = Stroke(1f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
            val head = Path().apply {
                moveTo(cx + 1.2f * u, cy - 3.6f * u); lineTo(cx + 3.4f * u, cy - 1.8f * u)
                lineTo(cx + 1.2f * u, cy); close()
            }
            drawPath(head, c)
        }
    }
}

/** The finger's pull on the island it is on: narrower by up to 7%, leaning the way it goes. */
private fun pulled(b: B, d: Float): B {
    val w = b.w * (1f - 0.07f * abs(d))
    return B(b.cx - w / 2f - 1.6f * d, b.y, w, b.h)
}

/** The first page: the swipe, then the three islands on their own springs (Scene.playMain). */
private fun DrawScope.drawMain(sc: Scene, pal: SkeuoPalette) {
    if (sc.stage < 3) {
        drawSwitch(sc, pal)
        return
    }
    // Whatever is heading for the row over whatever is heading for its card, as the plugin draws
    // the container heading for the row on top (super-island-multi-island-logic).
    for (p in sc.poses.sortedBy { if (it.toRow) 1 else 0 }) drawPose(pal, p)
}

/** Where one island is, on springs of its own: its box, how far it is its card, shown, pressed. */
private class Pose(val kind: Kind) {
    val x = Animatable(0f)
    val y = Animatable(0f)
    val w = Animatable(0f)
    val h = Animatable(0f)
    /** 0 an island, 1 its card. */
    val m = Animatable(0f)
    /** 1 shown; 0 gone into hiding where it was, at 0.6 of itself (SmallIslandToHidden). */
    val vis = Animatable(1f)
    val press = Animatable(0f)
    /** Heading for the row rather than for its card; only the drawing order asks. */
    var toRow = true
    val box get() = B(x.value, y.value, w.value, h.value)

    suspend fun snap(b: B, card: Float, shown: Float = 1f) {
        x.snapTo(b.x); y.snapTo(b.y); w.snapTo(b.w); h.snapTo(b.h)
        m.snapTo(card); vis.snapTo(shown); press.snapTo(0f)
        toRow = card < 0.5f
    }

    /** Off to `b`, from where it is at the speed it has: a call while one runs turns it. */
    suspend fun to(b: B, card: Float, spec: AnimationSpec<Float>) = coroutineScope {
        toRow = card < 0.5f
        launch { x.animateTo(b.x, spec) }
        launch { y.animateTo(b.y, spec) }
        launch { w.animateTo(b.w, spec) }
        launch { h.animateTo(b.h, spec) }
        launch { m.animateTo(card, spec) }
    }
}

/** A unit of speed (phone units a second) in squash: the exchange's 0.022 per progress, over its ~50 units. */
private const val POSE_SQUASH = 0.00044f

/**
 * One island as its springs have it: pressed in by 5% as a pill and 10% as a circle, squashed by
 * its speed along the way it is going (capped as the exchange is), and shrunk towards 0.6 as it
 * goes into hiding.
 */
private fun DrawScope.drawPose(pal: SkeuoPalette, p: Pose) {
    val v = p.vis.value
    if (v <= 0.003f) return
    val raw = p.box
    val round = 1f - ((raw.w - raw.h) / (raw.h * 0.8f)).coerceIn(0f, 1f)
    val b = pressedBy(raw, p.press.value * lerp(0.05f, 0.1f, round))
    val vx = p.x.velocity
    val vy = p.y.velocity
    val k = (kotlin.math.hypot(vx, vy) * POSE_SQUASH).coerceAtMost(EX_SQUASH_MAX)
    val hide = lerp(0.6f, 1f, v)
    val c = Offset(b.cx, b.cy)
    withTransform({
        scale(hide, hide, c)
        if (k > 0.001f) {
            if (abs(vy) >= abs(vx)) scale(1f - 0.6f * k, 1f + k, c) else scale(1f + k, 1f - 0.6f * k, c)
        }
    }) { drawMorph(pal, p.kind, b, p.m.value, v) }
}

/**
 * An island `m` of the way to its card, drawn at `b` wherever that is: the frame's corners from
 * the pill's round to the card's, the pill's words out and the card's in, and the picture going
 * from its circle in the pill to its rounded square in the card.
 */
private fun DrawScope.drawMorph(pal: SkeuoPalette, kind: Kind, b: B, m: Float, alpha: Float) {
    val card = cardBox(kind)
    val art = artBox(kind)
    val r = lerp(b.h / 2f, cardRadius(kind), smooth(0f, 0.6f, m)).coerceAtMost(b.h / 2f)
    drawRoundRect(pal.pill, Offset(b.x, b.y), Size(b.w, b.h), CornerRadius(r), alpha = alpha)
    val clip = Path().apply { addRoundRect(RoundRect(b.x, b.y, b.right, b.y + b.h, CornerRadius(r))) }
    clipPath(clip) {
        val out = alpha * (1f - smooth(0f, 0.45f, m))
        if (out > 0.01f) drawIsland(pal, kind, b, glass = 0f, content = out, icon = false)
        val inn = alpha * smooth(0.55f, 1f, m)
        if (inn > 0.01f) cardContent(pal, kind, b, inn)
        val inset = ISLAND_H * 0.17f
        val side = ISLAND_H - inset * 2f
        val round = 1f - ((b.w - b.h) / (b.h * 0.8f)).coerceIn(0f, 1f)
        val from = B(lerp(b.x + inset, b.cx - side / 2f, round), b.cy - side / 2f, side, side)
        val to = B(b.x + (art.x - card.x) * b.w / card.w, b.y + (art.y - card.y) * b.h / card.h, art.w, art.h)
        val t = smooth(0.2f, 1f, m)
        val ib = lerpB(from, to, t)
        drawRoundRect(pal.line, Offset(ib.x, ib.y), Size(ib.w, ib.h), CornerRadius(lerp(ib.w / 2f, ib.w * 0.26f, t)),
            alpha = alpha)
        drawGlyph(pal, kind, ib.cx, ib.cy, ib.w, alpha)
    }
}

/** The swipe: the pill and the small island, then SWAP_NEXT's three springs. */
private fun DrawScope.drawSwitch(sc: Scene, pal: SkeuoPalette) {
    val d = sc.drag.value
    val mid = circle(PILL_SMALL.cx, RY, ISLAND_H * 0.6f)
    if (sc.stage == 0) {
        drawIsland(pal, Kind.TIMER, SMALL)
        drawIsland(pal, Kind.MUSIC, pulled(PILL_SMALL, d))
        return
    }
    val g = sc.ghost.value
    drawIsland(pal, Kind.MUSIC, lerpB(PILL_SMALL, mid, g), glass = 1f - g, content = 1f - smooth(0f, 0.5f, g))
    val e = sc.emerge.value
    val side = lerp(ISLAND_H * 0.6f, ISLAND_H, e)
    drawIsland(pal, Kind.MESSAGE, circle(lerp(PILL_SMALL.right - ISLAND_H / 2f, SMALL.cx, e), RY, side),
        glass = smooth(0f, 0.12f, e), content = smooth(0.15f, 0.7f, e))
    val s = sc.sw.value
    drawIsland(pal, Kind.TIMER, pulled(lerpB(SMALL, PILL_SMALL, s), d), content = smooth(0.35f, 0.9f, s))
}

/** An island opening into its card: the frame goes to the card's, the picture to the card's. */
private fun DrawScope.drawOpening(pal: SkeuoPalette, kind: Kind, pill: B, card: B, cardRadius: Float, m: Float,
                                  glass: Float, pillContent: Float, art: B, drawCard: DrawScope.(B, Float) -> Unit) {
    val box = lerpB(pill, card, m.coerceIn(-0.03f, 1.03f))
    val r = lerp(pill.h / 2f, cardRadius, smooth(0f, 0.6f, m))
    drawRoundRect(pal.pill, Offset(box.x, box.y), Size(box.w, box.h), CornerRadius(r), alpha = glass)
    val clip = Path().apply { addRoundRect(RoundRect(box.x, box.y, box.right, box.y + box.h, CornerRadius(r))) }
    clipPath(clip) {
        // The pill's words out over the first half, the card's in over the second.
        val out = pillContent * (1f - smooth(0f, 0.45f, m))
        if (out > 0.01f) drawIsland(pal, kind, box, glass = 0f, content = out, icon = false)
        val inn = smooth(0.55f, 1f, m)
        if (inn > 0.01f) drawCard(box, inn)
        // The picture travels between its two places, whole, all the way.
        val inset = pill.h * 0.17f
        val icon = pill.h - inset * 2f
        val from = B(box.x + inset, box.cy - icon / 2f, icon, icon)
        val a = lerpB(from, B(box.x + (art.x - card.x) * (box.w / card.w), box.y + (art.y - card.y) * (box.h / card.h),
            lerp(icon, art.w, m), lerp(icon, art.h, m)), smooth(0.2f, 1f, m))
        val alpha = glass.coerceAtLeast(pillContent)
        // A circle in the pill, the card's rounded square once it is there.
        drawRoundRect(pal.line, Offset(a.x, a.y), Size(a.w, a.h),
            CornerRadius(lerp(a.w / 2f, a.w * 0.26f, smooth(0.2f, 1f, m))), alpha = alpha)
        drawGlyph(pal, kind, a.cx, a.cy, a.w, alpha)
    }
}

/**
 * An exchange squashed by its speed as the row's boxes are (ROW_SQUASH, capped at 8%): drawn out along the way it is going
 * and narrowed across it, the picture and the words in it with it. A turn half way is where it
 * shows - the speed runs out, the shape comes back, and the other way squeezes it again.
 */
private fun squashOf(sc: Scene) = (abs(sc.ex.velocity) * EX_SQUASH).coerceAtMost(EX_SQUASH_MAX)

private const val EX_SQUASH = 0.022f
private const val EX_SQUASH_MAX = 0.08f

/**
 * `up` rising out of `slot` into its card, and `down` falling from its card into `slot`, `x` of the
 * way. The one going to the row is drawn over the other, as the plugin draws the container
 * heading for the row (super-island-multi-island-logic).
 */
private fun DrawScope.drawSwap(pal: SkeuoPalette, up: Kind, down: Kind, slot: B, x: Float, sq: Float) {
    squashed(lerpB(slot, cardBox(up), x.coerceIn(0f, 1f)), sq) { drawCardOf(pal, up, slot, x) }
    squashed(lerpB(slot, cardBox(down), (1f - x).coerceIn(0f, 1f)), sq) { drawCardOf(pal, down, slot, 1f - x) }
}

/** Draws `block` squashed about `box`'s centre: `k` longer up and down, 0.6k narrower across. */
private fun DrawScope.squashed(box: B, k: Float, block: DrawScope.() -> Unit) {
    if (k < 0.001f) {
        block()
        return
    }
    withTransform({ scale(1f - 0.6f * k, 1f + k, Offset(box.cx, box.cy)) }) { block() }
}

private fun cardBox(kind: Kind) = when (kind) {
    Kind.MUSIC -> MEDIA_CARD
    Kind.NAV -> NAV_CARD
    Kind.TIMER -> TIMER_CARD
    Kind.MAIL -> MAIL_CARD
    else -> NOTE_CARD
}

private fun artBox(kind: Kind) = when (kind) {
    Kind.MUSIC -> MEDIA_ART
    Kind.NAV -> NAV_ART
    Kind.TIMER -> TIMER_ART
    Kind.MAIL -> MAIL_ART
    else -> NOTE_ART
}

private fun cardRadius(kind: Kind) = if (kind == Kind.MESSAGE) 6f else 7f

/** Each island's own card, opening from `from`, `m` of the way. */
private fun DrawScope.drawCardOf(pal: SkeuoPalette, kind: Kind, from: B, m: Float) =
    drawOpening(pal, kind, from, cardBox(kind), cardRadius(kind), m, 1f, 1f, artBox(kind)) { box, a ->
        cardContent(pal, kind, box, a)
    }

/** What each card holds, laid out in its own box and carried by `box`, at `a`. */
private fun DrawScope.cardContent(pal: SkeuoPalette, kind: Kind, box: B, a: Float) {
    val card = cardBox(kind)
    val k = box.w / card.w
    val v = box.h / card.h
    when (kind) {
        Kind.MUSIC -> {
            val x = box.x + 26f * k
            drawRoundRect(pal.line, Offset(x, box.y + 8f * v), Size(30f * k, 2.4f), CornerRadius(1.2f), alpha = a)
            drawRoundRect(pal.line, Offset(x, box.y + 14f * v), Size(20f * k, 1.9f), CornerRadius(0.95f), alpha = 0.7f * a)
            // The seek bar - the one blue on the card, it is what is playing - and the controls under it.
            val barY = box.y + 32f * v
            drawRoundRect(pal.line, Offset(box.x + 5f * k, barY), Size(76f * k, 1.4f), CornerRadius(0.7f), alpha = 0.6f * a)
            drawRoundRect(pal.accent, Offset(box.x + 5f * k, barY), Size(32f * k, 1.4f), CornerRadius(0.7f), alpha = a)
            val cy = box.y + 44f * v
            for ((i, cx) in listOf(30f, 50f, 70f).withIndex()) {
                val px = box.x + cx * k
                val s = if (i == 1) 2.6f else 1.8f
                val tri = Path().apply {
                    if (i == 0) { moveTo(px + s, cy - s); lineTo(px - s, cy); lineTo(px + s, cy + s) }
                    else { moveTo(px - s * 0.8f, cy - s); lineTo(px + s * 1.1f, cy); lineTo(px - s * 0.8f, cy + s) }
                    close()
                }
                drawPath(tri, pal.frame, alpha = a)
            }
        }
        Kind.NAV -> {
            // 高德's focus row: the distance large, the road under it.
            val x = box.x + 33f * k
            drawRoundRect(pal.line, Offset(x, box.y + 8f * v), Size(26f * k, 4f), CornerRadius(2f), alpha = a)
            drawRoundRect(pal.line, Offset(x, box.y + 17f * v), Size(40f * k, 2f), CornerRadius(1f), alpha = 0.7f * a)
            drawRoundRect(pal.line, Offset(x, box.y + 22.5f * v), Size(22f * k, 1.7f), CornerRadius(0.85f), alpha = 0.5f * a)
        }
        Kind.TIMER -> {
            // The time large, its bar, and two buttons.
            val x = box.x + 18f * k
            drawRoundRect(pal.line, Offset(x, box.y + 5f * v), Size(28f * k, 3.6f), CornerRadius(1.8f), alpha = a)
            drawRoundRect(pal.line, Offset(x, box.y + 11.5f * v), Size(16f * k, 1.7f), CornerRadius(0.85f), alpha = 0.6f * a)
            val barY = box.y + 22f * v
            drawRoundRect(pal.line, Offset(box.x + 5f * k, barY), Size(76f * k, 1.4f), CornerRadius(0.7f), alpha = 0.6f * a)
            drawRoundRect(pal.accent, Offset(box.x + 5f * k, barY), Size(48f * k, 1.4f), CornerRadius(0.7f), alpha = a)
            for (cx in floatArrayOf(36f, 50f)) {
                drawCircle(pal.line, 3.4f, Offset(box.x + cx * k, box.y + 32f * v), alpha = 0.7f * a)
            }
        }
        Kind.MAIL -> {
            // The subject, and two lines of what it says.
            val x = box.x + 17f * k
            drawRoundRect(pal.line, Offset(x, box.y + 5f * v), Size(30f * k, 2.4f), CornerRadius(1.2f), alpha = a)
            drawRoundRect(pal.line, Offset(x, box.y + 12f * v), Size(58f * k, 1.8f), CornerRadius(0.9f), alpha = 0.7f * a)
            drawRoundRect(pal.line, Offset(x, box.y + 17.5f * v), Size(50f * k, 1.8f), CornerRadius(0.9f), alpha = 0.7f * a)
            drawRoundRect(pal.line, Offset(x, box.y + 23f * v), Size(34f * k, 1.8f), CornerRadius(0.9f), alpha = 0.5f * a)
        }
        else -> {
            val x = box.x + 16f * k
            drawRoundRect(pal.line, Offset(x, box.y + 4.4f * v), Size(24f * k, 2.2f), CornerRadius(1.1f), alpha = a)
            drawRoundRect(pal.line, Offset(x, box.y + 9.8f * v), Size(54f * k, 1.8f), CornerRadius(0.9f), alpha = 0.7f * a)
            drawRoundRect(pal.line, Offset(x, box.y + 14.6f * v), Size(38f * k, 1.8f), CornerRadius(0.9f), alpha = 0.7f * a)
            drawRoundRect(pal.line, Offset(box.right - 12f * k, box.y + 4.6f * v), Size(8f * k, 1.6f), CornerRadius(0.8f),
                alpha = 0.6f * a)
        }
    }
}

// ---- the stack island's page

/**
 * The row spread `p` of the way out as the list (Scene.playStack): the music between the pill and
 * the media card, and the stack island between the small island and its rows - the first row the
 * island itself, the others out of it after it, each on the share of the progress the lock
 * screen's pile gives it (nearer first out, further first home).
 */
private fun DrawScope.drawStack(sc: Scene, pal: SkeuoPalette) {
    val p = sc.spread.value
    val swell = 1f + 0.1f * sc.pulse.value
    val small = pressedBy(SMALL, 0.1f * sc.pressSmall.value)
    val from = B(small.cx - small.w * swell / 2f, small.cy - small.h * swell / 2f, small.w * swell, small.h * swell)
    // Further rows first, so the nearer ones are drawn over them on the way.
    for (i in LIST_NOTES.indices.reversed()) {
        val lag = i * 0.12f
        val q = ((p - lag) / (1f - lag)).coerceIn(-0.03f, 1.03f)
        if (q <= 0f && i > 0) continue
        val box = lerpB(from, LIST_NOTES[i], q)
        if (i == 0) {
            // The island itself: its apps' picture fading as its first row comes in.
            val stay = 1f - smooth(0.1f, 0.4f, q)
            if (stay > 0.01f) drawIsland(pal, Kind.STACK, box, glass = 1f, content = stay)
            val row = smooth(0.1f, 0.4f, q)
            if (row > 0.01f) drawMorph(pal, Kind.MESSAGE, box, q.coerceIn(0f, 1f), row)
        } else {
            drawMorph(pal, Kind.MESSAGE, box, q.coerceIn(0f, 1f), smooth(0f, 0.3f, q))
        }
    }
    drawMorph(pal, Kind.MUSIC, lerpB(PILL_SMALL, LIST_MEDIA, p.coerceIn(-0.03f, 1.03f)), p.coerceIn(0f, 1f), 1f)
}

// ---- the full-screen page

/** On a picture the islands and cards are frosted white, as the cover's card is (CoverDemo). */
private fun glassOf(pal: SkeuoPalette) = SkeuoPalette(
    frame = Color.White, pill = Color.White.copy(alpha = 0.3f), pillOn = Color.White.copy(alpha = 0.38f),
    line = Color.White.copy(alpha = 0.85f), accent = pal.accent, screen = Color(0xFF55607A), dark = pal.dark,
)

private fun DrawScope.drawImmersive(sc: Scene, pal: SkeuoPalette, measurer: TextMeasurer, clockSp: TextUnit) {
    val mix = sc.bgMix.value
    if (sc.bgFrom != sc.bgTo) drawPage(sc, pal, measurer, sc.bgFrom, 1f, 1f)
    drawPage(sc, pal, measurer, sc.bgTo, mix, mix)
    // The small clock the cover and every page hold, white on the picture.
    val clock = measurer.measure("09:41", TextStyle(color = Color.White, fontSize = clockSp, fontWeight = FontWeight.Bold))
    val k = 0.55f
    withTransform({ scale(k, k, Offset(PW / 2f, 14f)) }) {
        drawText(clock, topLeft = Offset(PW / 2f - clock.size.width / 2f, 14f))
    }
    drawRoundRect(Color.White.copy(alpha = 0.55f), Offset(PW / 2f - 9f, 14f + clock.size.height * k + 2f),
        Size(18f, 2f), CornerRadius(1f))
    val g = glassOf(pal)
    for (x in DISC_X) drawCircle(Color.White.copy(alpha = 0.3f), ISLAND_H / 2f, Offset(x, RY))
    val x = sc.ex.value
    val pill = pressedBy(PILL_SMALL, 0.05f * sc.pressPill.value)
    val small = pressedBy(SMALL, 0.1f * sc.pressSmall.value)
    val sq = squashOf(sc)
    when (sc.exStage) {
        0 -> {
            drawIsland(g, Kind.TIMER, small)
            drawIsland(g, Kind.NAV, pill)
            drawCardOf(g, Kind.MUSIC, pill, 1f)
        }
        1 -> {
            drawIsland(g, Kind.TIMER, small)
            drawSwap(g, Kind.NAV, Kind.MUSIC, pill, x, sq)
        }
        2 -> {
            drawIsland(g, Kind.MUSIC, pill)
            drawSwap(g, Kind.TIMER, Kind.NAV, small, x, sq)
        }
        else -> {
            drawIsland(g, Kind.NAV, small)
            drawSwap(g, Kind.MUSIC, Kind.TIMER, pill, x, sq)
        }
    }
}

/** One page behind the lock screen, `alpha` in; `grow` is how far a coming map has settled from 1.1. */
private fun DrawScope.drawPage(sc: Scene, pal: SkeuoPalette, measurer: TextMeasurer, page: Int, alpha: Float,
                               grow: Float) {
    if (alpha <= 0.003f) return
    when (page) {
        BG_COVER -> {
            drawArtBackdrop(0.35f, soft = 0f, alpha = alpha)
            drawRect(Color.Black.copy(alpha = 0.1f * alpha), Offset.Zero, Size(PW, PH))
        }
        BG_MAP -> {
            val k = lerp(1.1f, 1f, grow)
            withTransform({ scale(k, k, Offset(PW / 2f, PH / 2f)) }) { drawMap(pal, alpha) }
        }
        else -> drawCountdown(sc, pal, measurer, alpha)
    }
}

/** 高德's map, as wireframe: blocks, streets, the route in the finger's blue, where you are. */
private fun DrawScope.drawMap(pal: SkeuoPalette, alpha: Float) {
    val ground = if (pal.dark) Color(0xFF2A2E35) else Color(0xFFE9EDF3)
    val block = if (pal.dark) Color(0xFF343942) else Color(0xFFDCE1E9)
    val street = if (pal.dark) Color(0xFF454B55) else Color.White
    drawRect(ground, Offset.Zero, Size(PW, PH), alpha = alpha)
    for ((bx, by, bw, bh) in listOf(
        floatArrayOf(-4f, 10f, 30f, 44f), floatArrayOf(34f, 10f, 34f, 44f), floatArrayOf(76f, 10f, 30f, 44f),
        floatArrayOf(-4f, 64f, 30f, 50f), floatArrayOf(34f, 64f, 34f, 50f), floatArrayOf(76f, 64f, 30f, 50f),
        floatArrayOf(-4f, 124f, 30f, 40f), floatArrayOf(44f, 124f, 24f, 40f), floatArrayOf(76f, 124f, 30f, 40f),
        floatArrayOf(-4f, 174f, 30f, 50f), floatArrayOf(44f, 174f, 62f, 50f),
    ).map { listOf(it[0], it[1], it[2], it[3]) }) {
        drawRoundRect(block, Offset(bx, by), Size(bw, bh), CornerRadius(3f), alpha = alpha)
    }
    for (y in floatArrayOf(59f, 119f, 169f)) drawLine(street, Offset(-5f, y), Offset(PW + 5f, y), 5f, alpha = alpha)
    for (x in floatArrayOf(30f, 72f)) drawLine(street, Offset(x, -5f), Offset(x, PH + 5f), 5f, alpha = alpha)
    drawLine(street, Offset(38f, 119f), Offset(38f, 169f), 4f, alpha = alpha)
    // The route: up from where you are, right at the next corner, on up and off the top.
    val route = Path().apply {
        moveTo(38f, 186f); lineTo(38f, 119f); lineTo(72f, 119f); lineTo(72f, 59f); lineTo(86f, 40f); lineTo(86f, -10f)
    }
    drawPath(route, Color.White, alpha = 0.85f * alpha, style = Stroke(5.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(route, pal.accent, alpha = alpha, style = Stroke(3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawCircle(Color.White, 3.6f, Offset(38f, 176f), alpha = alpha)
    drawCircle(pal.accent, 2.6f, Offset(38f, 176f), alpha = alpha)
}

/**
 * The countdown's page as CountdownScene draws it: the wallpaper blurred, a disc of glass, a ring
 * of what remains in #277af7 running anticlockwise from twelve with a dot at its head, the time
 * left, the whole under it, and the 屏幕常亮 pill.
 */
private fun DrawScope.drawCountdown(sc: Scene, pal: SkeuoPalette, measurer: TextMeasurer, alpha: Float) {
    val top = if (pal.dark) Color(0xFF3A3F4A) else Color(0xFFA9B2C4)
    val bottom = if (pal.dark) Color(0xFF22252B) else Color(0xFFD2D7E1)
    drawRect(Brush.verticalGradient(listOf(top, bottom), 0f, PH), Offset.Zero, Size(PW, PH), alpha = alpha)
    val c = Offset(PW / 2f, 90f)
    drawCircle(Color.White.copy(alpha = 0.16f * alpha), 32f, c)
    val r = 27f
    drawCircle(Color.White.copy(alpha = 0.1f * alpha), r, c, style = Stroke(1.8f))
    val left = 0.62f - 0.08f * sc.tick.value
    val sweep = -360f * left
    drawArc(COUNTDOWN_BLUE.copy(alpha = alpha), -90f, sweep, false, Offset(c.x - r, c.y - r), Size(r * 2f, r * 2f),
        style = Stroke(1.8f, cap = StrokeCap.Round))
    val head = Math.toRadians((-90f + sweep).toDouble())
    drawCircle(COUNTDOWN_BLUE.copy(alpha = alpha), 1.5f, Offset(c.x + r * cos(head).toFloat(), c.y + r * sin(head).toFloat()))
    val secs = 299 - (sc.tick.value * 7f).toInt()
    val time = "%02d:%02d".format(secs / 60, secs % 60)
    val t = measurer.measure(time, TextStyle(color = Color.White.copy(alpha = 0.95f * alpha),
        fontSize = 9f.toSp(), fontWeight = FontWeight.Medium))
    drawText(t, topLeft = Offset(c.x - t.size.width / 2f, c.y - t.size.height / 2f - 3f))
    drawRoundRect(Color.White.copy(alpha = 0.5f * alpha), Offset(c.x - 6f, c.y + 6f), Size(12f, 1.6f), CornerRadius(0.8f))
    drawRoundRect(COUNTDOWN_BLUE.copy(alpha = alpha), Offset(c.x - 9f, c.y + 38f), Size(18f, 6f), CornerRadius(3f))
    drawRoundRect(Color.White.copy(alpha = 0.9f * alpha), Offset(c.x - 5f, c.y + 40.3f), Size(10f, 1.4f), CornerRadius(0.7f))
}

private val COUNTDOWN_BLUE = Color(0xFF277AF7)
