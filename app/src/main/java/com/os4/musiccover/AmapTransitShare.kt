package com.os4.musiccover

import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * 高德's half of the lock screen's bus and subway card, in 高德's process: what ColorOS 17's
 * SceneService does with 高德's trip, done with what 高德 sends on HyperOS.
 *
 * On ColorOS 高德's script hands SceneService a GaoDePtIntentEntity with the milestone already in
 * it (`status` 1-9), through the IntelligentIntent provider. On HyperOS that script never opens
 * the channel (no `bizBegin(10200)`; checked on real rides), so the trip is read off the two
 * channels it does open, through NativesModuleWearable:
 *
 *   bizType 103  the trip card, every 1-3 s while a navigation is under way; its begin and end are
 *                the navigation's own (the route page never opens it; 「开始导航」 does).
 *   bizType 113  the plan (`type 24`, at the navigation's start) and the live data (`type 25`).
 *
 * AmapTransitEntity turns them into the entity; this decides what ColorOS gets from 高德 and 高德
 * here never says - which milestone it is - and runs SceneService's own rules on top:
 *
 *   - the milestone, read off which leg 高德's card is on and the stops left of it
 *     (AmapTransitMilestones), with GaoDePtRideCodeDeferBindManager's five minutes for a subway's
 *     到站 before the walk after it.
 *   - GaoDePtFinalDestCardManager: a trip whose last leg is a ride gets its end card five minutes
 *     (subway) or 35 s (bus) after that ride's 到站.
 *   - the end card (ya.a) the moment 高德 says the trip has arrived, for 30 s; nothing after it.
 *   - GaoDePtNaviSceneRouter.h: 30 s with no word after the end card, 15 min after a 到站, 30 min
 *     otherwise, and the card is taken down.
 *   - GaoDePtDismissHandler: the navigation ending in 高德 (its `bizEnd(103)`) takes it all down -
 *     unless the trip has arrived or is on its last walk (`shouldIgnoreDeleteIntent`).
 *
 * The entity goes to SystemUI (`op transit`, AmapTransitScene draws the page) and to the island
 * (AmapTransitIsland), which both build their card with AmapTransitCard.
 *
 * All of it runs on one thread of its own; the hooks only hand the payloads over.
 */
internal object AmapTransitShare {

    private const val TAG = "MCAmap: transit: "
    private const val SYSUI = "com.android.systemui"
    private const val SYSUI_PROBE = "com.os4.musiccover.PROBE"
    private const val WEARABLE = "com.amap.bundle.wearable.ajx.NativesModuleWearable"

    /** The trip card's channel (third_sdk_oppo_aod), and its begin and end are the navigation's. */
    private const val RIDE_BIZ = 103
    /** The plan and the live data's channel (amap_glass). */
    private const val LIVE_BIZ = 113
    private const val PLAN_TYPE = 24
    private const val RIDE_TYPE = 25

    /** GaoDePtNaviSceneRouter.h: how long a card lasts without another word. */
    private const val SILENCE_MS = 30 * 60_000L
    private const val ARRIVAL_SILENCE_MS = 15 * 60_000L
    private const val FINAL_MS = 30_000L
    /** GaoDePtFinalDestCardManager.i: a last ride's 到站 to the trip's end card. */
    private const val SUBWAY_LAST_MS = 300_000L
    private const val BUS_LAST_MS = 35_000L
    /** An unchanged state is told SystemUI again this often (a restarted SystemUI asks anyway). */
    private const val KEEPALIVE_MS = 60_000L

    /** 高德's own walking island, posted every second while its walking navigation runs. */
    private const val WALK_ISLAND_STALE_MS = 5_000L

    private val worker: Handler = Handler(HandlerThread("mc-transit").apply { start() }.looper)

    // ------------------------------------------------------------------ what 高德 has sent

    /** Whether 高德's trip card channel is open: a navigation is under way. */
    private var navigating = false
    /** The last plans 高德 sent, newest first; the one that fits the trip's capsules is used. */
    private val plans = ArrayDeque<JSONObject>()
    /** The trip's legs, one capsule each (the latest card's `planData`). */
    private var capsules: JSONArray? = null
    /** The latest card with words, and the latest one for each leg. */
    private var card: JSONObject? = null
    private val cardFor = HashMap<Int, JSONObject>()
    private var live: JSONObject? = null

    // ------------------------------------------------------------------ the trip's state

    private var tripId = ""
    /** Which leg, and which milestone of it. */
    private val milestones = AmapTransitMilestones()
    /** The exit 高德 named for the stop each ride ends at. */
    private val exits = HashMap<Int, String>()
    private var finalShown = false
    private var finalAt = 0L

    /** The opening walk: whether there is one, and whether it has been handed to 高德 yet. */
    private var opening = false
    private var walked = false

    @Volatile private var walkIslandAt = 0L

    /** A trip being played from its own plan (`transit sim`): 高德's own payloads wait meanwhile. */
    @Volatile private var simulating = false
    /** The last trip's capsules, kept past its end for `transit sim`. */
    private var lastCaps: JSONArray? = null

    /** What SystemUI was last told, and when. */
    @Volatile private var lastSent: String? = null
    private var lastSentAt = 0L
    @Volatile private var lastStatus = ""
    @Volatile private var lastKind = ""

    private val dismiss = Runnable { dismissAll("silence") }
    private val tick = Runnable { update() }
    private val finalCard = Runnable { showFinal("last ride") }

    // ------------------------------------------------------------------ hooks

    fun handle(cl: ClassLoader) {
        AmapTransitIsland.handle()
        // Without a connected device on 113 高德 sends no plan and no live data (AmapOppoBridge).
        AmapOppoBridge.handle(cl)
        try {
            val module = Xp.findClass(WEARABLE, cl)
            for (name in arrayOf("bizBegin", "bizBeginWithData", "bizEnd", "sendMessage")) {
                try {
                    Xp.hookAll(module, name) { chain ->
                        val a = chain.args
                        val biz = a.firstOrNull { it is Int } as Int?
                        val text = a.firstOrNull { it is String } as String?
                        // The trip's own channels only: walking navigation's 101 every second
                        // would push them out of the log.
                        if (biz == RIDE_BIZ || biz == LIVE_BIZ) Ledger.record(name, biz, text)
                        if (name == "sendMessage") {
                            if (text != null && (biz == RIDE_BIZ || biz == LIVE_BIZ)) {
                                worker.post { if (!simulating) take(biz, text) }
                            }
                        } else if (biz == RIDE_BIZ) {
                            val closed = name == "bizEnd"
                            worker.post { if (!simulating) channel(closed) }
                        }
                        chain.proceed()
                    }
                } catch (t: Throwable) {
                    Xp.log(TAG + "$name not watched: $t")
                }
            }
        } catch (t: Throwable) {
            Xp.log(TAG + "wearable module not watched: $t")
        }
    }

    /** 高德's own walking island (1236) was posted, or taken down. */
    fun walkIsland(posted: Boolean) {
        val was = walkIslandUp()
        walkIslandAt = if (posted) SystemClock.uptimeMillis() else 0L
        if (was != walkIslandUp()) worker.post { update() }
    }

    fun walkIslandUp(): Boolean =
        !simulating && walkIslandAt != 0L && SystemClock.uptimeMillis() - walkIslandAt < WALK_ISLAND_STALE_MS

    /** Runs [r] on the trip's own thread. */
    fun post(r: Runnable) {
        worker.post(r)
    }

    // ------------------------------------------------------------------ the channels

    /** One payload, from a channel or from the probe. */
    private fun take(biz: Int, text: String) {
        try {
            val root = JSONObject(text)
            if (biz == RIDE_BIZ) {
                val data = root.optJSONObject("cardData") ?: return
                val caps = data.optJSONArray("planData")
                if (caps != null && caps.length() > 0) {
                    capsules = caps
                    lastCaps = caps
                }
                if (data.optString("title").trim().isEmpty()) {
                    // The card a navigation starts with: the capsules and nothing else. They say
                    // whether the trip opens with a walk, which is handed to 高德 at once.
                    if (caps != null && caps.length() > 0) {
                        opening = AmapTransitEntity.walking(caps.getJSONObject(0))
                        walkToFirstStop()
                    }
                    return
                }
                card = data
                index(data)?.let { cardFor[it] = data }
            } else {
                val datas = array(root.opt("datas")) ?: return
                for (i in 0 until datas.length()) {
                    val e = datas.optJSONObject(i) ?: continue
                    val d = e.optJSONObject("data") ?: continue
                    when (e.optInt("type", -1)) {
                        PLAN_TYPE -> {
                            plans.addFirst(d)
                            while (plans.size > 4) plans.removeLast()
                            walkToFirstStop()
                        }
                        RIDE_TYPE -> live = d
                    }
                }
            }
            update()
        } catch (t: Throwable) {
            Xp.log(TAG + "payload failed: $t " + t.stackTrace.take(3).joinToString(" | "))
        }
    }

    /**
     * 高德's trip card channel opening or closing: a navigation starting, or being left. A channel
     * already open is left alone - the script opens its channels again when it rebuilds them.
     */
    private fun channel(closed: Boolean) {
        if (!closed) {
            if (navigating) return
            navigating = true
            newNavi()
            return
        }
        navigating = false
        if (lastSent == null && capsules == null) return
        // GaoDePtDismissHandler.b: 高德 deleting the trip is not the end while the trip has arrived
        // or is on its last walk - those cards end on their own.
        if (finalShown || finalPending() || onLastWalk()) {
            Xp.log(TAG + "navigation left, card kept (" + lastKind + ")")
            return
        }
        dismissAll("navigation left")
    }

    /** A navigation has started: whatever the last one left is let go. */
    private fun newNavi() {
        if (lastSent != null) dismissAll("new navigation")
        reset()
        tripId = "gaode-pt-" + System.currentTimeMillis()
        Xp.log(TAG + "navigation started")
    }

    private fun reset() {
        worker.removeCallbacks(dismiss)
        worker.removeCallbacks(tick)
        worker.removeCallbacks(finalCard)
        capsules = null
        card = null
        cardFor.clear()
        live = null
        milestones.reset()
        exits.clear()
        finalShown = false
        finalAt = 0L
        opening = false
        walked = false
    }

    // ------------------------------------------------------------------ the trip

    /**
     * What the trip is now, told on. Called for every payload and whenever something held runs
     * out; works out the leg, its milestone and what the island and the page get.
     */
    private fun update() {
        worker.removeCallbacks(tick)
        if (finalShown) return // GaoDePt_Helper: after the end card, nothing more is taken
        val caps = capsules ?: return
        val c = card ?: return
        val now = SystemClock.uptimeMillis()
        val title = c.optString("title").trim()
        if (c.optBoolean("arrived", false) || title.startsWith("已到达")) {
            showFinal("arrived")
            return
        }
        val n = caps.length()
        val at = index(c) ?: return
        if (at !in 0 until n) return
        val plan = plans.firstOrNull { AmapTransitEntity.fits(it, caps) }
        if (at == 0 && walking(caps, 0)) walkToFirstStop()

        milestones.moveTo(at, n, now, { walking(caps, it) }, { subway(caps, it, plan) })
        val held = milestones.held(now, walkIslandUp())
        val shown = held?.leg ?: at
        val legCard = cardFor[shown] ?: c
        val status: String
        var info: AmapTransitEntity.Live? = null
        var wake = 0L
        if (held != null) {
            status = held.status
            info = AmapTransitEntity.Live(0, 1.0, "", JSONArray())
            wake = held.until
        } else if (walking(caps, at)) {
            status = ""
        } else {
            info = ride(at, caps, plan, legCard, now)
            val group = live?.optJSONObject("locationData")?.optInt("groupIndex", -1) ?: -1
            val tip = if (group != at) -1
                else live?.optJSONObject("arriveRemind")?.optInt("tipType", -1) ?: -1
            // tipType 48: 高德 counting the leg as done (seen the moment a ride's card gave way).
            val step = milestones.rideStatus(info.remain, at == n - 1, tip == 48, plan != null, now)
            status = step.status
            wake = step.wakeAt
        }
        if (wake > now) worker.postDelayed(tick, wake - now)
        val exit = AmapTransitEntity.exit(legCard).also { if (it.isNotEmpty()) exits[shown] = it }
            .ifEmpty { exits[shown].orEmpty() }
        val entity = AmapTransitEntity.build(plan, caps, legCard, shown, status, info,
            if (plan == null) AmapTransitEntity.Fallback(ArrayList(milestones.seen), milestones.boardRemain)
            else null, exit, AmapTransitEntity.sentence(legCard), tripId)
        // The last ride's 到站, with nothing after it: the end card follows (FinalDestCardManager).
        if (status == AmapTransitCard.ARRIVE_LINE_DESTINATION && shown == n - 1 && finalAt == 0L) {
            val wait = if (subway(caps, shown, plan)) SUBWAY_LAST_MS else BUS_LAST_MS
            finalAt = now + wait
            worker.postDelayed(finalCard, wait)
        }
        tell(entity.toString(), status)
        silence(status)
    }

    /** What the ride under way says about itself, and the milestones told of it. */
    private fun ride(at: Int, caps: JSONArray, plan: JSONObject?, c: JSONObject, now: Long): AmapTransitEntity.Live {
        // The live data's own leg is `locationData.groupIndex` - the same count as the capsules
        // (0 for the walk to the station, 1 for the ride, 2 for the walk after it on 10-05) - and
        // its stop count belongs to that leg only: on a walk it counts something else.
        val group = live?.optJSONObject("locationData")?.optInt("groupIndex", -1) ?: -1
        val where = if (group == at) live?.optJSONObject("arriveRemind") else null
        val loc = if (index(c) == at) c.optJSONObject("location") else null
        val bus = !subway(caps, at, plan)
        val cardCount = loc?.optInt("remainStations", -1) ?: -1
        val liveCount = where?.optInt("remainStopNum", -1) ?: -1
        val said = Regex("(\\d+)\\s*站").find(AmapTransitEntity.sentence(c))?.groupValues?.get(1)?.toIntOrNull() ?: -1
        // A bus counts its own stops more finely than the card; a subway's card is its own count.
        val remain = listOf(if (bus) liveCount else cardCount, cardCount, liveCount, said)
            .firstOrNull { it >= 0 } ?: 0
        val cur = where?.optString("curStopName").orEmpty().replace(" ", "").trim()
        val seg = plan?.optJSONArray("segmentlist")?.optJSONObject(rideNumber(caps, at))
        val total = (seg?.optJSONArray("via_st_list")?.length() ?: -1) + 1
        milestones.observe(remain, total, cur, now)
        return AmapTransitEntity.Live(remain, loc?.optDouble("persent", -1.0) ?: -1.0, cur,
            arrivals(seg, bus))
    }

    /**
     * The next arrivals at the stop a ride boards at, as ColorOS's realtime items: a subway's
     * `subway[].tripTime`, a bus's `realtime.buses[].trip`, the ride's own line first.
     */
    private fun arrivals(seg: JSONObject?, bus: Boolean): JSONArray {
        val out = JSONArray()
        val id = seg?.optString("busid")?.trim().orEmpty()
        val name = seg?.optString("bus_key_name")?.trim().orEmpty()
        val dir = seg?.optString("directionName")?.trim().orEmpty()
        if (!bus) {
            val lines = live?.optJSONArray("subway") ?: return out
            val line = pick(lines, "lineId", id) ?: return out
            val times = line.optJSONArray("tripTime") ?: return out
            for (i in 0 until times.length()) {
                val t = times.optJSONObject(i) ?: continue
                out.put(JSONObject()
                    .put("mainTitle", t.optString("mainTitle").trim())
                    .put("orderTiptext", t.optString("orderTiptext").trim())
                    .put("lineName", name)
                    .put("lineDirection", dir))
            }
        } else {
            val buses = live?.optJSONObject("realtime")?.optJSONArray("buses") ?: return out
            val b = pick(buses, "line", id) ?: return out
            val trips = b.optJSONArray("trip")
            for (i in 0 until (trips?.length() ?: 0)) {
                val t = trips!!.optJSONObject(i) ?: continue
                val words = t.optString("grade_words").trim().ifEmpty { b.optString("sub_status").trim() }
                out.put(JSONObject()
                    .put("mainTitle", words)
                    .put("orderTiptext", t.optString("station_left").trim().let { if (it.isEmpty()) "" else it + "站" })
                    .put("lineName", name)
                    .put("lineDirection", dir))
            }
        }
        return out
    }

    /** The entry of [list] whose [key] is [id], or its only one. */
    private fun pick(list: JSONArray, key: String, id: String): JSONObject? {
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            if (id.isNotEmpty() && o.optString(key).trim() == id) return o
        }
        return if (list.length() == 1) list.optJSONObject(0) else null
    }

    /** The trip has arrived: ColorOS's end card (ya.a), for 30 s, and nothing after it. */
    private fun showFinal(why: String) {
        if (finalShown) return
        val caps = capsules ?: return
        worker.removeCallbacks(finalCard)
        worker.removeCallbacks(tick)
        finalShown = true
        val plan = plans.firstOrNull { AmapTransitEntity.fits(it, caps) }
        val last = caps.length() - 1
        val entity = AmapTransitEntity.build(plan, caps, card, last,
            AmapTransitCard.ARRIVE_FINAL_DESTINATION, null, null, "", "", tripId)
        Xp.log(TAG + "trip's end ($why)")
        tell(entity.toString(), AmapTransitCard.ARRIVE_FINAL_DESTINATION)
        silence(AmapTransitCard.ARRIVE_FINAL_DESTINATION)
    }

    /** GaoDePtNaviSceneRouter.h: how long the card lasts if nothing more is said. */
    private fun silence(status: String) {
        worker.removeCallbacks(dismiss)
        worker.postDelayed(dismiss, when (status) {
            AmapTransitCard.ARRIVE_FINAL_DESTINATION -> FINAL_MS
            AmapTransitCard.ARRIVE_LINE_DESTINATION -> ARRIVAL_SILENCE_MS
            else -> SILENCE_MS
        })
    }

    /** GaoDePtDismissHandler.a: the card down, and everything of the trip let go. */
    private fun dismissAll(why: String) {
        Xp.log(TAG + "dismissed: $why")
        reset()
        tell(null, "")
    }

    private fun finalPending(): Boolean = finalAt != 0L && !finalShown

    /** FinalDestCardManager.g: the trip is on its last leg, and it is a walk. */
    private fun onLastWalk(): Boolean {
        val caps = capsules ?: return false
        return milestones.legAt == caps.length() - 1 && walking(caps, milestones.legAt)
    }

    // ------------------------------------------------------------------ the legs

    /** The capsule the card is about: its `location.index`, else its line among the capsules. */
    private fun index(c: JSONObject): Int? {
        val loc = c.optJSONObject("location")
        if (loc != null && loc.has("index")) return loc.optInt("index")
        val caps = capsules ?: return null
        val line = c.optString("mainText").trim()
        if (!c.optString("title").contains("步行") && line.isNotEmpty()) {
            for (i in 0 until caps.length()) {
                val cap = caps.optJSONObject(i) ?: continue
                if (!AmapTransitEntity.walking(cap) &&
                    AmapTransitEntity.sameLine(cap.optString("text").trim(), line)) return i
            }
        }
        return null
    }

    private fun walking(caps: JSONArray, i: Int): Boolean =
        caps.optJSONObject(i)?.let { AmapTransitEntity.walking(it) } ?: false

    /** Which ride of the trip capsule [i] is (0 for the first). */
    private fun rideNumber(caps: JSONArray, i: Int): Int {
        var k = 0
        for (j in 0 until i) if (!walking(caps, j)) k++
        return k
    }

    private fun subway(caps: JSONArray, i: Int, plan: JSONObject?): Boolean {
        val seg = plan?.optJSONArray("segmentlist")?.optJSONObject(rideNumber(caps, i))
        val cap = caps.optJSONObject(i) ?: return false
        return when {
            cap.optString("capsuleType").trim() == "2" -> true
            cap.optString("capsuleType").trim() == "1" -> false
            seg != null -> seg.optString("bustype").trim() == "2"
            else -> cap.optString("text").trim().endsWith("线")
        }
    }

    // ------------------------------------------------------------------ the opening walk

    /**
     * Hands the trip's opening walk to 高德's own walking navigation, out of the plan: the first
     * ride's stop (`on_station`) and the way into it (`inport`, with its coordinate). 高德's card
     * that names the walk comes 26-59 s after 「开始导航」; the plan is there 30 ms after it.
     * ColorOS starts the same walk from its card's button (beginWalkAndBikeInTripNaviOnSilentClick),
     * with an entity this phone's 高德 does not send.
     */
    private fun walkToFirstStop() {
        if (!opening || walked) return
        // Only a plan whose rides are this trip's: the newest one is not necessarily it (a route
        // page sends one per plan shown, and a replay leaves its own behind).
        val caps = capsules ?: return
        val plan = plans.firstOrNull { AmapTransitEntity.fits(it, caps) } ?: return
        val seg = plan.optJSONArray("segmentlist")?.optJSONObject(0) ?: return
        val to = seg.optJSONObject("on_station")?.optString("name")?.trim().orEmpty()
        val c = seg.optJSONObject("inport")?.optJSONObject("coord")
        val lat = c?.optString("lat")?.toDoubleOrNull()
        val lng = c?.optString("lon")?.toDoubleOrNull()
        val cl = AmapImmerse.loader()
        if (to.isEmpty() || lat == null || lng == null || cl == null) return
        walked = true
        Xp.log(TAG + "walk to $to -> " + AmapFootNavi.start(cl, lat, lng, to))
    }

    // ------------------------------------------------------------------ telling

    /** Passes the entity on; the same one again only once KEEPALIVE_MS has gone. */
    private fun tell(entity: String?, status: String) {
        val now = SystemClock.uptimeMillis()
        if (entity != null && entity == lastSent && now - lastSentAt < KEEPALIVE_MS) return
        lastSent = entity
        lastSentAt = now
        if (status != lastStatus) Xp.log(TAG + "status " + lastStatus.ifEmpty { "-" } + " -> " +
            status.ifEmpty { if (entity == null) "end" else "walk" })
        lastStatus = status
        send(entity)
    }

    private fun send(entity: String?) {
        val ctx = AmapImmerse.context() ?: run {
            Xp.log(TAG + "no context to tell SystemUI")
            return
        }
        try {
            val i = Intent(SYSUI_PROBE).setPackage(SYSUI)
                .putExtra("op", "transit")
                .putExtra("src", "amap")
            if (entity == null) i.putExtra("do", "end") else i.putExtra("json", entity)
            ProbeGuard.send(ctx, i)
            lastKind = AmapTransitIsland.update(ctx, entity)
        } catch (t: Throwable) {
            Xp.log(TAG + "tell failed: $t")
        }
    }

    /** SystemUI started over: the last state again, if the trip has not ended since. */
    fun resend() {
        worker.post {
            val entity = lastSent ?: return@post
            lastSentAt = SystemClock.uptimeMillis()
            send(entity)
        }
    }

    // ------------------------------------------------------------------ the probe

    /**
     * `AMAPPROBE --es transit ...`:
     *   raw     a payload 高德 sent (`--es json <base64>`), taken as if it had just arrived - a
     *           103 card, a 113 `datas`, the way a captured ride is played back
     *   begin / stop   the navigation's begin and end (`bizBegin` / `bizEnd(103)`)
     *   demo / end     a made-up ride (AmapTransitScene.DEMO) straight to SystemUI and the island
     *   fast / slow    the milestones' own times (a stop's 30 s, a subway's 5 min) at a tenth, or
     *                  back, for a replay that does not take the ride's length
     *   sim            the last trip navigated, played stop by stop out of its plan (simulate)
     */
    fun probe(what: String, json: String): String {
        when (what) {
            "raw", "payload" -> worker.post { raw(json) }
            "begin" -> worker.post { channel(false) }
            "stop" -> worker.post { channel(true) }
            "demo" -> worker.post { tell(AmapTransitScene.DEMO, "demo") }
            "end" -> worker.post { dismissAll("probe") }
            "fast" -> worker.post { milestones.scale = 0.1 }
            "slow" -> worker.post { milestones.scale = 1.0 }
            "sim" -> worker.post { simulate() }
        }
        return describe()
    }

    /**
     * `transit sim`: the last trip 高德 navigated, played stop by stop out of its own plan - every
     * leg's card and live data as 高德 sends them, at a tenth of the milestones' times - so the whole
     * trip can be seen without riding it. 高德's own payloads are held back while it plays; nothing
     * here starts a navigation in 高德.
     */
    private fun simulate() {
        val caps = lastCaps ?: run {
            Xp.log(TAG + "sim: no trip to play")
            return
        }
        val plan = plans.firstOrNull { AmapTransitEntity.fits(it, caps) }
        val segs = plan?.optJSONArray("segmentlist")
        val dest = plan?.optJSONObject("epoi")?.optString("name")?.trim().orEmpty().ifEmpty { "目的地" }
        val n = caps.length()
        val steps = ArrayList<Pair<Long, () -> Unit>>()
        var t = 0L
        fun at(after: Long, f: () -> Unit) {
            t += after
            steps.add(t to f)
        }
        simulating = true
        milestones.scale = 0.1
        at(0) {
            dismissAll("sim")
            navigating = true
            tripId = "gaode-pt-sim-" + System.currentTimeMillis()
        }
        var ride = 0
        var lastSubwayRide = false
        for (i in 0 until n) {
            val cap = caps.optJSONObject(i) ?: continue
            if (AmapTransitEntity.walking(cap)) {
                val to = (ride until (segs?.length() ?: 0)).firstNotNullOfOrNull {
                    segs!!.optJSONObject(it)?.optJSONObject("on_station")?.optString("name")?.trim()
                        ?.takeIf { s -> s.isNotEmpty() }
                } ?: dest
                // After a subway with only walking left, its 到站 holds for 30 s (at a tenth).
                val wait = if (lastSubwayRide && (i + 1 until n).all { walking(caps, it) }) 33_000L else 4_000L
                at(3_000) {
                    take(RIDE_BIZ, simCard(caps, i, "步行至 $to", listOf("步行", "前往", to), 1, 0.5).toString())
                    take(LIVE_BIZ, simLive(i, "", 0))
                }
                t += wait - 3_000
                lastSubwayRide = false
                continue
            }
            val seg = segs?.optJSONObject(ride)
            val line = cap.optString("text").trim()
            val stops = ArrayList<String>()
            seg?.optJSONObject("on_station")?.optString("name")?.trim()?.let { stops.add(it) }
            val via = seg?.optJSONArray("via_st_list")
            for (k in 0 until (via?.length() ?: 0)) stops.add(via!!.optJSONObject(k)?.optString("name")?.trim().orEmpty())
            val off = seg?.optJSONObject("off_station")?.optString("name")?.trim().orEmpty()
            val exit = seg?.optJSONObject("outport")?.optString("name")?.trim().orEmpty()
            val later = (i + 1 until n).any { !walking(caps, it) }
            val total = if (stops.isEmpty()) 3 else stops.size
            for (remain in total downTo 1) {
                val stop = stops.getOrElse(total - remain) { "" }
                val items = arrayListOf("${remain}站", "后", " · ", off)
                if (exit.isNotEmpty() && !later) items.add("($exit)")
                items.add(if (later) "换乘" else "出站")
                at(if (remain == total) 3_000 else 5_000) {
                    take(LIVE_BIZ, simLive(i, stop, remain, seg?.optString("busid")?.trim().orEmpty()))
                    take(RIDE_BIZ, simCard(caps, i, "乘坐 $line", items, remain,
                        (total - remain).toDouble() / total).put("mainText", line).toString())
                }
            }
            t += 2_000
            lastSubwayRide = subway(caps, i, plan)
            ride++
        }
        at(3_000) {
            take(RIDE_BIZ, JSONObject().put("cardData", JSONObject().put("title", "已到达 $dest")
                .put("arrived", true)).toString())
            channel(true)
        }
        at(32_000) {
            simulating = false
            milestones.scale = 1.0
            Xp.log(TAG + "sim: done")
        }
        Xp.log(TAG + "sim: " + n + " legs, plan " + (plan != null) + ", " + t / 1000 + " s")
        for ((time, f) in steps) worker.postDelayed({ f() }, time)
    }

    private fun simCard(caps: JSONArray, i: Int, title: String, items: List<String>, remain: Int,
                        persent: Double): JSONObject {
        val words = JSONArray()
        for (w in items) words.put(JSONObject().put("text", w))
        return JSONObject().put("cardData", JSONObject()
            .put("title", title).put("arrived", false).put("offRoute", false)
            .put("titleItems", words).put("planData", caps)
            .put("location", JSONObject().put("index", i).put("persent", persent)
                .put("remainStations", remain)))
    }

    private fun simLive(i: Int, stop: String, remain: Int, line: String = ""): String {
        // At the stop a ride boards at, the next two trains, as 高德 sends them for a subway.
        val times = JSONArray()
        if (line.isNotEmpty()) times.put(JSONObject().put("lineId", line).put("tripTime", JSONArray()
            .put(JSONObject().put("mainTitle", "3分钟").put("orderTiptext", "第 1 辆"))
            .put(JSONObject().put("mainTitle", "9分钟").put("orderTiptext", "第 2 辆"))))
        val data = JSONObject()
            .put("arriveRemind", JSONObject().put("curStopName", stop).put("remainStopNum", remain)
                .put("tipType", 4))
            .put("locationData", JSONObject().put("groupIndex", i))
            .put("subway", times)
            .put("realtime", JSONObject())
        return JSONObject().put("datas", JSONArray().put(JSONObject().put("type", RIDE_TYPE)
            .put("data", data)).toString()).toString()
    }

    private fun raw(json: String) {
        val o = try {
            JSONObject(json)
        } catch (t: Throwable) {
            Xp.log(TAG + "raw: not JSON: $t")
            return
        }
        when {
            o.has("cardData") -> take(RIDE_BIZ, json)
            o.has("datas") -> take(LIVE_BIZ, json)
            else -> Xp.log(TAG + "raw: neither a card nor a live payload")
        }
    }

    fun describe(): String {
        val sb = StringBuilder("transit: navigating=").append(navigating)
            .append(" leg=").append(milestones.legAt)
            .append(" status=").append(lastStatus.ifEmpty { "-" })
            .append(" kind=").append(lastKind.ifEmpty { "-" })
            .append(" plans=").append(plans.size)
            .append(" fits=").append(capsules?.let { caps -> plans.any { AmapTransitEntity.fits(it, caps) } })
            .append(" hold=").append(milestones.hold?.let { it.status + "@" + it.leg } ?: "-")
            .append(" final=").append(if (finalShown) "shown" else if (finalPending()) "pending" else "-")
            .append(" walkIsland=").append(walkIslandUp())
            .append(" live=").append(lastSent != null)
        sb.append('\n').append(AmapTransitIsland.describe())
        sb.append('\n').append(AmapOppoBridge.describe())
        val caps = capsules
        if (caps != null) {
            sb.append("\ncapsules: ").append((0 until caps.length()).joinToString(" | ") {
                val c = caps.optJSONObject(it)
                if (c == null || AmapTransitEntity.walking(c)) "walk" else c.optString("text")
            })
        }
        val plan = plans.firstOrNull()
        val segs = plan?.optJSONArray("segmentlist")
        for (i in 0 until (segs?.length() ?: 0)) {
            val s = segs!!.optJSONObject(i) ?: continue
            sb.append("\nplan[").append(i).append("] ").append(s.optString("bus_key_name")).append(": ")
                .append(s.optJSONObject("on_station")?.optString("name")).append(" → ")
                .append(s.optJSONObject("off_station")?.optString("name"))
                .append(" (").append(s.optJSONArray("via_st_list")?.length() ?: 0).append(" between)")
        }
        return sb.toString()
    }

    private fun array(v: Any?): JSONArray? = when (v) {
        is JSONArray -> v
        is String -> runCatching { JSONArray(v) }.getOrNull()
        else -> null
    }

    /**
     * Every payload the trip's two channels carried, kept whole by its shape, and when each came:
     * what a ride is read back from (`AMAPPROBE --ez max true`, `--ez events true`). 高德's process
     * is the only place they ever are - nothing is written to disk.
     */
    object Ledger {
        private const val MAX_KINDS = 16
        private const val MAX_CHARS = 8000
        private const val MAX_EVENTS = 90
        private val startAt = SystemClock.uptimeMillis()
        private val kinds = LinkedHashMap<String, String>()
        private val events = ArrayList<String>()

        fun record(name: String, biz: Int?, text: String?) {
            val at = (SystemClock.uptimeMillis() - startAt) / 1000.0
            val key = "$biz $name " + shape(text)
            synchronized(this) {
                if (text != null && text.length > 2) {
                    kinds.remove(key)
                    val cut = if (text.length > MAX_CHARS) text.take(MAX_CHARS) + "…(+" +
                        (text.length - MAX_CHARS) + ")" else text
                    kinds[key] = String.format("%.1fs %dB %s", at, text.length, cut)
                    while (kinds.size > MAX_KINDS) kinds.remove(kinds.keys.first())
                }
                events.add(String.format("%.1fs %s %dB", at, key, text?.length ?: 0))
                while (events.size > MAX_EVENTS) events.removeAt(0)
            }
        }

        /** A payload's kind: a `datas` by its types, a card by its keys. */
        private fun shape(text: String?): String {
            if (text == null || text.length < 64 || (!text.contains("datas") && !text.contains("cardData"))) {
                return "text:" + text.orEmpty().take(24)
            }
            return try {
                val o = JSONObject(text)
                val datas = when (val d = o.opt("datas")) {
                    is JSONArray -> d
                    is String -> runCatching { JSONArray(d) }.getOrNull()
                    else -> null
                }
                if (datas != null) {
                    "datas:" + (0 until datas.length()).joinToString(",") {
                        datas.optJSONObject(it)?.optInt("type", -1)?.toString() ?: "?"
                    }
                } else {
                    val c = o.optJSONObject("cardData")
                    "card:" + (c ?: o).keys().asSequence().sorted().joinToString(",")
                }
            } catch (t: Throwable) {
                "?"
            }
        }

        fun dump(): String = synchronized(this) {
            if (kinds.isEmpty()) "ledger: nothing yet"
            else "ledger:" + kinds.entries.joinToString("") { "\n  " + it.key + "  " + it.value }
        }

        fun eventsDump(): String = synchronized(this) {
            if (events.isEmpty()) "events: nothing yet" else "events:" + events.joinToString("") { "\n  $it" }
        }
    }
}
