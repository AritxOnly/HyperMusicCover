package com.os4.musiccover

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
    private const val TIMEOUT_MS = 10L * 60_000L

    private const val PIC = "miui.focus.pic_mc_transit"
    private const val PIC_BG = "miui.focus.pic_mc_transit_bg"

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
     * The bar's nodes. Fixed, not the stops left: the card's track is three stops - the one
     * before, this one and the next - and a bar that loses a node at every station reads as a
     * different card each time the ride moves on, rather than the same ride further along.
     */
    private const val PROGRESS_POINTS = 3
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
        if (f.nodes != null && total > 1) {
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
            o.put("multiProgressInfo", JSONObject()
                .put("title", f.secondary)
                .put("progress", percent.coerceIn(0, 100))
                .put("color", hex(f.lineBg))
                .put("points", PROGRESS_POINTS))
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
