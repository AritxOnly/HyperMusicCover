package com.os4.musiccover

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject

/**
 * The focus notification for a bus or subway ride, posted as 高德 in 高德's process, made to
 * look like ColorOS 17's 公交/地铁 card: the line in its colour and where it is heading, the
 * milestone (下一站 / 当前站 / 准备换乘 / 已到达) large, the stations left under it, the three-stop
 * track with the transfer ⇄ and the next line's badge, and the landmark the shown station stands
 * near behind it all (AmapTransitScene's Frame, Track and Art - the lock-screen page's own).
 *
 * On HyperOS 高德 posts a focus notification only for walking and cycling (id 1236); a bus or
 * subway navigation has none of its own. 高德's other focus notification, XiaomiUAConnectedDevice
 * (bizType 20001, class jk7 in 17.00.0.2005), is the first protocol's small flat template and
 * uses id 1237, so the ride is posted as [ID] and 高德's own 1237 is held back while it is up
 * ([handle]) - two islands for one ride is one too many.
 *
 * A ride is posted in the plugin's own v2 template ([Style.SCENE], scene `template_v2`): the
 * `param_v2` envelope, the words in baseInfo, the line's colour and the stop's landmark behind
 * them in bgInfo, the leg's progress in multiProgressInfo, the line's badge in picInfo, and the
 * island's own row, ticker and AOD line in param_island - see [official]. Everything a surface
 * draws from - the island's expanded card, the notification centre's row, the lock screen's -
 * comes out of that one param, so they all say and look the same thing.
 *
 * [Style.CARD] (this module's own layout, miui.focus.rv), [Style.TEMPLATE] (the same fields under
 * param_v2 with no scene) and [Style.FLAT] (the old protocol 1 one-liner) are kept for the probe
 * only (`AMAPPROBE --es island card|template|flat`): none of them is posted by default.
 *
 * Tapping it on the lock screen opens the page (AmapTransitScene.servesKey); tapping it elsewhere
 * opens 高德 at the navigation. Only while the current leg is a bus or a subway: a walking leg is
 * 高德's own island again.
 */
internal object AmapTransitIsland {

    private const val TAG = "MCAmap: transit island: "
    /** Beside 高德's walking island, 1236, and clear of its XiaomiUAConnectedDevice's 1237. */
    private const val ID = 1239
    private const val AMAP_UA_ID = 1237
    private const val CHANNEL = "mc_transit"
    /** As long as SystemUI believes a silent trip (AmapTransitScene.STALE_MS). */
    private const val TIMEOUT_MS = 30L * 60_000L

    private const val PIC = "miui.focus.pic_mc_transit"
    private const val PIC_BG = "miui.focus.pic_mc_transit_bg"
    /**
     * The three pictures the progress bar is drawn with ([template]'s `progressInfo`): the vehicle
     * where the ride has got to, the stop ahead of it, and the destination. Their own keys, not
     * [PIC]: that one is the line's number and the island's left half wears it.
     */
    private const val PIC_VEHICLE = "miui.focus.pic_mc_vehicle"
    private const val PIC_PIN = "miui.focus.pic_mc_pin"
    private const val PIC_FLAG = "miui.focus.pic_mc_flag"

    /**
     * The car the progress bar's thumb is drawn with, after the design: a white shell going grey
     * towards its foot, dark glass, a dark tail, dark bogies.
     */
    private const val SHELL_TOP = 0xfffbfcfe.toInt()
    private const val SHELL_FOOT = 0xffd3d8df.toInt()
    private const val GLASS = 0xff22262d.toInt()
    private const val WHEEL = 0xff2a2d33.toInt()
    private const val HUB = 0xff6c717a.toInt()
    private const val LAMP = 0xffdfe9ff.toInt()
    /** The destination pin, grey - it is where the ride ends, not a line. */
    private const val DEST = 0xffa2a4a9.toInt()

    /**
     * The plugin's boxes for the bar's three pictures, in dp (focus_notification_module_progress):
     * the thumb is 60x47 and drawn `fitXY`, each pin 30x47, all three standing on the container's
     * foot, and the 12dp bar sits 4dp above that foot - 31dp to [BAR_FOOT] inside a box.
     * The pictures are drawn to exactly those boxes, so nothing is stretched and the bar's place
     * inside them is known.
     */
    private const val THUMB_W = 60f
    private const val SLOT_W = 30f
    private const val SLOT_H = 47f
    private const val BAR_FOOT = 43f
    /**
     * The metro and the pins are drawn in 设计图.jpg's own pixels, three to a dp (its 12dp bar is
     * 36px tall): they were traced from it there, and matched to it there. The thumb's and the
     * pins' boxes stand on the same foot, so all three share [SLOT_Y], the box's top in the
     * design; the thumb's box is placed so the car sits in its middle.
     */
    private const val DESIGN_PX = 3f
    private const val SLOT_Y = 138f
    private const val THUMB_X = 447.5f
    private const val UNDER = 0xff22282d.toInt()
    private const val GLOW = 0xffb4c6e6.toInt()
    private const val NOSE_WHITE = 0xffe8f0f2.toInt()

    /** The metro's parts, as SVG path data in the design's pixels (see [metro]). */
    private const val BODY = "M480.8 214.2 C480.9 211.6 482.6 210.1 485 210 L566 210 C573 210.2 579 211 584 212.8 " +
        "C590 215 595 219.5 598.8 225 C601.5 229 603.6 233 604.8 238 L605 246 C604.9 249.5 603.5 252.5 601.8 254.5 " +
        "C600 256.5 598.5 257.5 596 257.5 L485 257.5 C482.6 257.4 480.9 256 480.8 253.5 Z"
    private const val TAIL = "M482.5 210.5 L478.5 210.5 C474 210.8 470.6 215 470.2 222 L470 249 " +
        "C470.2 254.5 473 257.5 477.5 257.5 L482.5 257.5 Z"
    private const val SKIRT = "M576 256.5 L598.5 256.5 C600.6 256.6 601.2 258.2 599.8 259.4 C597 260.8 590 260.9 584 260.5 " +
        "C580 260.2 577 259 576 256.5 Z"
    private const val TAIL_RIM = "M482 211.6 C475.5 211.6 471.8 215.5 471.4 223 L471.3 234"
    private const val LOWER = "M480 246.8 L566 246.8 C568 246.8 570 247.2 572 248.2 C576 250.4 580 253.6 583 255.4 " +
        "C585 256.5 587 257.4 590 257.6 L606 257.6 L606 260 L480 260 Z"
    private const val STRIPE = "M480 244.4 L566 244.4 C570 244.6 574 246 577 248 C580 250 583 252.3 586 253.2 " +
        "C590 253.6 597 253.4 606 253.4 L606 257.6 L590 257.6 C587 257.4 585 256.5 583 255.4 " +
        "C580 253.6 576 250.4 572 248.2 C570 247.2 568 246.8 566 246.8 L480 246.8 Z"
    private const val BAND = "M583 205 L610 205 L610 222 L602.2 231 L600.6 240 C600 239.2 599.6 238.4 599 237.8 " +
        "C596.5 234.5 593.5 230.5 590.5 227.8 C588.6 226.2 587.3 225 586.2 224 L585.5 212.6 Z"
    private const val BAND_EDGE = "M600.6 240 C600 239.2 599.6 238.4 599 237.8 C596.5 234.5 593.5 230.5 590.5 227.8"
    private const val NOSE_LIT = "M585.5 212.6 L586.2 224 C587.3 225 588.6 226.2 590.5 227.8 C593.5 230.5 596.5 234.5 599 237.8 " +
        "C599.6 238.4 600 239.2 600.6 240 L602.2 231 L610 222 L610 254 L600 253.2 C594 250.8 590.6 248.6 590.6 246 " +
        "C590.6 244.2 591.8 242.8 593.4 242 L584 232 L583 213 Z"
    private const val UNDER_CAB = "M573 240.2 C577 241 581 242 585 242.8 C588 243.3 591 243.4 594 243.2"
    private const val NOSE_RIM = "M584 212.6 C590 215 595 219.5 598.8 225 C601.5 229 603.6 233 604.8 238"
    private const val CAB = "M576 228.1 C578.5 228.1 581.5 229.2 584 230.6 C587 232 590 234.8 592 238.6 " +
        "C592.6 239.8 593.1 241 593.4 242 C590 242 587.5 241.9 585.5 241.5 C582 240.8 578.5 240.1 576.5 239.4 " +
        "C575.2 238.9 574.6 237.8 574.6 236.5 L574.6 230 C574.6 228.9 575.2 228.1 576 228.1 Z"

    /** The card's height, as the layout has it. */
    private const val CARD_DP = 176f
    /**
     * The card's corner radius, the system's own for a notification's card: the plugin's
     * focus_notify_bg_img_bg is a rectangle of notification_item_bg_radius, and its template clips
     * to it (`outlineProvider="background"` + `clipToOutline="true"`). A card that draws its own
     * pixels has to cut them itself, or the four corners come out square.
     */
    private const val CORNER_DP = 24f
    /** The ground is soft; two thirds of the card's pixels are plenty and a third the memory. */
    private const val GROUND_SCALE = 0.67f
    private const val BOTTOM = 0xff07080b.toInt()
    /** Where the landmark's own centre sits across the card: right of centre, clear of the words. */
    private const val LANDMARK_AT = 0.72f
    /**
     * How much the picture is grown past a bare cover. Covering the card and standing right of
     * centre at once costs size - the picture is 807x378 with the landmark in the middle, so the
     * further right it is put the wider it must be - and this is how much of that is spent.
     */
    private const val LANDMARK_ZOOM = 1.25f
    /**
     * Where the picture sits vertically, as a share of the height the growth bought: 0 spends all
     * of it lifting the landmark, 1 spends none. The landmark pictures are taken with the subject
     * low in the frame, so with the picture only just grown it comes out under the words' last
     * line - and lifting it is exactly what the growth is for, which is why [LANDMARK_ZOOM] buys
     * more than a bare cover.
     */
    private const val LANDMARK_DOWN = 0f
    /**
     * The narrowest fill the bar draws as a piece of itself rather than as a standing mark.
     *
     * The fill's ends are capped at half its height, so once it is narrower than it is tall the
     * two caps meet and what is left is a square head, not a rounded one - 高德's own 2% for a
     * ride just begun does exactly that. The bar is about 880x42 px on this phone, so the fill is
     * as wide as it is tall - the width at which the cap is exactly a half-circle - at 4.8% of it,
     * which as a whole percent is 5.
     */
    private const val PROGRESS_MIN = 5

    /**
     * The milestones the leg's own progress belongs to: 下一站 (3), 下一站即终点 (4), 到达普通站
     * (5) and 到达换乘站 (6) - the ones where the ride is between stations.
     *
     * ColorOS draws a station overview for exactly these and no others: its card builder puts
     * `cardStationOverview` in `K()` (3/4), `a()` (5) and `d()` (6) only. At 到达起始站附近 (1) and
     * 候车 (2) it puts `cardWaitingInformation` - the list of trains coming, line, direction and
     * arrival - in that place instead, and at 到站 (7) it puts the landmark; neither has a bar.
     *
     * Which is also why the bar has to be held back here: before the ride starts the only card 高德
     * has sent is the walk to the station, and its `location.persent` is how far along the WALK is
     * (0.5, halfway there), so a bar drawn from it said the 4号线 was half ridden while the walker
     * was still on the street.
     */
    private val PROGRESS_AT = setOf("3", "4", "5", "6")

    enum class Style { CARD, TEMPLATE, FLAT, SCENE }

    /**
     * The official scenes the focus plugin knows (its own strings), for trying a ride in each:
     * `AMAPPROBE --es island templateBaseScene` and the rest of [SCENES]. [Style.TEMPLATE] sends
     * the same fields with no scene at all, which is what a build that will not take one falls
     * back to.
     */
    /**
     * The one scene a ride is posted in: `template_v2`, which is what the plugin's own sample
     * carries ({"param_v2": {..., "scene": "template_v2"}}). The plugin's other scene names -
     * templateBaseScene, templateBaseProgressScene, templateRevertScene,
     * templateRevertProgressScene, templateRevertOversizeScene - belong to its V3 factory
     * (param_v3), which a param_v2 never reaches: posting them one by one changed nothing, which
     * is how they were tried.
     */
    val SCENES = listOf("template_v2")

    @Volatile var style = Style.SCENE
        private set
    /** The scene [Style.SCENE] posts under; the only one a param_v2 reads. */
    @Volatile var scene: String? = "template_v2"
        private set

    @Volatile private var posted = false
    @Volatile private var lastStatus: String? = null
    @Volatile private var lastKey: String? = null
    /** The entity last shown, for a repost when the landmark arrives or the style changes. */
    @Volatile private var lastEntity: String? = null
    @Volatile private var lastError: String? = null
    @Volatile private var art: AmapTransitScene.Art.Set? = null
    @Volatile private var artPick: AmapTransitScene.Art.Pick? = null
    @Volatile private var held = 0
    @Volatile private var lastHeld: String? = null

    /**
     * 高德's own 1237 (XiaomiUAConnectedDevice) is not posted while the ride's card is up: it
     * would be a second, smaller island for the same ride. What it said is kept for the probe.
     */
    fun handle() {
        try {
            Xp.hookAll(NotificationManager::class.java, "notify") { chain ->
                val a = chain.args
                val id = a.firstOrNull { it is Int } as Int?
                val n = a.lastOrNull() as? Notification
                if (!posted || id != AMAP_UA_ID || n == null) return@hookAll chain.proceed()
                held++
                lastHeld = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString() + " | " +
                    n.extras.getCharSequence(Notification.EXTRA_TEXT)
                if (held == 1) Xp.log(TAG + "holding back 高德's own $AMAP_UA_ID: $lastHeld")
                null
            }
        } catch (t: Throwable) {
            Xp.log(TAG + "notify hook failed: $t")
        }
    }

    /** The ride's island for [entity], or none for null or a leg that is not a ride. */
    fun update(ctx: Context, entity: String?) {
        try {
            val trip = entity?.let { AmapTransitScene.Trip.parse(JSONObject(it)) }
            if (trip == null || !trip.leg.rides()) {
                cancel(ctx)
                return
            }
            lastEntity = entity
            post(ctx, trip, JSONObject(entity).optString("deepLink"))
            lastError = null
        } catch (t: Throwable) {
            lastError = t.toString()
            Xp.log(TAG + "not posted: $t")
        }
    }

    fun cancel(ctx: Context) {
        lastEntity = null
        if (!posted) return
        posted = false
        lastStatus = null
        lastKey = null
        ctx.getSystemService(NotificationManager::class.java)?.cancel(ID)
        Xp.log(TAG + "taken down")
    }

    /**
     * `AMAPPROBE --es island card|template|flat`, or one of the plugin's own scenes by name:
     * the style, and the ride again in it.
     */
    fun setStyle(ctx: Context?, name: String): String {
        // SCENE is chosen by a scene's own name below, never by the word "scene": matching that
        // here would set the style and clear the scene it is the whole point of.
        val s = Style.values().firstOrNull {
            it != Style.SCENE && it.name.equals(name, ignoreCase = true)
        }
        if (s != null) {
            style = s
            scene = null
        } else if (SCENES.any { it.equals(name, ignoreCase = true) }) {
            style = Style.SCENE
            scene = SCENES.first { it.equals(name, ignoreCase = true) }
        } else {
            return "unknown style $name (card, template, flat, ${SCENES.joinToString(", ")})"
        }
        Xp.log(TAG + "style " + style.name.lowercase() + (scene?.let { " $it" } ?: ""))
        val e = lastEntity
        if (ctx != null && e != null) {
            // A different layout under the same id: take the old one down so it is drawn anew.
            ctx.getSystemService(NotificationManager::class.java)?.cancel(ID)
            lastStatus = null
            update(ctx, e)
        }
        return describe()
    }

    /**
     * The ride in one of the plugin's own scenes ([SCENES]): the fields a template scene is read
     * for - the words, the line's own progress, its badge and the ground - carried at the top of
     * `miui.focus.param` under the `scene` name the plugin acts on, rather than [Style.TEMPLATE]'s
     * `param_v2` with no scene at all. Which of the scenes takes which fields is what posting
     * them one by one answers.
     */
    private fun official(trip: AmapTransitScene.Trip, f: AmapTransitScene.Frame, title: String,
                         milestone: Boolean, float: Boolean): JSONObject {
        return template(trip, f, title, milestone, float)
            .put("scene", scene.orEmpty())
            .put("title", title)
            .put("content", listOf(f.line, f.direction).filter { it.isNotEmpty() }.joinToString(" "))
            .put("subContent", f.secondary)
            .put("colorTitle", "#FFFFFF")
            .put("colorContent", "#FFFFFF")
            .put("colorBg", "#000000")
            .put("showSmallIcon", false)
            .put("padding", true)
        // The progress is [template]'s `multiProgressInfo`, which the plugin's own strings
        // describe the rules of ("progress > 100, reduced to 100", "points > 4, limited to 4").
        // A hand-made `progressInfo` beside it is not that node: the plugin logs "progressInfo
        // param error" for it, and a param it cannot read is a param it falls back from.
    }

    fun describe(): String {
        val sb = StringBuilder("island: style=")
            .append(if (style == Style.SCENE) "scene " + scene else style.name.lowercase())
            .append(" posted=").append(posted)
            .append(" art=").append(if (art != null) "yes" else artPick?.toString() ?: "-")
        if (held > 0) sb.append(" held1237=").append(held).append(" (").append(lastHeld).append(')')
        lastError?.let { sb.append(" error=").append(it) }
        return sb.toString()
    }

    private fun post(ctx: Context, trip: AmapTransitScene.Trip, deepLink: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "公交/地铁导航",
                NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            })
        }
        val f = AmapTransitScene.Frame.of(trip)
        fetchArt(ctx, f)
        val title = f.primary.ifEmpty { f.line }
        val content = listOf(f.line, f.secondary).filter { it.isNotEmpty() }.joinToString(" · ")
        // A new milestone floats once, the way 高德's walking island floats at a turn; the same
        // one reposted (the keepalive, the landmark arriving) does not.
        val milestone = trip.status != lastStatus
        lastStatus = trip.status
        val float = milestone && trip.status in FLOAT_AT
        val badge = Icon.createWithBitmap(badge(f.line, f.lineBg, f.lineText))
        val pics = Bundle().apply {
            putParcelable(PIC, badge)
            putParcelable("miui.focus.pic_large", badge)
            // The progress bar's own three pictures, by the names its `progressInfo` asks for.
            putParcelable(PIC_VEHICLE, Icon.createWithBitmap(vehicle(f.subway, f.lineBg)))
            putParcelable(PIC_PIN, Icon.createWithBitmap(pin(f.lineBg)))
            putParcelable(PIC_FLAG, Icon.createWithBitmap(flag()))
        }
        val extras = Bundle()
        when (style) {
            Style.CARD -> {
                extras.putParcelable("miui.focus.rv", card(ctx, f))
                extras.putString("miui.focus.param.custom",
                    shared(JSONObject(), title, f, milestone, float).toString())
            }
            Style.TEMPLATE -> {
                val bg = ground(ctx, f, 0.5f)
                pics.putParcelable(PIC_BG, Icon.createWithBitmap(bg))
                extras.putString("miui.focus.param",
                    JSONObject().put("param_v2", template(trip, f, title, milestone, float)).toString())
            }
            Style.SCENE -> {
                val bg = ground(ctx, f, 0.5f)
                pics.putParcelable(PIC_BG, Icon.createWithBitmap(bg))
                // `param_v2` is the envelope and `scene` inside it picks the template, which is
                // how the plugin's own sample carries it: {"param_v2": {..., "scene": "template_v2"}}.
                // Without the envelope the param is not read at all and the plugin falls back.
                extras.putString("miui.focus.param",
                    JSONObject().put("param_v2",
                        official(trip, f, title, milestone, float)).toString())
            }
            Style.FLAT -> extras.putString("miui.focus.param",
                shared(JSONObject(), title, f, milestone, float)
                    .put("protocol", 1)
                    .put("scene", "templateRevertProgressScene")
                    .put("title", title)
                    .put("content", content)
                    .put("colorTitle", "#FFFFFF")
                    .put("colorContent", "#FFFFFF")
                    .put("colorBg", "#000000")
                    .put("showSmallIcon", false)
                    .put("padding", true)
                    .toString())
        }
        extras.putBundle("miui.focus.pics", pics)
        val open = Intent(Intent.ACTION_VIEW,
            Uri.parse(deepLink.ifEmpty { "amapuri://amap?clearStack=0&keepStack=1" }))
            .setPackage(ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val small = ctx.resources.getIdentifier("notification_amap", "drawable", ctx.packageName)
            .takeIf { it != 0 } ?: ctx.applicationInfo.icon
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(small)
            .setContentTitle(title)
            .setContentText(content)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setOngoing(true)
            // A new milestone may alert again, so HyperOS re-floats it (arriving, transfer); the
            // keepalive repost of the same state stays quiet.
            .setOnlyAlertOnce(!milestone)
            .setShowWhen(false)
            .setTimeoutAfter(TIMEOUT_MS)
            .setContentIntent(PendingIntent.getActivity(ctx, ID, open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addExtras(extras)
            .build()
        nm.notify(ID, n)
        posted = true
        val key = "$style|$title|$content"
        if (key != lastKey) Xp.log(TAG + "${style.name.lowercase()}: $title | $content")
        lastKey = key
    }

    /** Floats on: the stop before yours, a transfer, arriving. */
    private val FLOAT_AT = setOf("4", "6", "7")

    /**
     * What every style says the same way: the status bar's and the AOD's line, floating, and
     * the super island - the line's badge, the milestone beside it, the stations left after.
     */
    private fun shared(o: JSONObject, title: String, f: AmapTransitScene.Frame,
                       milestone: Boolean, float: Boolean): JSONObject {
        val island = JSONObject()
            .put("islandProperty", 1)
            .put("bigIslandArea", JSONObject()
                .put("imageTextInfoLeft", JSONObject()
                    .put("type", 1)
                    .put("picInfo", JSONObject().put("type", 1).put("pic", PIC))
                    .put("textInfo", JSONObject().put("title",
                        f.islandLeft.ifEmpty { title })))
                .apply {
                    // The island's other half is the milestone's own word's opposite number: the
                    // station, or the line a transfer changes to - not the card's guide line.
                    val right = f.islandRight.ifEmpty { f.secondary }
                    if (right.isNotEmpty()) put("imageTextInfoRight", JSONObject()
                        .put("type", 2)
                        .put("textInfo", JSONObject().put("title", right)))
                })
            .put("smallIslandArea", JSONObject()
                .put("picInfo", JSONObject().put("type", 1).put("pic", PIC)))
        return o.put("ticker", title)
            .put("tickerPic", PIC)
            .put("aodTitle", title)
            .put("aodPic", PIC)
            .put("enableFloat", float)
            .put("reopen", if (milestone) "reopen" else "close")
            .put("updatable", true)
            .put("param_island", island)
    }

    /**
     * The system's large template (param_v2), for a HyperOS that will not take 高德's own
     * layout: the milestone over the line and the stations left (baseInfo), the leg's progress
     * in the line's colour with a node per station still to come (multiProgressInfo), the
     * line's badge (picInfo) and the ground (bgInfo).
     */
    private fun template(trip: AmapTransitScene.Trip, f: AmapTransitScene.Frame, title: String,
                         milestone: Boolean, float: Boolean): JSONObject {
        val o = shared(JSONObject(), title, f, milestone, float)
            .put("protocol", 1)
            .put("baseInfo", JSONObject()
                .put("type", 2)
                .put("title", title)
                .put("content", listOf(f.line, f.direction).filter { it.isNotEmpty() }.joinToString(" "))
                .put("subContent", f.secondary)
                .put("colorTitle", "#FFFFFF")
                .put("colorContent", "#CCFFFFFF")
                .put("colorSubContent", "#B3FFFFFF"))
            .put("picInfo", JSONObject().put("type", 1).put("pic", PIC))
            .put("bgInfo", JSONObject().put("type", 1).put("picBg", PIC_BG)
                .put("colorBg", hex(AmapTransitScene.blend(f.lineBg, BOTTOM, 0.55f))))
        // How long the leg is: its station list when 高德 gave one, and otherwise the stops still
        // to come, which is all a ride built out of 高德's cards knows. A ride always has an empty
        // `via` - the module's own entity cannot fill it - so without the second the guard below
        // never opened and the card never had a bar at all.
        val leg = trip.leg
        val total = if (leg.via.size > 0) leg.via.size + 1 else leg.remain + 1
        if (f.nodes != null && total > 1 && f.status in PROGRESS_AT) {
            // 高德's own reading of where the ride has got to when the card carried one -
            // `location.persent`, a real ride's 0.02 / 0.44 / 0.71 down its leg - and only the
            // shape of the stops left when it did not. Counting stops instead is not the same
            // thing: a leg with three of four stops left is not a quarter of the way along it.
            val done = (total - leg.remain).coerceIn(0, total)
            val said = if (trip.legPercent in 0.0..1.0) (trip.legPercent * 100).toInt()
                else done * 100 / total
            // The fill has a floor: narrower than the bar is tall it stops being a piece of the
            // bar and draws as a mark standing up at the left end, which is what a ride that has
            // just begun looks like - 高德's own `persent` for boarding is 0.02. A ride that is
            // genuinely nowhere along its leg still shows nothing.
            val percent = if (said in 1 until PROGRESS_MIN) PROGRESS_MIN else said
            // `progressInfo`, not `multiProgressInfo`. The plugin takes the two for the same slot
            // of the card and asks about this one FIRST (`TemplateFactoryV3`: multiProgressInfo
            // before progressInfo), and they are not the same bar: `multiProgressInfo` is a row of
            // segments and dots, while `progressInfo` is the one drawn like the design - a single
            // bar filled to the ride's own place in the line's colour, a picture riding that edge,
            // a pin where it is going and a flag at the end. Its own three pictures are what
            // `picForward` / `picMiddle` / `picEnd` name, and they are sent in `miui.focus.pics`
            // with the rest.
            //
            // No title either way. The words are already on `baseInfo.subContent` above the bar,
            // and ColorOS's own station overview carries none at all - its `cardStationOverview`
            // is a list of stops, `isCurStation`, `isTwoStation` and `curIndex`, with not one
            // string in it. Sending the same sentence twice made the card read it twice over.
            o.put("progressInfo", JSONObject()
                .put("progress", percent.coerceIn(0, 100))
                .put("colorProgress", hex(f.lineBg))
                // Deepening towards the car, as the design's fill does.
                .put("colorProgressEnd", hex(AmapTransitScene.blend(f.lineBg, 0xff000000.toInt(), 0.25f)))
                .put("picForward", PIC_VEHICLE)
                .put("picMiddle", PIC_PIN)
                .put("picEnd", PIC_FLAG))
        }
        return o
    }

    // ------------------------------------------------------------------ the card

    /**
     * The card's views: the ground (the line's colour, the landmark), the line's pill, where it
     * is heading, the milestone and what is left, the track. Pixels for this screen: the
     * notification is drawn on the phone that posts it.
     */
    private fun card(ctx: Context, f: AmapTransitScene.Frame): RemoteViews {
        val dp = ctx.resources.displayMetrics.density
        val rv = RemoteViews(BuildConfig.APPLICATION_ID, R.layout.mc_transit_card)
        rv.setImageViewBitmap(R.id.mc_transit_ground, ground(ctx, f, GROUND_SCALE))
        if (f.line.isNotEmpty()) {
            rv.setImageViewBitmap(R.id.mc_transit_line, pill(f.line, f.lineBg, f.lineText, dp))
        } else {
            rv.setViewVisibility(R.id.mc_transit_line, View.GONE)
        }
        rv.setTextViewText(R.id.mc_transit_direction, f.direction)
        rv.setTextViewText(R.id.mc_transit_primary, f.primary.ifEmpty { f.line })
        rv.setTextViewText(R.id.mc_transit_secondary, f.secondary)
        rv.setViewVisibility(R.id.mc_transit_secondary, if (f.secondary.isEmpty()) View.GONE else View.VISIBLE)
        if (f.nodes != null) {
            rv.setImageViewBitmap(R.id.mc_transit_track, track(f, contentWidth(ctx), dp))
            rv.setViewVisibility(R.id.mc_transit_track, View.VISIBLE)
        } else {
            rv.setViewVisibility(R.id.mc_transit_track, View.GONE)
        }
        return rv
    }

    /** The card as wide as a notification row: the screen less the shade's margins. */
    private fun cardWidth(ctx: Context): Int {
        val m = ctx.resources.displayMetrics
        return (minOf(m.widthPixels, m.heightPixels) - 2 * 14f * m.density).toInt().coerceAtLeast(1)
    }

    /** Inside the card's padding (the layout's 18dp each side). */
    private fun contentWidth(ctx: Context) =
        (cardWidth(ctx) - 36f * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    /**
     * The ground, as the page has it in little: the line's colour into near-black, and the
     * landmark on the right, fading into the colour under the words and darkening under the
     * track. A default picture rather than a landmark stays dim, as on the page. Everything is
     * cut to the card's corners, the way the system's own card is.
     */
    private fun ground(ctx: Context, f: AmapTransitScene.Frame, scale: Float): Bitmap {
        val dp = ctx.resources.displayMetrics.density
        val w = (cardWidth(ctx) * scale).toInt().coerceAtLeast(1)
        val h = (CARD_DP * dp * scale).toInt().coerceAtLeast(1)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.clipPath(corners(w, h, CORNER_DP * dp * scale))
        val p = Paint(Paint.DITHER_FLAG)
        val top = AmapTransitScene.blend(f.lineBg, BOTTOM, 0.42f)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(top, AmapTransitScene.blend(f.lineBg, BOTTOM, 0.72f), BOTTOM),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        val set = art
        val still = set?.still
        if (still != null && set.pick == artPick) {
            // Placed by the landmark inside the picture rather than by the picture's own edge.
            // Pinned to its right edge, a picture of a building of this aspect lands centred,
            // roof through the milestone and the stations; grown just past a cover, so no edge
            // of it shows, what it is a picture *of* can be put where there is room instead -
            // right of centre, and lifted by the height the growth bought.
            val cover = maxOf(w / still.width.toFloat(), h / still.height.toFloat()) * LANDMARK_ZOOM
            val lw = still.width * cover
            val lh = still.height * cover
            val left = w * LANDMARK_AT - lw / 2f
            val topY = h - lh + (lh - h) * LANDMARK_DOWN
            val r = RectF(left, topY, left + lw, topY + lh)
            val ip = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            ip.alpha = if (set.pick.fallback) 110 else 235
            c.drawBitmap(still, null, r, ip)
            // Into the colour on the left, under the words - held at full strength until the
            // picture's own left edge and fading out from there, so that edge is not a seam
            // across the card. The picture no longer reaches the card's left side (it is placed
            // by the landmark in it, not by its own edge), which is what leaves the seam to hide.
            val fade = Paint(Paint.DITHER_FLAG)
            val seam = maxOf(0f, r.left)
            val fadeEnd = seam + w * 0.45f
            fade.shader = LinearGradient(0f, 0f, fadeEnd, 0f,
                intArrayOf(top, top, top and 0x00ffffff),
                floatArrayOf(0f, (seam / fadeEnd).coerceIn(0f, 1f), 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fade)
            // And darker at the foot, under the station names.
            fade.shader = LinearGradient(0f, h * 0.45f, 0f, h.toFloat(),
                0x00000000, 0x99000000.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fade)
        }
        return b
    }

    /** The three stops, the page's own Track, the badge's room only when there is a badge. */
    private fun track(f: AmapTransitScene.Frame, w: Int, dp: Float): Bitmap {        val badged = f.nodes.badge.any { it != null }
        val top = (if (badged) AmapTransitScene.Track.TOP_DP else 12f) * dp
        val h = (top + AmapTransitScene.Track.BOTTOM_DP * dp).toInt()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        AmapTransitScene.Track(dp).draw(Canvas(b), f, w * 0.12f, w * 0.88f, top)
        return b
    }

    /**
     * A rounded rectangle's path, for cutting a bitmap to the card's corners. `clipPath` is
     * antialiased while `clipRect` is not, and this is a soft gradient against a light shade.
     */
    private fun corners(w: Int, h: Int, r: Float): Path {
        val radius = r.coerceAtMost(minOf(w, h) / 2f)
        val p = Path()
        p.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, Path.Direction.CW)
        return p
    }

    /** The line's name in a pill of its colour: 「地铁1号线」 on red. */
    private fun pill(line: String, bg: Int, fg: Int, dp: Float): Bitmap {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        p.textSize = 13f * dp
        val fm = p.fontMetrics
        val h = (fm.bottom - fm.top) + 6f * dp
        val w = p.measureText(line) + 16f * dp
        val b = Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        p.color = bg
        c.drawRoundRect(RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()), h / 2f, h / 2f, p)
        p.color = if (Color.alpha(fg) == 0) Color.WHITE else fg
        c.drawText(line, 8f * dp, b.height / 2f - (fm.ascent + fm.descent) / 2f, p)
        return b
    }

    /**
     * The landmark for the frame, fetched in 高德's process the way the page fetches it in
     * SystemUI's (AmapTransitScene.Art: OPPO's pictures, kept in 高德's cache); the card is
     * posted again, quietly, once it is here.
     */
    private fun fetchArt(ctx: Context, f: AmapTransitScene.Frame) {
        if (f.art == artPick) return
        artPick = f.art
        art = null
        AmapTransitScene.Art.request(ctx, f.art) { set ->
            if (set.pick != artPick) return@request
            art = set
            val e = lastEntity ?: return@request
            if (style != Style.FLAT) update(ctx, e)
        }
    }

    private fun hex(c: Int) = String.format("#%06X", c and 0xffffff)

    /**
     * The vehicle the leg is ridden on, side on: a metro car for a subway, a bus for anything else.
     *
     * Side on because of where it is drawn - the progress bar's thumb, which the plugin keeps at
     * the fill's own edge (`ModuleProgressViewHolder.setProgressThumb` puts it at
     * `progress * width / 100`, centred on the point), so the picture is a car seen from the
     * platform and the bar is the track it is running along.
     *
     * The box is the plugin's own thumb box, [THUMB_W] x [SLOT_H], because the plugin draws it
     * `fitXY`: a picture of any other shape is stretched to that one.
     */
    private fun vehicle(subway: Boolean, bg: Int): Bitmap {
        val d = Resources.getSystem().displayMetrics.density
        val b = Bitmap.createBitmap(Math.round(THUMB_W * d), Math.round(SLOT_H * d),
            Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (subway) {
            c.scale(d / DESIGN_PX, d / DESIGN_PX)
            c.translate(-THUMB_X, -SLOT_Y)
            metro(c, bg)
        } else {
            // Drawn on a 100 x 40 grid, the wheels' foot at 40.
            val u = 18.5f / 40f
            c.scale(d, d)
            c.translate((THUMB_W - 100f * u) / 2f, BAR_FOOT - 0.4f - 40f * u)
            c.scale(u, u)
            bus(c, bg)
        }
        return b
    }

    /**
     * The design's metro car (设计图.jpg), traced from it in its own pixels and drawn with its
     * depth: a rounded dark tail lit along its rim, a white shell going blue-grey where its flank
     * turns under, windows sunk in a soft glow, a wrap-round cockpit with its lit edge, the line's
     * stripe sweeping down the nose, wheels lit along their lower rims, and its shadow on the bar.
     * The dark under the car also hides the fill running on beneath it, as the design shows.
     */
    private fun metro(c: Canvas, line: Int) {
        val core = AmapTransitScene.blend(line, 0xff6a5a40.toInt(), 0.38f)
        val noseCore = AmapTransitScene.blend(line, 0xff8090a8.toInt(), 0.5f)
        val halo = AmapTransitScene.blend(line, 0xffe0e6ee.toInt(), 0.55f)

        c.drawRoundRect(RectF(464f, 255f, 600f, 267f), 4f, 4f, paint(alpha(Color.BLACK, 0.5f), blur = 2.5f))
        c.drawRoundRect(RectF(478f, 261f, 600f, 267.5f), 2f, 2f, paint(shader = LinearGradient(
            478f, 0f, 500f, 0f, alpha(UNDER, 0f), UNDER, Shader.TileMode.CLAMP), blur = 0.8f))
        c.drawRoundRect(RectF(484f, 256.5f, 598f, 263.5f), 2f, 2f, paint(0xff1d2026.toInt()))
        c.drawRect(484f, 258.6f, 598f, 259.6f, paint(alpha(0xff3f4a5a.toInt(), 0.8f)))
        for ((cx, lit) in listOf(490.5f to 0.6f, 497.5f to 0.35f, 562.5f to 0.6f, 572f to 0.35f)) {
            c.drawCircle(cx, 262f, 3f, paint(WHEEL))
            c.drawArc(RectF(cx - 2.4f, 259.9f, cx + 2.4f, 264.7f), 0f, 180f, false,
                paint(alpha(0xff9a9ea4.toInt(), lit), stroke = 0.9f))
        }

        // The nose's chin, the line's colour gone dark, coming down below the flank's foot.
        c.drawPath(svg(SKIRT), paint(shader = vg(257f to AmapTransitScene.blend(line, 0xff2a3040.toInt(), 0.6f),
            260.5f to AmapTransitScene.blend(line, 0xff2a3040.toInt(), 0.78f)), blur = 0.6f))

        val tail = svg(TAIL)
        // The tail's end face, turned away from us: lit down to the roof's fold, where the roof
        // comes round into it, dark below.
        c.drawPath(tail, paint(shader = vg(210.5f to 0xff5a5a5a.toInt(), 212f to 0xff8c8c8c.toInt(),
            215f to 0xff7a7a7a.toInt(), 219f to 0xff6e6e6e.toInt(), 222.6f to 0xff646466.toInt(),
            223.6f to 0xff404244.toInt(), 240f to 0xff34363a.toInt(), 254f to 0xff2a2a30.toInt(),
            257.5f to 0xff1f2230.toInt())))
        c.save()
        c.clipPath(tail)
        c.drawOval(RectF(473f, 209.5f, 483f, 216.5f), paint(alpha(Color.WHITE, 0.18f), blur = 1.2f))
        c.drawPath(svg(TAIL_RIM), paint(shader = vg(211f to alpha(0xff9a9a9a.toInt(), 0.4f),
            222f to alpha(0xff808080.toInt(), 0.25f), 234f to alpha(0xff606060.toInt(), 0f)),
            blur = 0.8f, stroke = 3.6f))
        c.restore()

        val body = svg(BODY)
        // The roof and the flank are two faces: the roof brightening towards the fold at 223.6,
        // the flank dropping away below it, greyer and cooler.
        c.drawPath(body, paint(shader = vg(210f to 0xffc6c6c6.toInt(), 211f to 0xfffbfbfb.toInt(),
            212f to 0xffe6e6e6.toInt(), 214f to 0xffebebeb.toInt(), 217f to 0xffeeeeee.toInt(),
            219f to 0xfff3f3f3.toInt(), 222.6f to 0xfff5f5f5.toInt(), 223.6f to 0xffeaeaeb.toInt(),
            224.6f to 0xffe1e2e5.toInt(), 226f to 0xffd8dce4.toInt(), 229f to 0xffd0d6e0.toInt(), 234f to 0xffc6ccd8.toInt(), 240f to 0xffbcc2cf.toInt(),
            243f to 0xffbcc6dc.toInt(), 258f to 0xffb8c2d8.toInt())))
        c.save()
        c.clipPath(body)
        // Its end turning away from us, greying along the edge where it meets the tail.
        c.drawRect(480f, 208f, 488f, 260f, paint(shader = LinearGradient(480.5f, 0f, 487f, 0f,
            alpha(0xff9aa0aa.toInt(), 0.75f), alpha(0xff9aa0aa.toInt(), 0f), Shader.TileMode.CLAMP)))
        c.drawPath(svg(LOWER), paint(shader = vg(246.8f to 0xffa9c0dd.toInt(), 249f to 0xffa7b8cb.toInt(),
            252f to 0xffa2b5c6.toInt(), 254f to 0xff97acc6.toInt(), 256f to 0xff8aa2c2.toInt(),
            257f to 0xff4a6a98.toInt(), 257.6f to 0xff3f6498.toInt())))
        val stripe = svg(STRIPE)
        c.drawPath(stripe, paint(alpha(halo, 0.75f), stroke = 2.2f))
        c.drawPath(stripe, paint(shader = LinearGradient(580f, 0f, 592f, 0f, core, noseCore,
            Shader.TileMode.CLAMP)))
        val glass = vg(228.1f to 0xff6f81a5.toInt(), 229f to 0xff1f2e4b.toInt(), 230f to 0xff141d2e.toInt(),
            231.2f to 0xff1b2121.toInt(), 239.5f to 0xff222421.toInt(), 240.8f to 0xff16191a.toInt(),
            241.5f to 0xff3d444c.toInt())
        for ((x, w) in listOf(488.4f to 28.8f, 522.6f to 27.5f)) {
            val box = RectF(x, 228.1f, x + w, 241.5f)
            c.drawRoundRect(box, 1.6f, 1.6f, paint(alpha(GLOW, 0.75f), blur = 0.8f, stroke = 3.2f))
            c.drawRoundRect(box, 1.6f, 1.6f, paint(shader = glass))
        }
        for (x in floatArrayOf(556.8f, 566.6f)) {
            c.drawLine(x, 227f, x, 256.5f, paint(alpha(0xffaab2bf.toInt(), 0.35f), blur = 0.5f, stroke = 1f))
        }
        c.drawPath(svg(NOSE_LIT), paint(shader = LinearGradient(584f, 0f, 595f, 0f,
            alpha(NOSE_WHITE, 0.3f), NOSE_WHITE, Shader.TileMode.CLAMP), blur = 1.5f))
        // The light running under the cab's window and on round into the nose's front.
        c.drawPath(svg(UNDER_CAB), paint(alpha(0xfff2f5f8.toInt(), 0.85f), blur = 0.7f, stroke = 2.4f))
        val band = svg(BAND)
        c.drawPath(band, paint(shader = LinearGradient(598f, 217f, 587f, 227f,
            0xff8e8e8e.toInt(), 0xff4c4c4e.toInt(), Shader.TileMode.CLAMP), blur = 0.6f))
        c.drawPath(svg(BAND_EDGE), paint(alpha(0xff2a2b30.toInt(), 0.9f), blur = 0.7f, stroke = 2f))
        c.save()
        c.clipPath(band)
        c.drawPath(svg(NOSE_RIM), paint(alpha(0xffb4b4b4.toInt(), 0.8f), blur = 0.5f, stroke = 1.4f))
        c.restore()
        val cab = svg(CAB)
        c.drawPath(cab, paint(alpha(GLOW, 0.4f), blur = 0.8f, stroke = 3f))
        c.drawPath(cab, paint(shader = vg(228.8f to 0xff3a4352.toInt(), 231f to 0xff2c3434.toInt(),
            240.6f to 0xff283030.toInt())))
        c.save()
        c.clipPath(cab)
        c.drawLine(583f, 231f, 590.5f, 240.5f, paint(alpha(0xff2a3358.toInt(), 0.8f), blur = 1f, stroke = 3f))
        c.restore()
        c.drawOval(RectF(600.3f, 244.9f, 602.7f, 248.1f), paint(0xffb8c4dc.toInt()))
        c.restore()
    }

    /** A bus in the same hand: the same shell and glass, a flat front, three windows, two wheels. */
    private fun bus(c: Canvas, line: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val shell = Path()
        shell.addRoundRect(RectF(2f, 1f, 98f, 34f),
            floatArrayOf(4f, 4f, 7f, 7f, 3f, 3f, 3f, 3f), Path.Direction.CW)
        p.shader = LinearGradient(0f, 0f, 0f, 34f, intArrayOf(SHELL_TOP, SHELL_TOP, SHELL_FOOT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(shell, p)
        p.shader = null

        c.save()
        c.clipPath(shell)
        p.color = line
        c.drawRect(0f, 24f, 100f, 26.6f, p)
        p.color = GLASS
        c.drawRoundRect(RectF(10f, 7f, 31f, 18f), 1.6f, 1.6f, p)
        c.drawRoundRect(RectF(34f, 7f, 55f, 18f), 1.6f, 1.6f, p)
        c.drawRoundRect(RectF(58f, 7f, 79f, 18f), 1.6f, 1.6f, p)
        c.drawRoundRect(RectF(84.5f, 5.5f, 101f, 20f), 2f, 2f, p)
        c.restore()

        for (x in floatArrayOf(22f, 78f)) {
            p.color = WHEEL
            c.drawCircle(x, 35f, 4.6f, p)
            p.color = HUB
            c.drawCircle(x, 35f, 1.8f, p)
        }
        p.color = LAMP
        c.drawCircle(96.4f, 28.4f, 1f, p)
    }

    /**
     * A stop on the bar: the design's map pin in the line's colour with a metro car's front on it,
     * a soft bevel round its rim and a dark edge, so it stands off the card. The bar's middle pin
     * (`progress_point1`), which the plugin keeps at the bar's middle.
     */
    private fun pin(line: Int): Bitmap {
        val d = Resources.getSystem().displayMetrics.density
        val b = slot(d)
        val c = Canvas(b)
        c.scale(d / DESIGN_PX, d / DESIGN_PX)
        c.translate(-(757f - SLOT_W * DESIGN_PX / 2f), -SLOT_Y)
        val drop = drop(757f, 194f, 36.5f, 47f, 48f, 3f, 5f, 10f)
        c.save()
        c.translate(0f, 1.5f)
        c.drawPath(drop, paint(alpha(Color.BLACK, 0.2f), blur = 2f))
        c.restore()
        c.drawPath(drop, paint(alpha(AmapTransitScene.blend(line, Color.BLACK, 0.55f), 0.8f), stroke = 2f))
        c.drawPath(drop, paint(line))
        c.save()
        c.clipPath(drop)
        c.drawPath(drop, paint(alpha(0xff7088b0.toInt(), 0.38f), blur = 1.5f, stroke = 9f))
        c.restore()

        // The car's front: its body and the base it stands on, white, a little shadow under them.
        val front = Path()
        front.addRoundRect(RectF(741.8f, 173.2f, 772.2f, 206.5f),
            floatArrayOf(7f, 7f, 7f, 7f, 5f, 5f, 5f, 5f), Path.Direction.CW)
        front.op(svg("M746 206 L768 206 L769 210 Q770 212.6 773.6 213.8 L740.4 213.8 Q744 212.6 745 210 Z"),
            Path.Op.UNION)
        c.drawPath(front, paint(alpha(Color.BLACK, 0.18f), blur = 0.8f))
        c.drawPath(front, paint(Color.WHITE))
        val grey = AmapTransitScene.blend(line, 0xff8a9090.toInt(), 0.62f)
        c.drawRect(748.5f, 206.8f, 766.5f, 209.6f, paint(grey))
        c.drawRoundRect(RectF(752f, 175.4f, 762f, 178f), 1.3f, 1.3f, paint(grey))
        val window = RectF(746.5f, 180.5f, 768.1f, 191.3f)
        c.drawRoundRect(window, 2f, 2f, paint(line))
        c.drawRoundRect(window, 2f, 2f, paint(AmapTransitScene.blend(line, 0xff103070.toInt(), 0.4f), stroke = 1f))
        c.drawCircle(748.8f, 199f, 2.5f, paint(grey))
        c.drawCircle(765.3f, 199f, 2.5f, paint(grey))
        return b
    }

    /**
     * Where the leg ends: the design's grey pin with a white flag on it, the bar's
     * `progress_point2`. The plugin stands it at the bar's end; the design has the pin's right
     * edge at that end, so it sits right of its box's middle.
     */
    private fun flag(): Bitmap {
        val d = Resources.getSystem().displayMetrics.density
        val b = slot(d)
        val c = Canvas(b)
        c.scale(d / DESIGN_PX, d / DESIGN_PX)
        c.translate(-(1248f - SLOT_W * DESIGN_PX), -SLOT_Y)
        val drop = drop(1215f, 203.5f, 32.5f, 45f, 30f, 14f, 6f, 8f)
        c.save()
        c.translate(0f, 1.5f)
        c.drawPath(drop, paint(alpha(Color.BLACK, 0.2f), blur = 2f))
        c.restore()
        c.drawPath(drop, paint(shader = vg(171f to 0xffa3a1ad.toInt(), 236f to 0xff97959f.toInt(),
            248f to 0xff8c8b92.toInt())))
        c.drawRoundRect(RectF(1203f, 191f, 1206.6f, 220.2f), 0.6f, 0.6f, paint(Color.WHITE))
        val cloth = paint(Color.WHITE, stroke = 0.8f)
        cloth.style = Paint.Style.FILL_AND_STROKE
        cloth.strokeJoin = Paint.Join.ROUND
        c.drawPath(svg("M1206 191 C1211 189.8 1215 191 1219 192.3 L1231.5 192.5 L1231.5 211.8 " +
            "L1219 211.6 C1215 210.3 1211 209.8 1206 210.8 Z"), cloth)
        return b
    }

    /** A pin's box, the plugin's own: [SLOT_W] x [SLOT_H], `fitCenter`, standing on its foot. */
    private fun slot(d: Float): Bitmap = Bitmap.createBitmap(Math.round(SLOT_W * d),
        Math.round(SLOT_H * d), Bitmap.Config.ARGB_8888)

    /**
     * A map pin centred on (cx, cy): a round head of radius [r] drawn out to a point [tip] below
     * the centre. Each flank leaves the circle [deg] degrees either side of its foot and runs to
     * the point in one curve, its handles [k1] along the circle's tangent and ([c2x], [c2y]) off
     * the point - fitted to the design's pins row by row.
     */
    private fun drop(cx: Float, cy: Float, r: Float, tip: Float, deg: Float, k1: Float,
                     c2x: Float, c2y: Float): Path {
        val th = Math.toRadians(deg.toDouble())
        val px = (r * Math.sin(th)).toFloat()
        val py = (r * Math.cos(th)).toFloat()
        val c1x = px - k1 * Math.cos(th).toFloat()
        val c1y = py + k1 * Math.sin(th).toFloat()
        val p = Path()
        p.moveTo(cx, cy + tip)
        p.cubicTo(cx - c2x, cy + tip - c2y, cx - c1x, cy + c1y, cx - px, cy + py)
        p.arcTo(RectF(cx - r, cy - r, cx + r, cy + r), 90f + deg, 360f - 2f * deg, false)
        p.cubicTo(cx + c1x, cy + c1y, cx + c2x, cy + tip - c2y, cx, cy + tip)
        p.close()
        return p
    }

    /** SVG path data, the subset the pictures are written in: absolute M, L, C, Q and Z. */
    private fun svg(data: String): Path {
        val t = Regex("[MLCQZ]|-?[0-9.]+").findAll(data).map { it.value }.toList()
        val p = Path()
        var i = 0
        var cmd = 'M'
        fun n() = t[i++].toFloat()
        while (i < t.size) {
            if (t[i][0].isLetter()) cmd = t[i++][0]
            when (cmd) {
                'M' -> { p.moveTo(n(), n()); cmd = 'L' }
                'L' -> p.lineTo(n(), n())
                'C' -> p.cubicTo(n(), n(), n(), n(), n(), n())
                'Q' -> p.quadTo(n(), n(), n(), n())
                'Z' -> p.close()
            }
        }
        return p
    }

    /** A vertical gradient through (y, colour) stops. */
    private fun vg(vararg stops: Pair<Float, Int>): Shader {
        val y0 = stops.first().first
        val y1 = stops.last().first
        return LinearGradient(0f, y0, 0f, y1, IntArray(stops.size) { stops[it].second },
            FloatArray(stops.size) { (stops[it].first - y0) / (y1 - y0) }, Shader.TileMode.CLAMP)
    }

    private fun alpha(colour: Int, a: Float) = (Math.round(a * 255) shl 24) or (colour and 0xffffff)

    /**
     * A paint for the pictures: a colour or a shader, a stroke when [stroke] is set, and a blur of
     * [blur] - an SVG `stdDeviation`, which is how the pictures were matched to the design, turned
     * into the radius `BlurMaskFilter` takes (Skia: sigma = radius * 0.57735 + 0.5).
     */
    private fun paint(colour: Int = Color.BLACK, shader: Shader? = null, blur: Float = 0f,
                      stroke: Float = 0f): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = if (shader != null) Color.BLACK else colour
        p.shader = shader
        if (blur > 0f) p.maskFilter = BlurMaskFilter(((blur - 0.5f) / 0.57735f).coerceAtLeast(0.1f),
            BlurMaskFilter.Blur.NORMAL)
        if (stroke > 0f) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = stroke
        }
        return p
    }

    /** The line as a disc in its colour, its number on it: 「地铁1号线」 is 1, 「机场线」 机场. */
    private fun badge(line: String, bg: Int, fg: Int): Bitmap {
        val size = 96
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = bg
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        val digits = Regex("\\d+").find(line)?.value
        val label = digits ?: line.removePrefix("地铁").removeSuffix("线").take(2).ifEmpty { "M" }
        p.color = if (Color.alpha(fg) == 0) Color.WHITE else fg
        p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        p.textSize = if (label.length <= 1) 56f else if (label.length == 2) 44f else 32f
        val fm = p.fontMetrics
        c.drawText(label, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, p)
        return b
    }
}
