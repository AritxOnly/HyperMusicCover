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
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject

/**
 * The focus island for a bus or subway ride, posted as 高德 in 高德's process.
 *
 * On HyperOS 高德 posts a focus notification only for walking and cycling (its
 * XIAOMIFootRideConnectDevice, id 1236); a bus or subway navigation has none, so there is no
 * island to tap and AmapTransitScene could never be opened. ColorOS has no such gap: its
 * lock-screen card is SceneService's own (lockImmersiveDefault="1"). So the ride gets an island
 * here, made the way 高德 makes its walking one - the first focus protocol's flat template
 * (protocol 1, title, content, ticker and aodPic out of miui.focus.pics) - from the same
 * intentEntity the page is drawn from, with the page's own words (AmapTransitScene.Frame).
 * Posted from 高德's process, it is 高德's notification, which HyperOS already lets be a focus
 * one. Tapping it on the lock screen opens the page (AmapTransitScene.servesKey); tapping it
 * elsewhere opens 高德 at the navigation.
 *
 * Only while the current leg is a bus or a subway: a walking leg is 高德's own island again.
 */
internal object AmapTransitIsland {

    private const val TAG = "MCAmap: transit island: "
    /** Beside 高德's walking island, 1236. */
    private const val ID = 1237
    private const val CHANNEL = "mc_transit"
    /** As long as SystemUI believes a silent trip (AmapTransitScene.STALE_MS). */
    private const val TIMEOUT_MS = 10L * 60_000L

    private const val PIC = "miui.focus.pic_mc_transit"

    @Volatile private var posted = false
    @Volatile private var lastStatus: String? = null
    @Volatile private var lastKey: String? = null

    /** The ride's island for [entity], or none for null or a leg that is not a ride. */
    fun update(ctx: Context, entity: String?) {
        try {
            val trip = entity?.let { AmapTransitScene.Trip.parse(JSONObject(it)) }
            if (trip == null || !trip.leg.rides()) {
                cancel(ctx)
                return
            }
            post(ctx, trip, JSONObject(entity).optString("deepLink"))
        } catch (t: Throwable) {
            Xp.log(TAG + "not posted: $t")
        }
    }

    fun cancel(ctx: Context) {
        if (!posted) return
        posted = false
        lastStatus = null
        lastKey = null
        ctx.getSystemService(NotificationManager::class.java)?.cancel(ID)
        Xp.log(TAG + "taken down")
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
        val title = f.primary.ifEmpty { f.line }
        val content = listOf(f.line, f.secondary).filter { it.isNotEmpty() }.joinToString(" · ")
        // A new milestone floats once, the way 高德's walking island floats at a turn; the same
        // one reposted (the keepalive) does not.
        val milestone = trip.status != lastStatus
        lastStatus = trip.status
        val param = JSONObject()
            .put("protocol", 1)
            .put("scene", "templateRevertProgressScene")
            .put("title", title)
            .put("content", content)
            .put("ticker", title)
            .put("tickerPic", PIC)
            .put("aodTitle", title)
            .put("aodPic", PIC)
            .put("colorTitle", "#FFFFFF")
            .put("colorContent", "#FFFFFF")
            .put("colorBg", "#000000")
            .put("showSmallIcon", false)
            .put("enableFloat", milestone && trip.status in FLOAT_AT)
            .put("reopen", if (milestone) "reopen" else "close")
            .put("padding", true)
            .put("updatable", true)
        val pics = Bundle().apply {
            val badge = Icon.createWithBitmap(badge(f.line, f.lineBg, f.lineText))
            putParcelable(PIC, badge)
            putParcelable("miui.focus.pic_large", badge)
        }
        val extras = Bundle().apply {
            putString("miui.focus.param", param.toString())
            putBundle("miui.focus.pics", pics)
        }
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
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setTimeoutAfter(TIMEOUT_MS)
            .setContentIntent(PendingIntent.getActivity(ctx, ID, open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addExtras(extras)
            .build()
        nm.notify(ID, n)
        posted = true
        val key = "$title|$content"
        if (key != lastKey) Xp.log(TAG + "$title | $content")
        lastKey = key
    }

    /** Floats on: the stop before yours, a transfer, arriving. */
    private val FLOAT_AT = setOf("4", "6", "7")

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
