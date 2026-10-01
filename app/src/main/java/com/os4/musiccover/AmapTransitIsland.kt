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
 * HyperOS's flat template (protocol 1: title, content, ticker) is a one-line card, too small for
 * any of this, so by default the ride is a focus notification with its own layout
 * (miui.focus.rv, [Style.CARD]): this module's R.layout.mc_transit_card, which SystemUI inflates
 * from this package (高德 holds QUERY_ALL_PACKAGES, so it may name it); its ticker, AOD line and
 * super island are in miui.focus.param.custom. [Style.TEMPLATE] is the system's large template
 * instead (param_v2: baseInfo, multiProgressInfo, bgInfo), [Style.FLAT] the old small one; the
 * probe switches between them (`AMAPPROBE --es island card|template|flat`), for a HyperOS build
 * that will not show one of them for 高德.
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
    /** The ground is soft; two thirds of the card's pixels are plenty and a third the memory. */
    private const val GROUND_SCALE = 0.67f
    private const val BOTTOM = 0xff07080b.toInt()

    enum class Style { CARD, TEMPLATE, FLAT }

    @Volatile var style = Style.CARD
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

    /** `AMAPPROBE --es island card|template|flat`: the style, and the ride again in it. */
    fun setStyle(ctx: Context?, name: String): String {
        val s = Style.values().firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return "unknown style $name (card, template, flat)"
        style = s
        Xp.log(TAG + "style $s")
        val e = lastEntity
        if (ctx != null && e != null) {
            // A different layout under the same id: take the old one down so it is drawn anew.
            ctx.getSystemService(NotificationManager::class.java)?.cancel(ID)
            lastStatus = null
            update(ctx, e)
        }
        return describe()
    }

    fun describe(): String {
        val sb = StringBuilder("island: style=").append(style.name.lowercase())
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
                    .put("textInfo", JSONObject().put("title", title)))
                .apply {
                    if (f.secondary.isNotEmpty()) put("imageTextInfoRight", JSONObject()
                        .put("type", 2)
                        .put("textInfo", JSONObject().put("title", f.secondary)))
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
        val leg = trip.leg
        val total = leg.via.size + 1
        if (f.nodes != null && total > 1) {
            val done = (total - leg.remain).coerceIn(0, total)
            o.put("multiProgressInfo", JSONObject()
                .put("title", f.secondary)
                .put("progress", done * 100 / total)
                .put("color", hex(f.lineBg))
                .put("points", leg.remain.coerceIn(0, 4)))
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
     * track. A default picture rather than a landmark stays dim, as on the page.
     */
    private fun ground(ctx: Context, f: AmapTransitScene.Frame, scale: Float): Bitmap {
        val dp = ctx.resources.displayMetrics.density
        val w = (cardWidth(ctx) * scale).toInt().coerceAtLeast(1)
        val h = (CARD_DP * dp * scale).toInt().coerceAtLeast(1)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.DITHER_FLAG)
        val top = AmapTransitScene.blend(f.lineBg, BOTTOM, 0.42f)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(top, AmapTransitScene.blend(f.lineBg, BOTTOM, 0.72f), BOTTOM),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        val set = art
        val still = set?.still
        if (still != null && set.pick == artPick) {
            val lw = h * still.width / still.height.toFloat()
            val r = RectF(w - lw, 0f, w.toFloat(), h.toFloat())
            val ip = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            ip.alpha = if (set.pick.fallback) 110 else 235
            c.drawBitmap(still, null, r, ip)
            // Into the colour on the left, under the words.
            val fade = Paint(Paint.DITHER_FLAG)
            fade.shader = LinearGradient(maxOf(0f, r.left), 0f, maxOf(0f, r.left) + w * 0.45f, 0f,
                top, top and 0x00ffffff, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fade)
            // And darker at the foot, under the station names.
            fade.shader = LinearGradient(0f, h * 0.45f, 0f, h.toFloat(),
                0x00000000, 0x99000000.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fade)
        }
        return b
    }

    /** The three stops, the page's own Track, the badge's room only when there is a badge. */
    private fun track(f: AmapTransitScene.Frame, w: Int, dp: Float): Bitmap {
        val badged = f.nodes.badge.any { it != null }
        val top = (if (badged) AmapTransitScene.Track.TOP_DP else 12f) * dp
        val h = (top + AmapTransitScene.Track.BOTTOM_DP * dp).toInt()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        AmapTransitScene.Track(dp).draw(Canvas(b), f, w * 0.12f, w * 0.88f, top)
        return b
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
