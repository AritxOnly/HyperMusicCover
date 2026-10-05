package com.os4.musiccover

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 高德's half of the lock screen's bus and subway page: where ColorOS 17 hears about a transit
 * navigation, stood in for here.
 *
 * On ColorOS 17 高德 does not draw this page. Its script hands the navigation's state to a
 * "device" (OppoIntelligentCard, LiveCardOppoIntelligentTemplate in its logs; class il3 in
 * 17.00.0.2005), which passes it as JSON to a content provider:
 *   - authority "IntelligentIntent" - SceneService's IntelligentIntentProvider;
 *   - call("queryFeature", "querySupportIntent", {intentName}) first, and the device only counts
 *     as there when the answer's result is {"code":0,"data":"{\"querySupportIntent\":true}"};
 *   - then call("shareIntent", null, {intentData}) for every new state, intentData being
 *     {intentName, intentVersion, identifier, timestamp, serviceId{}, intentAction{actionType,
 *     actionStatus...}, intentEntity{...}}; deleteIntent / deleteEntity when it is over.
 * The transit intent is com.autonavi.minimap#Navigation.NotifyPublicTransportStatus, and its
 * intentEntity is SceneService's GaoDePtIntentEntity: status (the milestone, 1-7), naviInfo (one
 * item per leg, the current one isCurrent, with on_station, off_station, via_st_list and their
 * coordinates, remainStations, the line's name and colours), destCitycode, exitName, guideInfo.
 * SceneService builds its card out of that (com.oplus.sdp.ya.b); AmapTransitScene draws it here.
 *
 * Nothing answers that authority on HyperOS, so 高德's acquireUnstableContentProviderClient
 * returns null and the device reports itself unsupported. Here the acquire is answered with a
 * client for a provider every app may reach (Settings') and the calls on that one client are
 * answered in this process, the way SceneService answers them, without ever reaching Settings.
 * A phone with a real IntelligentIntent provider keeps it: only a null acquire is stood in for.
 *
 * HyperOS gives 高德 a focus island only for walking and cycling, so the ride gets one of its own
 * here as well (AmapTransitIsland), posted as 高德 with every state passed on.
 *
 * Whether 高德's script picks this device on a phone that is not an OPPO is decided in its
 * script (encrypted, assets/ajx.bundle/bundles.oajx), not in its Java; the probe says what
 * happened: how many times 高德 asked for the authority, what it queried, what it shared.
 */
internal object AmapTransitShare {

    private const val TAG = "MCAmap: transit: "
    private const val AUTHORITY = "IntelligentIntent"
    /** Every app may reach it, and nothing below ever does. */
    private const val STAND_IN = "settings"
    const val INTENT_NAME = "Navigation.NotifyPublicTransportStatus"
    private const val SYSUI = "com.android.systemui"
    private const val SYSUI_PROBE = "com.os4.musiccover.PROBE"

    /** SceneService's CallResult codes: 0 success; what it answers an unknown method with. */
    private const val CODE_OK = 0
    private const val CODE_UNSUPPORTED = 1003

    /** How often an unchanged state is passed on anyway, so SystemUI knows the navigation is
     * still on even when 高德 shares nothing new between two stations. */
    private const val KEEPALIVE_MS = 60_000L

    /**
     * How long the card that says the trip has arrived stays up, which is ColorOS's own figure.
     * SceneService gives its arrival card a 30-second auto-dismiss - both places that build one
     * pass 30000 (`GaoDePtNaviIntentHandler.showFinalDestCard` outright, `GaoDePtNaviSceneRouter.h`
     * as the terminal status's delay) - where every other milestone is left half an hour of
     * silence. The card is not dropped when the ride ends, either: `GaoDePtDismissHandler` returns
     * on `shouldIgnoreDeleteIntent` while one is due, so 高德 deleting the intent does not take it
     * down. A ride that has arrived therefore comes back up, and only its not leaving was wrong
     * here - ours gets the same 30 seconds and is taken down, instead of waiting out SystemUI's
     * own ten-minute silence.
     */
    private const val ARRIVED_MS = 30_000L

    /** Fires the arrival card's own takedown, armed while one is up and cancelled by any other
     * state of the same ride. */
    private val arriving = Handler(Looper.getMainLooper())
    private val takeArrivalDown = Runnable { clear("arrived") }

    /** Payloads kept whole in the ledger, and how long one may be before it is cut. */
    private const val MAX_LEDGER = 16
    private const val MAX_LEDGER_CHARS = 8000
    /** Sends kept in the event log, which is what says how often 高德 pushes. */
    private const val MAX_EVENTS = 90
    /**
     * What a line whose colour 高德 has not given is drawn in, which is ColorOS's own blue:
     * SceneService's `ya.b.L` answers `#4A86FF` for a colour it cannot read, and `ya.b.c` uses the
     * same one for a waiting card whose line named none. (This was a neutral grey, so an
     * uncoloured line was drawn in no line's colour at all; the ROM draws it in the default one.)
     */
    private const val NEUTRAL = "#4a86ff"
    /** Every send, oldest first: "12.3s 103 sendMessage 892B", for the interval between them. */
    private val events = ArrayList<String>()
    private val startAt = SystemClock.uptimeMillis()

    /** The clients handed out for the authority; weak, so a released one is forgotten. */
    private val ours: MutableSet<ContentProviderClient> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    private const val WEARABLE = "com.amap.bundle.wearable.ajx.NativesModuleWearable"
    /** The script's last call of each kind and bizType, oldest first (watchWearable). */
    private val wearable = LinkedHashMap<String, String>()
    /** The device layer's last payloads per bizType and method, oldest first (watchWearable). */
    private val payloads = LinkedHashMap<String, String>()
    /**
     * Every payload the device layer carried, whole: "bizType method" -> "12.3s 1234B {json}".
     * The ride's own data is what this is for, so nothing here is cut without saying so.
     */
    private val ledger = LinkedHashMap<String, String>()
    private val ledgerAt = LinkedHashMap<String, Long>()

    /** 高德's device layer, the one type behind every vendor card. */
    private const val WEARABLE_SERVICE = "com.amap.bundle.wearable.WearableService"
    /** The OPPO intelligent card's device class: il3 in 17.00.0.2005, LiveCardOppoIntelligentTemplate. */
    private const val OPPO_CARD = "il3"
    /** bizType -> device config factory. Xn0.a(bizType, data) is where a channel's devices come from. */
    private const val BIZ_TABLE = "xn0"
    /** The device-config holder a channel is built from: wn0(deviceId). */
    private const val DEVICE_CONFIG = "wn0"
    /** 高德's own phone-type answer (com.feather.support.RomUtil): watched, then left alone. */
    private const val ROM_UTIL = "com.feather.support.RomUtil"
    /** The channel 高德's own ride card comes down: the OPPO AOD card's bizType. */
    private const val RIDE_BIZ = 103
    /** The channel 高德's live ride data comes down (amap_glass). */
    private const val LIVE_BIZ = 113
    /** The `type` of the ride's own entry in a 113 payload; the channel carries other kinds too. */
    private const val RIDE_TYPE = 25
    /**
     * The `type` of the ride's whole route plan, also on 113: `segmentlist[]` with each leg's
     * `on_station`, `via_st_list` and `off_station` (names, coordinates, `is_trans`), its line's
     * name and colour and its exits. This is where the stop the ride is coming to comes from.
     */
    private const val PLAN_TYPE = 24
    /** Real ride cards turned into the island's entity (ride). */
    private val cards = AtomicInteger()
    /** The plan channel's last card and the live channel's last data, and whether a ride began. */
    @Volatile private var planCard: JSONObject? = null
    @Volatile private var plan: JSONArray? = null
    /**
     * Each line's own colour, by name, kept beside the plan: 高德 sends it on the plan's capsules
     * (7号线's #86B81C), and a plan rebuilt without them - the simulation's, say - must not cost
     * the ride the colour the line actually has.
     */
    private val lineColors = HashMap<String, String>()
    /** The lines a simulation is using, which the real plan replaces on its next card. */
    @Volatile private var simPlan: JSONArray? = null
    @Volatile private var live: JSONObject? = null
    @Volatile private var liveAt = 0L
    /** The ride's own route plan (the 113 channel's `type 24`), for the stations it names. */
    @Volatile private var route: JSONObject? = null

    /**
     * The stop the leg being ridden lets you out at, as the last card to name one named it, and
     * the line that card was for.
     *
     * SceneService reads this from the entity's `off_station` on every payload of the ride
     * (`ya.b.P`), so its arrival card always knows which stop was arrived at. 高德 names the same
     * stop in the card's sentence while the ride is moving (「3站后 · 南村万博(B口)出站」) and
     * stops naming it once the ride has arrived - 「已到达 汕黄牛.牛肉海鲜自助」 names the place
     * the walk ends at, not the station the ride ended at. Reading the arrival card on its own
     * therefore gave the restaurant instead of 南村万博, so the last stop the leg named is kept.
     */
    @Volatile private var alightStop: String = ""
    @Volatile private var alightLine: String = ""

    /**
     * The exit 高德 sends you out of at that stop (「B口」), as the last card to name one named it,
     * and the line that card was for. An arrival card names the stop and the exit on ColorOS
     * (`entity.exitName` comes down with every payload); 高德's own arrival card names neither, so
     * both are remembered from the cards that do.
     */
    @Volatile private var exitStop: String = ""
    @Volatile private var exitLine: String = ""

    /**
     * Whether the trip's opening walk has already been handed to 高德's walking navigation, so a
     * second card for the same walk does not start it over. Let go with the rest of the ride.
     */
    @Volatile private var walked = false
    @Volatile private var riding = false

    /**
     * Whether this navigation opens with a walk to the first stop, as 高德's own plan's first
     * capsule says. Read off the card 高德 sends when the navigation starts, which is the capsules
     * and nothing else.
     */
    @Volatile private var opening = false

    /**
     * Whether 高德's ride channel (103) is open, which is what a navigation being under way looks
     * like from here. The script begins it when the trip's navigation starts - the 「开始导航」 -
     * and ends it when that navigation is left, so its two edges are the navigation's own
     * beginning and end. That is the one thing the cards cannot say: a second navigation of the
     * same trip sends the same cards as the first, in the same order, so nothing in them marks a
     * new one. Measured on 2026-10-05: the route page opens 113 alone, and 103 arrives only on
     * 「开始导航」; leaving fires `bizEnd(103)`, entering again fires `bizBegin(103)`.
     */
    @Volatile private var live103 = false
    /** How many sendMessages each bizType has taken since this process started. */
    private val sends = java.util.concurrent.ConcurrentHashMap<Int, AtomicInteger>()
    /** The OPPO intelligent card's channel, the one 高德's Java opens for any phone that asks. */
    private const val OPPO_BIZ = 10200
    private const val OPPO_DEVICE = "thid_sdk_template_oppo_intelligent"
    /** What the card's device needs before it will reach the provider at all (il3.isSupport). */
    private const val PROVIDER = "IntelligentIntent"
    private const val INTENT = "Navigation.NotifyPublicTransportStatus"
    /** The begin-data that sets those two on the card; the same one an OPPO's script sends. */
    private const val OPPO_BEGIN_DATA =
        "{\"authority\":\"$PROVIDER\",\"intentName\":\"$INTENT\"}"
    /** Set while [bridgeOppo] is inside the service, so its own call is not bridged again. */
    private val bridging = java.util.concurrent.atomic.AtomicBoolean(false)
    /** The bizTypes the bridge has opened OPPO's channel for, and how it went. */
    private val bridged = LinkedHashMap<String, String>()
    @Volatile private var oppoCard: Class<*>? = null
    /** Device-config holder the table handed out for 10200, the one 高德 itself would build. */
    @Volatile private var oppoConfig: Any? = null
    /** 高德's own phone-type answers here, for the probe. */
    @Volatile private var rom = ""
    /**
     * Whether 高德 is told it is on an OPPO phone. Off by default: the OPPO card's channel carries
     * the ride's `via_st_list`, which nothing else here has, but answering OPPO is the phone
     * lying about itself to a whole app, so it is a switch the probe turns on rather than
     * something the module does on its own.
     */
    @Volatile private var spoofOppo = false

    private val acquires = AtomicInteger()
    private val queries = AtomicInteger()
    private val shares = AtomicInteger()
    private val deletes = AtomicInteger()
    @Volatile private var lastQuery: String? = null
    @Volatile private var lastShare: String? = null
    @Volatile private var lastShareAt = 0L
    /** What SystemUI was last told, and when: an entity, or null once it ended. */
    @Volatile private var lastSent: String? = null
    @Volatile private var lastSentAt = 0L

    fun handle(cl: ClassLoader) {
        AmapTransitIsland.handle()
        watchWearable(cl)
        watchRom(cl)
        try {
            for (name in arrayOf("acquireUnstableContentProviderClient", "acquireContentProviderClient")) {
                Xp.hookAll(ContentResolver::class.java, name) { chain ->
                    val out = chain.proceed()
                    val asked = chain.args.getOrNull(0)
                    if (out != null || asked !is String || asked != AUTHORITY) return@hookAll out
                    acquires.incrementAndGet()
                    // Re-enters this hook with the stand-in's authority, which passes straight on.
                    val client = (chain.thisObject as ContentResolver)
                        .acquireUnstableContentProviderClient(STAND_IN)
                    if (client == null) {
                        Xp.log(TAG + "no stand-in client for $AUTHORITY")
                        return@hookAll null
                    }
                    ours.add(client)
                    Xp.log(TAG + "$AUTHORITY asked for (#${acquires.get()}), answered here")
                    client
                }
            }
            Xp.hookAll(ContentProviderClient::class.java, "call") { chain ->
                if (chain.thisObject !in ours) return@hookAll chain.proceed()
                val a = chain.args
                // call(method, arg, extras), or call(authority, method, arg, extras).
                val off = if (a.size >= 4) 1 else 0
                answer(a.getOrNull(off) as String?, a.getOrNull(off + 1) as String?,
                    a.getOrNull(off + 2) as Bundle?)
            }
            Xp.log(TAG + "standing in for $AUTHORITY")
        } catch (t: Throwable) {
            Xp.log(TAG + "hooks failed: " + t)
        }
    }

    private fun answer(method: String?, arg: String?, extras: Bundle?): Bundle {
        val result = try {
            when (method) {
                "queryFeature" -> query(arg, extras)
                "shareIntent" -> share(extras?.getString("intentData"))
                "deleteIntent", "deleteEntity" -> delete(method, extras)
                "getSid" -> result(CODE_OK, null)
                else -> {
                    Xp.log(TAG + "unknown call $method")
                    result(CODE_UNSUPPORTED, null)
                }
            }
        } catch (t: Throwable) {
            Xp.log(TAG + "$method failed: $t")
            result(CODE_UNSUPPORTED, null)
        }
        return Bundle().apply { putString("result", result.toString()) }
    }

    /** SceneService's QueryFeatureUtil.f, for the one intent this stands in for. */
    private fun query(feature: String?, extras: Bundle?): JSONObject {
        queries.incrementAndGet()
        val data = JSONObject()
        when (feature) {
            "querySupportIntent" -> {
                val name = extras?.getString("intentName").orEmpty()
                data.put(feature, name == INTENT_NAME)
                lastQuery = name
            }
            "querySupportIntentByPackage" -> {
                data.put(INTENT_NAME, true)
                lastQuery = feature
            }
            "enableIntelligentIntent" -> {
                data.put(feature, true)
                lastQuery = feature
            }
            else -> {
                lastQuery = feature
                Xp.log(TAG + "unknown feature $feature")
                return result(CODE_UNSUPPORTED, null)
            }
        }
        Xp.log(TAG + "queryFeature $feature ${extras?.getString("intentName").orEmpty()} -> $data")
        return result(CODE_OK, data.toString())
    }

    private fun share(intentData: String?): JSONObject {
        if (intentData.isNullOrEmpty()) return result(CODE_UNSUPPORTED, null)
        shares.incrementAndGet()
        lastShareAt = SystemClock.uptimeMillis()
        val json = JSONObject(intentData)
        val name = json.optString("intentName")
        ledger("shareIntent", listOf(OPPO_BIZ, intentData))
        if (name != INTENT_NAME) {
            Xp.log(TAG + "shareIntent for '$name', not ours: " + intentData.take(200))
            return result(CODE_OK, null)
        }
        val entity = json.optJSONObject("intentEntity") ?: return result(CODE_UNSUPPORTED, null)
        // The route's whole polyline: the page never draws it, and it is most of the bytes.
        entity.remove("path")
        val action = json.optJSONObject("intentAction")?.optString("actionType").orEmpty()
        lastShare = "status=" + entity.optString("status") + " action=" + action
        tell(entity.toString(), action)
        return result(CODE_OK, null)
    }

    private fun delete(method: String, extras: Bundle?): JSONObject {
        deletes.incrementAndGet()
        Xp.log(TAG + "$method " + extras?.keySet()?.joinToString())
        // 高德 deleting the intent is the ride being over: everything of it is let go, or the
        // state survives the end and the next payload - a late one, or the ride's own trailing
        // updates - puts the card back up with nothing riding.
        clear(method)
        return result(CODE_OK, null)
    }

    private fun result(code: Int, data: String?): JSONObject = JSONObject()
        .put("code", code)
        .put("message", if (code == CODE_OK) "success" else "unsupported")
        .apply { if (data != null) put("data", data) }

    /** Passes the state on to SystemUI; the same one again only once KEEPALIVE_MS has gone. */
    private fun tell(entity: String?, action: String) {
        val now = SystemClock.uptimeMillis()
        if (entity != null && entity == lastSent && now - lastSentAt < KEEPALIVE_MS) return
        lastSent = entity
        lastSentAt = now
        send(entity, action)
    }

    private fun send(entity: String?, action: String) {
        val ctx: Context = AmapImmerse.context() ?: run {
            Xp.log(TAG + "no context to tell SystemUI")
            return
        }
        try {
            val i = Intent(SYSUI_PROBE).setPackage(SYSUI)
                .putExtra("op", "transit")
                .putExtra("src", "amap")
                .putExtra("action", action)
            if (entity == null) i.putExtra("do", "end") else i.putExtra("json", entity)
            ProbeGuard.send(ctx, i)
            // HyperOS has no island for a ride, so the page would have nothing to open from.
            AmapTransitIsland.update(ctx, entity)
        } catch (t: Throwable) {
            Xp.log(TAG + "tell failed: $t")
        }
    }

    /**
     * `AMAPPROBE --es transit demo|end`: a made-up ride (AmapTransitScene.DEMO) passed on as if
     * 高德 had shared it - SystemUI's page and the island both - or its end; for trying the whole
     * way from the island to the page without riding a train.
     */
    fun probe(what: String): String {
        when (what) {
            "demo" -> tell(JSONObject(AmapTransitScene.DEMO).toString(), "demo")
            "end" -> tell(null, "demo")
            "进站", "enter" -> simulate("进站")
            "乘车", "ride" -> simulate("乘车")
            "换乘", "transfer" -> simulate("换乘")
            "到达", "arrive" -> simulate("到达")
            // `AMAPPROBE --es transit raw --es json '<the payload 高德 sent>'`: the payload itself,
            // back through the same `ride()` a real one goes through. A card's shape that arrives
            // from a real ride is the only way to try the reading of it.
            "raw", "payload" -> raw(json)
        }
        return describe()
    }

    /** One payload 高德 sent, taken as if it had just arrived on its channel. */
    private fun raw(json: String) {
        if (json.isEmpty()) {
            Xp.log(TAG + "raw: nothing to take")
            return
        }
        val o = try {
            JSONObject(json)
        } catch (t: Throwable) {
            Xp.log(TAG + "raw: not JSON: " + t)
            return
        }
        // The whole message, as `sendMessage` hands it over: either a 103 card or a 113 payload,
        // with the channel taken from `bizType` when the message carries one.
        val biz = o.optInt("bizType", -1)
        val card = o.optJSONObject("cardData")
        if (card != null) {
            Xp.log(TAG + "raw: 103 card " + card.optString("title"))
            ride(RIDE_BIZ, o.toString())
            return
        }
        if (o.optString("datas").isNotEmpty()) {
            Xp.log(TAG + "raw: 113 payload")
            ride(LIVE_BIZ, o.toString())
            return
        }
        Xp.log(TAG + "raw: neither a card nor a live payload, bizType=" + biz)
    }

    /** The payload a `raw` probe carried in, on its way to [raw]. */
    @Volatile var json: String = ""

    /**
     * `AMAPPROBE --es transit 进站|乘车|换乘|到达`: the ride card 高德 sends for that stage of the
     * trip, made here and fed through the same `ride()` a real card goes through, so the whole
     * way to the island can be tried without a train. The card is 高德's own shape (titleItems,
     * subTitleItems, planData, location), and the lines are the plan's own.
     */
    private fun simulate(stage: String) {
        // The simulation's own plan, built from the real one where it is there: the capsules carry
        // each line's own colour (7号线's #86B81C, say), and a capsule with only a name would make
        // the badge fall back to a colour the line does not have.
        val lines = ArrayList<String>()
        val colors = HashMap<String, String>()
        for (i in 0 until (plan?.length() ?: 0)) {
            val p = plan!!.optJSONObject(i) ?: continue
            val name = p.optString("name")
            lines.add(name)
            val c = p.optString("color").trim()
            if (c.startsWith("#")) colors[name] = c
        }
        // Whatever the plan already knew about a line's colour, the rebuilt capsules keep. A plan
        // that arrives without its capsules' colours (高德's own does carry them) must not be the
        // reason the badge shows a colour the line does not have.
        synchronized(lineColors) {
            for (l in lines) if (!colors.containsKey(l)) lineColors[l]?.let { colors[l] = it }
            // The line the real card named is the one being simulated when the plan is its own.
            for (i in 0 until (planCard?.optJSONArray("planData")?.length() ?: 0)) {
                val o = planCard!!.optJSONArray("planData")!!.optJSONObject(i) ?: continue
                val bg = o.optString("bgColor").trim()
                val name = o.optString("text").trim()
                if (name.isNotEmpty() && bg.startsWith("#")) {
                    lineColors[name] = bg
                    colors[name] = bg
                }
            }
        }
        if (lines.isEmpty()) lines.addAll(listOf("7号线", "3号线", "番29路"))
        val at = when (stage) {
            "进站" -> 0
            "乘车" -> 0
            "换乘" -> 1
            else -> lines.size - 1
        }
        val line = lines[at]
        val next = if (at + 1 < lines.size) lines[at + 1] else ""
        val station = if (stage == "换乘") "汉溪长隆" else "大学城南"
        val action = if (stage == "到达") "已到达" else stage
        val card = JSONObject()
            .put("title", if (stage == "进站") "步行至 $station 地铁站" else "$action $line")
            .put("arrived", stage == "到达")
            .put("mainText", line)
            .put("subText", if (stage == "进站") "$station(E口)" else station)
            .put("remainMessage", if (stage == "到达") "已到达" else "约12分钟·09:31到达")
            .put("titleItems", JSONArray()
                .put(JSONObject().put("text", station))
                .put(JSONObject().put("text", "(E口)"))
                .put(JSONObject().put("text", action)))
            .put("subTitleItems", JSONArray()
                .put(JSONObject().put("text", line))
                .put(JSONObject().put("text", "(美的大道方向)")))
            .put("location", JSONObject().put("index", at).put("persent", 0)
                .put("remainStations", if (stage == "到达") 0 else 3))
        val plans = JSONArray()
        card.put("planData", plans)
        for (l in lines) {
            val c = JSONObject().put("text", l)
            colors[l]?.let { c.put("bgColor", it) }
            plans.put(c)
        }
        // The simulation's own lines, so a stage without a next one cannot overwrite the plan
        // 高德 gave (lineAt reads this first, and the real channel clears it again).
        simPlan = rides(card.optJSONArray("planData"))
        Xp.log(TAG + "simulate $stage: $line" + (if (next.isEmpty()) "" else " -> $next"))
        ride(RIDE_BIZ, JSONObject().put("cardData", card).toString())
    }

    /** SystemUI started over: the last state again, if the navigation has not ended since. */
    fun resend() {
        val entity = lastSent ?: return
        lastSentAt = SystemClock.uptimeMillis()
        send(entity, "resend")
    }

    /**
     * What 高德's own phone-type answer says on this phone. Whether the OPPO card's channel is
     * opened at all (bizType 10200, the one that carries a real `intentEntity` - and with it the
     * ride's `via_st_list`, which nothing else on a Xiaomi phone has) is decided in 高德's
     * encrypted script, not in its Java: its Java holds that channel back for no phone. The only
     * handle on that decision is what the script is told about the phone, and this is the class
     * 高德 answers that with. Read, not spoofed, until the probe says what it says.
     */
    private fun watchRom(cl: ClassLoader) {
        try {
            val cls = Xp.findClass(ROM_UTIL, cl)
            val out = StringBuilder()
            for (m in cls.declaredMethods.sortedBy { it.name }) {
                if (m.parameterTypes.isNotEmpty()) continue
                if (!java.lang.reflect.Modifier.isStatic(m.modifiers)) continue
                // The predicates, and 高德's own two names - not every no-arg method a class
                // happens to have, invoked inside 高德's own process to build a probe line.
                val pred = m.name.startsWith("is") &&
                    (m.returnType == java.lang.Boolean.TYPE || m.returnType == java.lang.Boolean::class.java)
                if (!pred && m.name != "getName" && m.name != "getVersion") continue
                m.isAccessible = true
                val v = try {
                    m.invoke(null)?.toString() ?: "null"
                } catch (t: Throwable) {
                    "!"
                }
                if (out.isNotEmpty()) out.append(' ')
                out.append(m.name).append('=').append(v)
            }
            rom = if (out.isEmpty()) "no static methods" else out.toString()
            Xp.log(TAG + "rom: " + rom)
        } catch (t: Throwable) {
            rom = "not visible: " + t
            Xp.log(TAG + "rom: " + rom)
        }
        // The one answer that decides whether the script opens the OPPO card's channel. Answered
        // only while the probe has asked for it; the rest of the class is left exactly as it is.
        try {
            val cls = Xp.findClass(ROM_UTIL, cl)
            Xp.hookAll(cls, "isOppo") { chain -> if (spoofOppo) true else chain.proceed() }
            Xp.log(TAG + "isOppo hooked (spoof " + spoofOppo + ")")
        } catch (t: Throwable) {
            Xp.log(TAG + "isOppo not hooked: " + t)
        }
    }

    /** `AMAPPROBE --es oppo true|false`: answer 高德's `isOppo` that way, or not at all. */
    fun spoof(on: Boolean) {
        spoofOppo = on
        Xp.log(TAG + "isOppo spoof " + on)
    }

    /**
     * What 高德's script asks of its device layer (NativesModuleWearable, the AJX module behind
     * every OPPO / vivo / Honor / Xiaomi card): which bizTypes it begins and what it sends them.
     * The OPPO card is bizType 10200 (thid_sdk_template_oppo_intelligent, il3) and nothing in
     * 高德's Java holds it back on another phone; whether the script begins it is the question,
     * and a ride with this on answers it.
     *
     * Every string argument is kept whole - the ride's own JSON is the point of this - and the
     * same calls are hooked once more on the service underneath, where a channel that the AJX
     * module never reaches (a page's own begin, say) still shows up.
     */
    private fun watchWearable(cl: ClassLoader) {
        try {
            val module = Xp.findClass(WEARABLE, cl)
            for (name in arrayOf("bizBegin", "bizBeginWithData", "bizEnd", "sendMessage",
                    "sendNotify", "sendLockScreenMessage")) {
                try {
                    Xp.hookAll(module, name) { chain ->
                        record(name, chain.args)
                        if (name == "sendMessage") {
                            val a = chain.args
                            val biz = a.firstOrNull { it is Int } as Int?
                            val text = a.firstOrNull { it is String } as String?
                            if (text != null && (biz == RIDE_BIZ || biz == LIVE_BIZ)) ride(biz, text)
                        }
                        if (name == "bizBegin" || name == "bizBeginWithData" || name == "bizEnd") {
                            val biz = chain.args.firstOrNull { it is Int } as Int?
                            if (biz == RIDE_BIZ) channel(name == "bizEnd")
                        }
                        if (name != "sendMessage" && name != "sendNotify") {
                            Xp.log(TAG + "script: " + describeCall(name, chain.args))
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
        try {
            val service = Xp.findClass(WEARABLE_SERVICE, cl)
            bridgeOppo(service, cl)
            for (name in arrayOf("bizBegin", "bizEnd", "sendMessage", "sendNotify")) {
                try {
                    Xp.hookAll(service, name) { chain ->
                        // Inside the bridge's own call: the plain one, no record, no bridge.
                        if (bridging.get()) return@hookAll chain.proceed()
                        record("svc." + name, chain.args)
                        if (name == "bizEnd") {
                            val biz = chain.args.firstOrNull { it is Int } as Int?
                            val key = "bizBegin($biz)"
                            val ours = synchronized(bridged) {
                                biz != null && biz != OPPO_BIZ && bridged.containsKey(key)
                            }
                            val svc = chain.thisObject
                            chain.proceed()
                            if (ours && svc != null) close(svc, biz!!)
                            return@hookAll null
                        }
                        Xp.log(TAG + "service " + name + " " + describeCall(name, chain.args))
                        chain.proceed()
                    }
                } catch (t: Throwable) {
                    Xp.log(TAG + "service $name not watched: $t")
                }
            }
        } catch (t: Throwable) {
            Xp.log(TAG + "wearable service not watched: $t")
        }
    }

    /**
     * The channel 高德's script never opens here. 高德's Java keeps the OPPO intelligent card
     * (bizType 10200, thid_sdk_template_oppo_intelligent, il3) unconditional - `xn0`'s table maps
     * it and `jl3.getConfig` hands it back for any phone - so a begin for it needs no machine
     * check at all; only the script's decision is missing.
     *
     * Two halves, because the card on its own is a dead end:
     *  - `bizBegin(10200)` is called beside every channel the script does begin, with the begin
     *    data that sets the card's provider and intent (il3.onReceiveBizBeginData) and the intent
     *    itself pre-armed on the instance (isSupport reads both before it will call at all). The
     *    provider it then asks for is this module's own stand-in, so the card is supported here.
     *  - the card's device config is put into the channel's own device list as well (`xn0.a`), so
     *    every payload the script sends for the ride reaches the card too, in 高德's own words.
     * What comes out is whatever 高德's script sends for a bus or subway ride, passed through the
     * same path SceneService uses on an OPPO; the probe reports both halves.
     */
    private fun bridgeOppo(service: Class<*>, cl: ClassLoader) {
        try {
            oppoCard = Xp.findClass(OPPO_CARD, cl)
            hookOppoCard()
        } catch (t: Throwable) {
            Xp.log(TAG + "OPPO card hooks failed: $t")
        }
        try {
            val table = Xp.findClass(BIZ_TABLE, cl)
            for (m in table.declaredMethods) {
                if (m.name != "a" || m.parameterTypes.size != 2 ||
                    m.parameterTypes[0] != Integer.TYPE
                ) continue
                m.isAccessible = true
                Xp.hook(m) { chain ->
                    val out = chain.proceed()
                    // Only a channel the bridge opened, and never 10200's own list.
                    val biz = chain.args.firstOrNull { it is Int } as Int?
                    if (biz != null && biz != OPPO_BIZ && out is MutableList<*>) inject(biz, out)
                    out
                }
            }
        } catch (t: Throwable) {
            Xp.log(TAG + "device table not hooked: $t")
        }
        // The script's own door: the AJX module's begin, on whichever object carries it - here the
        // script's bizBegin never reaches WearableService itself, so the service is called directly.
        try {
            val module = Xp.findClass(WEARABLE, cl)
            val begins = module.declaredMethods.filter {
                (it.name == "bizBegin" || it.name == "bizBeginWithData") &&
                    it.parameterTypes.firstOrNull() == Integer.TYPE
            }
            for (begin in begins) {
                begin.isAccessible = true
                val iface = ifaceOf(begin)
                val data = begin.parameterTypes.getOrNull(1) == String::class.java
                Xp.hook(begin) { chain ->
                    val a = chain.args
                    val biz = a.firstOrNull { it is Int } as Int?
                    val svc = a.firstOrNull { iface.isInstance(it) }
                    if (biz != null && biz != OPPO_BIZ && svc != null) {
                        open(svc, begin, biz, a.getOrNull(1) as? String, data)
                    }
                    chain.proceed()
                }
            }
            Xp.log(TAG + "OPPO channel bridge armed on " + begins.size + " module begin(s)")
        } catch (t: Throwable) {
            Xp.log(TAG + "OPPO channel bridge failed: $t")
        }
    }

    /** The wearable service type the module's begin carries, whatever it is called. */
    private fun ifaceOf(begin: java.lang.reflect.Method): Class<*> =
        begin.parameterTypes.firstOrNull { it.name.contains("earable") && it.isInterface }
            ?: begin.parameterTypes[1]

    /**
     * The card's instance, whichever begin built it: its provider and intent are set before its
     * own isSupport looks at them, so it counts as supported here even though the script never
     * handed it the data an OPPO's script would.
     */
    private fun hookOppoCard() {
        val card = oppoCard ?: return
        try {
            Xp.hookAll(card, "isSupport") { chain ->
                arm(chain.thisObject)
                chain.proceed()
            }
        } catch (t: Throwable) {
            Xp.log(TAG + "card isSupport not hooked: $t")
        }
        try {
            Xp.hookAll(card, "connect") { chain ->
                setBridged("card", "connected")
                Xp.log(TAG + "$OPPO_DEVICE connected, provider=$PROVIDER intent=$INTENT")
                chain.proceed()
            }
        } catch (t: Throwable) {
            Xp.log(TAG + "card connect not hooked: $t")
        }
        // What the card is handed, which is what it forwards: a payload with no intentName in it
        // is one no OPPO card could post, so this is where the script's shape is judged.
        try {
            Xp.hookAll(card, "send") { chain ->
                val a = chain.args
                ledger("card.send", listOf(OPPO_BIZ, a.getOrNull(0)))
                chain.proceed()
            }
        } catch (t: Throwable) {
            Xp.log(TAG + "card send not hooked: $t")
        }
    }

    /** il3's authority (h) and intent (g), the two isSupport refuses without. */
    private fun arm(device: Any?) {
        if (device == null) return
        try {
            Xp.setObjectField(device, "h", PROVIDER)
            Xp.setObjectField(device, "g", INTENT)
        } catch (t: Throwable) {
            Xp.log(TAG + "card not armed: $t")
        }
    }

    /** Puts 10200's own device config into [list], once per channel. */
    private fun inject(biz: Int, list: MutableList<*>) {
        val key = "bizBegin($biz)"
        synchronized(bridged) { if (!bridged.containsKey(key)) return }
        try {
            val cfg = oppoConfig ?: config().also { oppoConfig = it }
            if (cfg == null) {
                setBridged(key, "no 10200 config")
                return
            }
            if (list.contains(cfg)) return
            @Suppress("UNCHECKED_CAST")
            (list as MutableList<Any?>).add(cfg)
            setBridged(key, "in channel, " + list.size + " devices")
            Xp.log(TAG + "$OPPO_DEVICE rides in bizType $biz (" + list.size + " devices)")
        } catch (t: Throwable) {
            setBridged(key, "inject failed " + t)
        }
    }

    /** One fresh wn0 for the OPPO card, from 高德's own table, never the script's list. */
    private fun config(): Any? {
        val table = try {
            Xp.findClass(BIZ_TABLE, oppoCard?.classLoader)
        } catch (t: Throwable) {
            return null
        }
        for (m in table.declaredMethods) {
            if (m.name != "a" || m.parameterTypes.size != 2 ||
                m.parameterTypes[0] != Integer.TYPE
            ) continue
            try {
                m.isAccessible = true
                val out = m.invoke(null, OPPO_BIZ, null) as? List<*> ?: continue
                if (out.isNotEmpty()) return out[0]
            } catch (t: Throwable) {
                Xp.log(TAG + "10200 config not read: $t")
            }
        }
        return null
    }

    /** Opens 10200 beside the channel the script just began, once per bizType. */
    private fun open(svc: Any, begin: java.lang.reflect.Method, biz: Int, data: String?,
                     withData: Boolean) {
        val key = "bizBegin($biz)"
        Xp.log(TAG + "opening $OPPO_DEVICE for bizType $biz (withData=" + withData + ")")
        synchronized(bridged) {
            if (bridged.containsKey(key)) return
            bridged[key] = "opening"
            while (bridged.size > 8) bridged.remove(bridged.keys.first())
        }
        try {
            if (!bridging.compareAndSet(false, true)) return
            try {
                val method = on(svc, "bizBeginWithData")
                val plain = on(svc, "bizBegin")
                val cbType = (method ?: plain)?.parameterTypes?.get(1) ?: return
                // The service drops a begin whose callback is null, so a stand-in of the script's
                // own kind is made; only the card's connection state ever goes to it.
                val cb = java.lang.reflect.Proxy.newProxyInstance(
                    cbType.classLoader, arrayOf(cbType)
                ) { _, m, args ->
                    if (m.name == "callback") setBridged(key, "callback " + args?.firstOrNull())
                    null
                }
                if (method != null) {
                    // The card's provider and intent come from the begin data (il3.onReceiveBizBeginData),
                    // so 高德's own for an OPPO is used; the script's is not that shape.
                    method.invoke(svc, OPPO_BIZ, OPPO_BEGIN_DATA, cb, null)
                } else if (plain != null) {
                    plain.invoke(svc, OPPO_BIZ, cb, null)
                    beginOppoWithData(svc, cb)
                } else {
                    setBridged(key, "no begin on the service")
                    return
                }
            } finally {
                bridging.set(false)
            }
            if (bridged[key] == "opening") setBridged(key, "asked")
            Xp.log(TAG + "bridged $OPPO_DEVICE for bizType $biz -> bizBegin($OPPO_BIZ)")
        } catch (t: Throwable) {
            setBridged(key, "failed " + t)
            Xp.log(TAG + "bridge for $biz failed: $t")
        }
    }

    /** One of the service's own methods, by name, whatever its object is. */
    private fun on(svc: Any, name: String): java.lang.reflect.Method? =
        svc.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.firstOrNull() == Integer.TYPE
        }

    /** il3.onReceiveBizBeginData with the card's two fields, for a data-less begin. */
    private fun beginOppoWithData(svc: Any, cb: Any) {
        val method = on(svc, "bizBeginWithData") ?: return
        method.invoke(svc, OPPO_BIZ, OPPO_BEGIN_DATA, cb, null)
    }

    /** The card's channel goes when the script's does, so it never outlives the ride. */
    private fun close(svc: Any, biz: Int) {
        try {
            val method = on(svc, "bizEnd") ?: return
            if (!bridging.compareAndSet(false, true)) return
            try {
                method.invoke(svc, OPPO_BIZ)
            } finally {
                bridging.set(false)
            }
            synchronized(bridged) { bridged.remove("bizBegin($biz)") }
            Xp.log(TAG + "closed $OPPO_DEVICE with bizType $biz")
        } catch (t: Throwable) {
            Xp.log(TAG + "close for $biz failed: $t")
        }
    }

    private fun setBridged(key: String, what: String) {
        synchronized(bridged) { bridged[key] = what }
    }

    /** The last calls of each kind and bizType and the last payloads of each, capped. */
    private fun record(name: String, args: List<Any?>) {
        val biz = args.firstOrNull { it is Int } as Int?
        val head = "$name($biz)"
        val line = describeCall(name, args)
        val text = args.firstOrNull { it is String && (it as String).isNotEmpty() } as String?
        if (name == "sendMessage" && biz != null) {
            sends.computeIfAbsent(biz) { AtomicInteger() }.incrementAndGet()
        }
        synchronized(wearable) {
            wearable.remove(head)
            wearable[head] = line
            while (wearable.size > 12) wearable.remove(wearable.keys.first())
        }
        if (text != null) {
            synchronized(payloads) {
                payloads.remove(head)
                payloads[head] = line
                while (payloads.size > 8) payloads.remove(payloads.keys.first())
            }
        }
        ledger(name, args)
    }

    /** Every argument of one device-layer call, strings whole. */
    private fun describeCall(name: String, args: List<Any?>): String {
        val biz = args.firstOrNull { it is Int } as Int?
        val sb = StringBuilder(name).append('(').append(biz)
        for (a in args) {
            if (a is Int) continue
            sb.append(", ").append(
                when (a) {
                    null -> "null"
                    is String -> '"' + a + '"'
                    is Boolean -> a.toString()
                    else -> a.javaClass.simpleName + '@' + Integer.toHexString(System.identityHashCode(a))
                }
            )
        }
        return sb.append(')').toString()
    }

    /**
     * The whole payload under the channel it went down, kept per bizType and method. A payload
     * longer than [MAX_LEDGER_CHARS] is cut with its length said, so a schema that runs long is
     * still recognisable; the probe's `max` dump answers with all of them.
     */
    private fun ledger(name: String, args: List<Any?>) {
        val biz = args.firstOrNull { it is Int } as Int?
        val text = args.firstOrNull { it is String && (it as String).length > 2 } as String?
            ?: return
        // One ledger entry per payload *shape*, not per channel: the 113 channel alone carries
        // several kinds of payload (a ride's own `type 25`, walking's 7 and 24), and keying by
        // channel kept whichever arrived last, so a whole ride's worth of the others was never in
        // the ledger to be read.
        val key = "$biz " + name + " " + shape(text)
        val now = SystemClock.uptimeMillis()
        val at = ((now - startAt).toDouble() / 1000.0).toString()
        val cut = if (text.length > MAX_LEDGER_CHARS)
            text.take(MAX_LEDGER_CHARS) + "…(+" + (text.length - MAX_LEDGER_CHARS) + " chars)"
        else text
        synchronized(ledger) {
            ledger[key] = at + "s " + text.length + "B " + cut
            ledgerAt[key] = now
            while (ledger.size > MAX_LEDGER) {
                val first = ledger.keys.first()
                ledger.remove(first)
                ledgerAt.remove(first)
            }
        }
        // The event log, which answers "how often does 高德 push": one line per send, with the
        // gap since the one before it, so a ride's opening burst, its steady countdown and its
        // station changes are each visible as their own spacing.
        synchronized(events) {
            val gap = if (events.isEmpty()) 0.0
            else ((now - startAt).toDouble() / 1000.0) - events.last().substringBefore('s').toDouble()
            events.add(String.format("%.1fs +%.1fs %s %dB", (now - startAt) / 1000.0, gap, key,
                text.length))
            while (events.size > MAX_EVENTS) events.removeAt(0)
        }
    }

    /**
     * What a payload is, for the ledger's key: a `datas` payload by the `type`s it carries, a
     * card by the sort of fields its `cardData` has, and anything else by its own top-level keys.
     * Values are left out on purpose - the shape is what tells one kind of payload from another.
     */
    private fun shape(text: String): String {
        // Cheap on purpose: this runs on every device-layer call, on 高德's own message path, and
        // most of those carry nothing worth parsing.
        if (text.length < 64 || (!text.contains("datas") && !text.contains("cardData"))) {
            return "text:" + text.take(24)
        }
        return try {
            val o = JSONObject(text)
            val datas = array(o.opt("datas"))
            if (datas != null) {
                val types = ArrayList<String>()
                for (i in 0 until datas.length()) {
                    types.add(datas.optJSONObject(i)?.optInt("type", -1)?.toString() ?: "?")
                }
                "datas:" + types.joinToString(",")
            } else {
                val card = o.optJSONObject("cardData")
                val keys = (card ?: o).keys().asSequence().sorted().joinToString(",")
                (if (card != null) "card:" else "keys:") + keys
            }
        } catch (t: Throwable) {
            "?"
        }
    }

    /** Every send, oldest first, and how far apart they were. */
    fun eventsDump(): String {
        val sb = StringBuilder("events:")
        synchronized(events) {
            if (events.isEmpty()) return sb.append(" nothing yet").toString()
            for (e in events) sb.append("\n  ").append(e)
        }
        return sb.toString()
    }

    /** The ledger, whole, for a probe that asked for everything. */
    fun ledgerDump(): String {
        val sb = StringBuilder("ledger:")
        synchronized(ledger) {
            if (ledger.isEmpty()) return sb.append(" nothing yet").toString()
            for ((k, v) in ledger) sb.append("\n  ").append(k).append("  ").append(v)
        }
        return sb.toString()
    }

    /**
     * The ride card 高德 really sends, turned into the entity this module already draws.
     *
     * 高德 keeps two channels for a bus or subway navigation, and between them they carry the whole
     * ride. Neither is an intent entity - nothing in 高德's Java builds one - so both are turned
     * into the same GaoDePtIntentEntity-shaped JSON the IntelligentIntent provider answers with,
     * and AmapTransitScene and the island need no change at all.
     *
     * bizType 103, the plan (its own OPPO AOD card's channel, third_sdk_oppo_aod):
     *
     *   {"cardData":{"planData":[{"icon":"bus_foot_a","subText":"13"},{"text":"7号线",
     *      "bgColor":"#86B81C"},{"text":"3号线","bgColor":"#FFA500"},{"text":"番29路"}]}}
     *
     *   and, once the ride is under way, the trip card itself:
     *
     *   {"cardData":{"title":"步行至 大学城南地铁站", "mainText":"4号线","subText":"大学城南(E口)",
     *      "remainMessage":"21分钟·08:21到达",
     *      "titleItems":[{"text":"大学城南"},{"text":"(E口)"},{"text":"进站"}],
     *      "subTitleItems":[{"text":"4号线"},{"text":"(南沙客运港方向)"}],
     *      "arrived":false,"location":{"index":0,"persent":0,"remainStations":1}}}
     *
     * bizType 113 (amap_glass) carries the live part: which line is running, where its vehicle is,
     * how many stops are left, and the next train's countdown.
     *
     *   {"datas":"[{\"type\":25,\"data\":{
     *      \"realtime\":{\"buses\":[{\"line\":\"440100017560\",\"station_index\":\"8\",
     *         \"trip\":[{\"grade_words\":\"已进站\",\"station_left\":\"0\",\"speed\":\"5\",
     *            \"track\":{\"xs\":\"113.38520500\",\"ys\":\"22.93589000\"}}]}]},
     *      \"subway\":[{\"lineId\":\"440100023034\",\"tripTime\":[{\"mainTitle\":\"2分钟\"}]}],
     *      \"arriveRemind\":{\"remainStopNum\":7,\"remainTime\":4631,\"remainLength\":26553}}}]"}
     *
     * The walking phase is left alone (高德 has an island of its own for it) and the card is taken
     * down once the ride ends.
     */
    private fun ride(biz: Int, payload: String) {
        try {
            val root = JSONObject(payload)
            if (biz == RIDE_BIZ) {
                val data = root.optJSONObject("cardData") ?: return
                val fresh = rides(data.optJSONArray("planData"))
                // A new ride, not just the next card of the same one: the plan is taken again
                // whenever its lines are not the ones already held, not only when it has a
                // different number of them. Two rides of one leg each both come as a plan of one
                // line, and by length alone the second kept the first one's name and colour.
                if (plan == null || !samePlan(fresh, plan!!)) {
                    plan = fresh
                    simPlan = null
                    // The route is NOT dropped here. A plan card and the route plan are two views
                    // of the same trip and arrive in either order, so dropping one when the other
                    // changes threw the stations away a moment after they arrived (a route panel
                    // sends the plan card for every line it shows). A route is used by matching
                    // the line being ridden against its segments, so a stale one simply does not
                    // match and the fallback takes over.
                    Xp.log(TAG + "plan: " + (0 until fresh.length()).joinToString(" -> ") {
                        fresh.optJSONObject(it)!!.optString("kind") + ":" +
                            fresh.optJSONObject(it)!!.optString("name")
                    })
                }
                val title = data.optString("title").trim()
                if (title.isEmpty()) {
                    // 高德's first card of a navigation names nothing at all: it is the trip's
                    // capsules and no more - no stop, no milestone, no progress. What it does say
                    // is whether the trip opens with a walk, which is the walk [walkToFirstStop]
                    // is here to start. The card is not held: a card with no words would put
                    // nothing on the island anyway.
                    opening = opensWithWalk(data.optJSONArray("planData"))
                    walkToFirstStop()
                    return
                }
                planCard = data
                if (title.contains("步行")) {
                    // A walking card is 高德's own phase, and before the ride there is nothing of
                    // ours up at all. After one it means the ride is over - unless the walk is a
                    // change in the middle of the trip, which the plan's own index tells apart:
                    // a plan's capsules are its legs in order, and a walk on the last of them is
                    // the end of the trip.
                    // 高德's index counts legs in its own planData, walking capsules and all,
                    // while `plan` here has had every walking capsule dropped - so the leg count
                    // that index belongs to is the card's own, not this one.
                    val index = data.optJSONObject("location")?.optInt("index", -1) ?: -1
                    val legs = data.optJSONArray("planData")?.length() ?: 0
                    if (riding && (index < 0 || legs == 0 || index >= legs - 1)) {
                        clear()
                    } else if (!riding) {
                        // The walk at the front of the trip, which is the one 高德 sends when the
                        // trip's navigation starts - the 「开始导航」 this is here for. See
                        // [walkTo]: 高德 opens its own walking navigation, it is not drawn here.
                        walkTo(data)
                    }
                    return
                }
                riding = true
            } else {
                // 高德's `datas` is a string holding JSON, which is what `sendMessage` hands over
                // ({"datas":"[{\"type\":25,\"data\":{...}}]"}); reading it with optJSONArray
                // returns null, and the whole live channel - the stop the ride is at, the stops
                // left, the countdown - never arrives.
                // The channel carries more than the ride's live data: a payload's `datas` holds
                // entries of several `type`s - the ride's own 25, its route plan 24, walking's 7
                // (naviType) - and taking the first one regardless put `{naviType:3}` in for the
                // ride's live data whenever a walking payload went past. Each is taken by its
                // type, and the plan is kept for the stations it names.
                val data = array(root.opt("datas")) ?: return
                val plan = dataOf(data, PLAN_TYPE)
                if (plan != null) {
                    route = plan
                    // The trip's own plan, which names the stop the opening walk goes to and the
                    // way into it. It is here about 30 ms after the navigation starts, tens of
                    // seconds before 高德's card for that walk is.
                    walkToFirstStop()
                }
                val inner = dataOf(data, RIDE_TYPE)
                if (inner != null) {
                    live = inner
                    liveAt = SystemClock.uptimeMillis()
                }
                if (plan == null && inner == null) return
                if (!riding) return
            }
            Xp.log(TAG + "ride: card=" + (planCard != null) + " live=" + (live != null) +
                " plan=" + (plan?.length() ?: -1) + " riding=" + riding)
            show()
        } catch (t: Throwable) {
            Xp.log(TAG + "ride card failed: " + t + " " + t.stackTrace.take(4).joinToString(" | "))
        }
    }

    /**
     * The entry of a 113 payload of that `type`, or null when the payload is another kind's - a
     * walking payload must not be taken for the ride's.
     */
    private fun dataOf(data: JSONArray, type: Int): JSONObject? {
        for (i in 0 until data.length()) {
            val e = data.optJSONObject(i) ?: continue
            if (e.optInt("type", -1) == type) return e.optJSONObject("data")
        }
        return null
    }

    /**
     * The stations of the leg being ridden, in order, out of the route plan: its `on_station`,
     * its `via_st_list` and its `off_station`. 高德's own card names only the line's end and the
     * live payload only the stop the ride is at, so this is the one place the stop being
     * approached can be read from.
     */
    private fun sequence(seg: JSONObject?): List<String> =
        seg?.let { stations(it) } ?: emptyList()

    /** One segment's stations in order: on_station, its `via_st_list`, off_station. */
    private fun stations(seg: JSONObject): List<String> {
        val out = ArrayList<String>()
        val via = seg.optJSONArray("via_st_list")
        name(seg.optJSONObject("on_station"))?.let { out.add(it) }
        for (i in 0 until (via?.length() ?: 0)) {
            name(via!!.optJSONObject(i))?.let { out.add(it) }
        }
        name(seg.optJSONObject("off_station"))?.let { out.add(it) }
        return out
    }

    /**
     * The exits of the stop a leg ends at, shaped the way the page reads them (GaoDePtPort):
     * the plan's own `outport` for that leg, with its name, shield, status and coordinate. A plan
     * that names no exit answers an empty list and the page falls back to the stop itself.
     */
    private fun ports(seg: JSONObject?): JSONArray {
        val out = JSONArray()
        val p = seg?.optJSONObject("outport") ?: return out
        val name = p.optString("name").trim()
        if (name.isEmpty()) return out
        // 高德 writes the plan's coordinate as {lon, lat} strings; the page reads {lat, lng}.
        val c = p.optJSONObject("coord")
        out.put(JSONObject()
            .put("name", name)
            .put("shield", p.optString("shield").trim())
            .put("status", p.optInt("status", -1))
            .put("status_desc", p.optString("status_desc").trim())
            .put("coord", JSONObject()
                .put("lat", c?.optString("lat")?.toDoubleOrNull() ?: 0.0)
                .put("lng", c?.optString("lon")?.toDoubleOrNull() ?: 0.0)))
        return out
    }

    /**
     * 高德's ride channel opening or closing: a trip's navigation starting, or being left.
     *
     * Only the opening re-arms - a close is not a reason to take anything down, because 高德 closes
     * the channel the moment the trip is over, in the same breath as the arrival card that is meant
     * to stay up for its own [ARRIVED_MS] (measured: `bizEnd(103)` one millisecond behind the
     * 「已到达」 card). [clear] there would take that card down and cancel its timer.
     *
     * A channel that is already open is left alone: the script begins its channels again when it
     * rebuilds them, and a rebuild is not a navigation starting.
     */
    private fun channel(closed: Boolean) {
        if (closed) {
            live103 = false
            return
        }
        if (live103) return
        live103 = true
        newNavi()
    }

    /**
     * A navigation has just started, and the trip is walked to its station again from the top: the
     * cards of a second navigation are the cards of the first over again, the walk at the front
     * among them.
     *
     * `walked` is let go so that walk is handed over again - it is what says the walk has already
     * been started, and it was only ever let go by [clear], which a navigation that is left before
     * the trip's last walk never reaches. `riding` is let go with it, and it is the one that
     * mattered most: it is set by the first card that says a ride is under way and no leave of the
     * navigation clears it, so the second navigation's walk card arrived with `riding` still true
     * and matched neither branch of [ride] - the trip's first leg is not its last, so the
     * end-of-trip test declined it, and only `!riding` would have started the walk.
     *
     * The alighting stop and the exit are NOT let go here. They are the last card to name one's,
     * and a card of the new navigation names them again before anything reads them; letting them
     * go would only lose them for a rebuild of the channel mid-ride, which arrives here too.
     */
    private fun newNavi() {
        walked = false
        riding = false
        opening = false
        Xp.log(TAG + "navigation started: the walk to the station is armed again")
        walkToFirstStop()
    }

    /** Whether a trip's capsules open with a walking leg - the walk the trip begins with. */
    private fun opensWithWalk(capsules: JSONArray?): Boolean {
        val first = capsules?.optJSONObject(0) ?: return false
        return first.optString("icon").startsWith("bus_foot") ||
            first.optString("capsuleType").trim() == "0"
    }

    /**
     * Hands the trip's opening walk to 高德's own walking navigation, out of the plan itself.
     *
     * The card 高德 sends for that walk is no use for this: the first thing on the ride's channel
     * when the navigation starts is a card holding nothing but `planData`, and the card that names
     * the stop (「步行至 大学城南地铁站」) only arrives once 高德's own walking phase gets going -
     * measured at 26 and 59 seconds after the tap, over two runs. Starting from it means the
     * navigation is entered, and the walk starts, half a minute later.
     *
     * Everything that card adds is in the plan, about 30 ms after the tap: the trip's first ride
     * segment names the stop the walk goes to (`on_station.name`, 「大学城南」) and carries the way
     * into it with its coordinate (`inport`, 「E口」 at 113.399217, 23.044146). So the walk is
     * started from that, and starts when the tap is made.
     *
     * Whether there is a walk at all is [opening]'s - a trip that begins at the station has none.
     */
    private fun walkToFirstStop() {
        if (!opening || walked) return
        val seg = route?.optJSONArray("segmentlist")?.optJSONObject(0) ?: return
        val to = seg.optJSONObject("on_station")?.optString("name")?.trim().orEmpty()
        val c = seg.optJSONObject("inport")?.optJSONObject("coord")
        val lat = c?.optString("lat")?.toDoubleOrNull()
        val lng = c?.optString("lon")?.toDoubleOrNull()
        val cl = AmapImmerse.loader()
        if (to.isEmpty() || lat == null || lng == null) {
            Xp.log(TAG + "walk: the plan names no stop yet")
            return
        }
        if (cl == null) {
            Xp.log(TAG + "walk to " + to + ": no loader")
            return
        }
        walked = true
        Xp.log(TAG + "walk to " + to + " -> " + AmapFootNavi.start(cl, lat, lng, to))
    }

    /**
     * Hands the trip's opening walk to 高德's own walking navigation.
     *
     * A bus or subway trip begins with a walk to the first stop, and 高德 gives that walk its own
     * navigation - the 「步行导航 ▸」 beside it in the trip's page, which otherwise takes a second
     * tap somewhere else to reach. ColorOS starts it for you when the trip's card is pressed, out
     * of the walk's entity, which its build of 高德 hands over; this phone's 高德 hands over no
     * such entity, so the walk's own end is read off the plan instead: the stop the card says you
     * are walking to (「步行至 大学城南地铁站」), and the entrance the plan gives for that line
     * (「E口」, 113.399217, 23.044146 - `inport`). 高德 fills the start of the walk in from the
     * current fix by itself, and does the drawing; AmapFootNavi does the call.
     *
     * Only the walk at the front gets this. A walk in the middle of a trip is a change of line,
     * and ColorOS waits to be asked for that one too - its walking card carries a button.
     */
    private fun walkTo(card: JSONObject) {
        if (walked) return
        val to = station(whereStation(card))
        if (to.isEmpty()) return
        val inport = segmentPort(card.optString("mainText").trim())
        val c = inport?.optJSONObject("coord")
        val lat = c?.optString("lat")?.toDoubleOrNull()
        val lng = c?.optString("lon")?.toDoubleOrNull()
        val cl = AmapImmerse.loader()
        if (lat == null || lng == null || cl == null) {
            Xp.log(TAG + "walk to " + to + ": no " + (if (cl == null) "loader" else "entrance"))
            return
        }
        walked = true
        Xp.log(TAG + "walk to " + to + " -> " + AmapFootNavi.start(cl, lat, lng, to))
    }

    /**
     * The plan's entrance for the line a walk is going to: a segment names its line in
     * `bus_key_name`, and its `inport` is the way in at the end of the walk to it.
     */
    private fun segmentPort(line: String): JSONObject? {
        val list = route?.optJSONArray("segmentlist") ?: return null
        for (i in 0 until list.length()) {
            val s = list.optJSONObject(i) ?: continue
            if (line.isEmpty() || s.optString("bus_key_name").trim() == line) {
                return s.optJSONObject("inport")
            }
        }
        return null
    }

    /**
     * The plan's segment for the leg being ridden - the line's name is not enough to pick it.
     *
     * A plan is whatever the last route panel left behind: a ride sends none of its own (the whole
     * 2026-10-05 ride carried 346 live payloads and not one `type 24`), so the one being held is
     * regularly another trip's, and 「7号线」 names both of that line's directions. Taking the
     * first segment whose name matched therefore handed back a leg going the other way, and named
     * its stations: at 大学城南 with three stops to go the ride said 下一站 深井 where its own
     * card said the stop after it was 板桥, and at one stop to go it said 下一站 裕丰围 where the
     * card said 南村万博 - worse than having no plan at all, which gets both right.
     *
     * What tells a plan of this ride from a plan of another is the stop the card says this leg
     * gets off at ([alight]): the card names the stop it lets you out at, and this ride's own
     * segment ends there. A plan that ends elsewhere is not this ride's, and is let go - the
     * fallback names the stop from 高德's `nextStopName` and, with one stop left, the card's own
     * destination, which is where the ride is going anyway.
     */
    private fun segment(line: String, alight: String): JSONObject? {
        val list = route?.optJSONArray("segmentlist") ?: return null
        if (list.length() == 0) return null
        // Without the stop the card names there is nothing to check the plan against, and an
        // unchecked plan is the thing this is here to keep out.
        if (alight.isEmpty()) return null
        if (line.isEmpty()) return only(list, alight)
        // The exact name first: the segment's own key name is 「7号线」 and the card's line is
        // 「7号线」, and looking for one inside the other would take 「11号线」 for 「1号线」.
        for (i in 0 until list.length()) {
            val s = list.optJSONObject(i) ?: continue
            if (s.optString("bus_key_name").trim() == line && agrees(s, alight)) return s
        }
        // Then inside either: 「番29路」 against 「番29路(短线)」, 「地铁4号线(南沙客运港--黄村)」
        // against 「4号线」.
        for (i in 0 until list.length()) {
            val s = list.optJSONObject(i) ?: continue
            if ((inside(s.optString("bus_key_name").trim(), line) ||
                    inside(s.optString("busname").trim(), line)) && agrees(s, alight)) return s
        }
        return only(list, alight)
    }

    /** The plan's only segment, when it has just the one and it is this ride's. */
    private fun only(list: JSONArray, alight: String): JSONObject? {
        if (list.length() != 1) return null
        val s = list.optJSONObject(0) ?: return null
        return if (agrees(s, alight)) s else null
    }

    /** Whether [seg] is the leg being ridden: it has to end at the stop the card names. */
    private fun agrees(seg: JSONObject, alight: String): Boolean =
        name(seg.optJSONObject("off_station")) == alight

    /**
     * Whether [line] names a whole line in [name]. 「4号线」 inside 「地铁4号线(南沙客运港--黄村)」
     * is one; 「1号线」 inside 「11号线」 is not - the character before it there is a digit, so what
     * was found is the tail of a longer number.
     */
    private fun inside(name: String, line: String): Boolean {
        var at = name.indexOf(line)
        while (at >= 0) {
            if (at == 0 || !name[at - 1].isDigit()) return true
            at = name.indexOf(line, at + 1)
        }
        return false
    }

    /** A station object's name, or null when it has none. */
    private fun name(station: JSONObject?): String? =
        station?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The ride's lines, in the order the trip takes them. The plan's capsules are a leg each:
     * a walking one is `capsuleType 0` (or the foot icon), a subway `2`, a bus `1` with a line
     * name. 「13」 on a walking capsule is its minutes, not a bus.
     */
    private fun rides(plans: JSONArray?): JSONArray {
        val out = JSONArray()
        for (i in 0 until (plans?.length() ?: 0)) {
            val o = plans!!.optJSONObject(i) ?: continue
            val name = o.optString("text").trim()
            if (name.isEmpty()) continue
            if (o.optString("icon").startsWith("bus_foot")) continue
            if (o.optString("capsuleType") == "0") continue
            val bg = o.optString("bgColor").trim()
            if (name.isNotEmpty() && bg.startsWith("#")) {
                synchronized(lineColors) { lineColors[name] = bg }
            }
            out.put(JSONObject()
                .put("name", name)
                .put("kind", if (name.contains("号线") || name.endsWith("线")) "2" else "1")
                .put("color", bg))
        }
        return out
    }

    /** Whether two plans name the same lines, in the same order. */
    private fun samePlan(a: JSONArray, b: JSONArray): Boolean {
        if (a.length() != b.length()) return false
        for (i in 0 until a.length()) {
            val x = a.optJSONObject(i)?.optString("name")
            val y = b.optJSONObject(i)?.optString("name")
            if (x != y) return false
        }
        return true
    }

    /** The plan's line [at], or the first when there is no such index. */
    /**
     * The plan's leg at [at], or nothing when there is none. The leg after the last one is
     * nothing, not the first one over again: falling back to index 0 there gave a one-leg ride a
     * second leg that was the line it was already on, so `next` was never null - the transfer
     * badge named the line being ridden instead of the one being changed to, and the line under
     * the milestone said 「换乘」 on a ride that changes to nothing. ([current] only ever answers
     * 0..length-1, so this is the whole of what the fallback did.)
     */
    private fun lineAt(at: Int): JSONObject? {
        val p = simPlan ?: plan ?: return null
        if (at !in 0 until p.length()) return null
        return p.optJSONObject(at)
    }

    /** Which line the ride is on now, and how far it has got along the plan. */
    private fun current(planCard: JSONObject?, live: JSONObject?): Int {
        val said = planCard?.optString("mainText")?.trim().orEmpty()
        if (said.isNotEmpty()) {
            for (i in 0 until (plan?.length() ?: 0)) {
                if (plan!!.optJSONObject(i)?.optString("name") == said) return i
            }
        }
        val busId = live?.optJSONObject("realtime")?.optJSONArray("buses")
            ?.optJSONObject(0)?.optString("line").orEmpty()
        if (busId.isNotEmpty()) {
            for (i in 0 until (plan?.length() ?: 0)) {
                val p = plan!!.optJSONObject(i) ?: continue
                if (p.optString("kind") == "1") return i
            }
        }
        val location = planCard?.optJSONObject("location")
        if (location != null) {
            val index = location.optInt("index", 0)
            if (index in 0 until (plan?.length() ?: 0)) return index
        }
        return 0
    }

    /** Builds the entity out of whatever the two channels have said, and passes it on. */
    private fun show() {
        val card = planCard
        val at = current(card, live)
        val mine = lineAt(at)
        val next = lineAt(at + 1)
        val line = mine?.optString("name").orEmpty()
        if (line.isEmpty()) return
        val kind = mine?.optString("kind").orEmpty().ifEmpty { "1" }
        val color = mine?.optString("color").orEmpty()
        val where = live?.optJSONObject("arriveRemind")
        val bus = live?.optJSONObject("realtime")?.optJSONArray("buses")?.optJSONObject(0)
        val trip = bus?.optJSONArray("trip")?.optJSONObject(0)
        // A bus says how many stops are left; a subway only says the next train, so the trip
        // card's own count stands in for it. 高德's `location.remainStations` is the count for the
        // ride it drew, and a bus's own stop count is finer, so a bus keeps its own.
        // 高德's `location` on a walking card describes the WALK, not the leg the card is named
        // after: `persent` is how far along the street it has got (「步行至 大学城南地铁站」 walks
        // at 0.5, halfway there) and `remainStations` is the one stop it ends at, the station.
        // Read as the ride's they put a half-filled bar and 「剩 1 站」 on a 4号线 that had not been
        // boarded - and the card is the walking one for the whole walk, so it is the walk's numbers
        // the island would show until the ride began. ColorOS draws no station overview before the
        // ride starts either; see AmapTransitIsland.PROGRESS_AT for its own rule.
        val cardLocation = if (card?.optString("title").orEmpty().contains("步行")) null
            else card?.optJSONObject("location")
        val cardCount = cardLocation?.optInt("remainStations", -1) ?: -1
        val remain = when {
            kind == "1" && where?.has("remainStopNum") == true ->
                where.optInt("remainStopNum", 0)
            cardCount >= 0 -> cardCount
            kind == "1" -> where?.optInt("remainStopNum", 0) ?: 0
            else -> 0
        }
        val countdown = live?.optJSONArray("subway")?.optJSONObject(0)
            ?.optJSONArray("tripTime")?.optJSONObject(0)?.optString("mainTitle").orEmpty()
        val items = card?.optJSONArray("titleItems")
        val title = card?.optString("title").orEmpty()
        val direction = text(card?.optJSONArray("subTitleItems"), 1)
        // `titleItems` is one sentence cut into pieces, not fixed slots: a real ride sent
        // 「1站」「后」「 · 」「邮轮中心」「出站」, which is 「1站后 · 邮轮中心出站」 - the count
        // first and the stop fourth. Reading a piece by its place therefore reads a count as a
        // stop's name, which is how 「下一站 2站」 reached the lock screen. The pieces are read
        // apart instead, and the count, the stop and the action taken out of them.
        val sentence = titleItems(items)
        val parts = pieces(items)
        val first = text(items, 0)
        val stopsLeft = Regex("(\\d+)\\s*站").find(sentence)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val stationName = stopIn(parts).ifEmpty { if (isCount(first)) "" else first }
        val action = ACTION.find(sentence)?.groupValues?.get(1).orEmpty()
        val arriveText = bus?.optString("sub_status").orEmpty()
        val stationAction = action
        // Where the ride is. 高德's `curStopName` is the station the vehicle is at or has just
        // left - a real ride at 大学城南 read `curStopName` 大学城南 with 3 stops to go, 板桥 at
        // 2, 员岗 at 1 - and `nextStopName` is the one it is coming to. A ride may leave the
        // second empty the whole way (2026-10-05, 7号线: 346 payloads, never filled), so the stop
        // after the current one can only be worked out at the end: with one stop left, the next
        // stop is the destination the card itself names.
        val curStop = where?.optString("curStopName").orEmpty().replace(" ", "").trim()
        // The stop this leg lets you out at, which is what the card's sentence names. It is also
        // the one thing that can tell a plan of this ride from a plan of another - see [segment] -
        // and the stop the arrival milestone has to name, which is why the last one this leg named
        // stands in for it on the cards that name none (see [alightStop]).
        if (stationName.isNotEmpty()) {
            alightStop = stationName
            alightLine = line
        }
        val alight = stationName.ifEmpty { if (alightLine == line) alightStop else "" }
        // The stop the ride is coming to. 高德 names it in `nextStopName`, which a real ride left
        // empty for its whole length; the route plan's own station list has it, so that is read
        // first - the stop after the one the ride is at, in the leg's own order.
        val seg = segment(line, alight)
        val seq = sequence(seg)
        val after = seq.indexOf(curStop)
        val routeNext = if (after >= 0 && after + 1 < seq.size) seq[after + 1] else ""
        val nextStop = routeNext.ifEmpty {
            where?.optString("nextStopName").orEmpty().replace(" ", "").trim()
        }
        // The line's end: SceneService's `ya.b.P` reads the leg's own `off_station` and only then
        // `destStation`, and this is the same stop in the same order - the one the leg's cards
        // named it, then whatever the card's own words name, then the sentence itself. (The plan
        // needs no separate look: [segment] only accepts a plan that ends at [alight] anyway.)
        val dest = alight.ifEmpty { destName(title) }.ifEmpty { stationName }
        // 高德's own status codes, as its card sends them: 1 near the origin, 2 waiting, 3 the next
        // stop, 5 arrived at a stop, 6 a transfer, 7 the line's end. (The 4/5 in ColorOS's
        // GaoDePublicTransportNavMilestone are that enum's own ordinals, not these.)
        val arrived = card?.optBoolean("arrived", false) == true || title.contains("已到达") ||
            (kind == "1" && bus?.optString("status") == "0" && remain <= 0)
        val nextIsDest = remain == 1 && dest.isNotEmpty()
        // The stop the milestone names: the one the ride is coming to when 高德 has named it, or
        // when the only stop left is the destination - and nothing at all when it has not, which
        // is what keeps 「下一站」 off a stop the ride has already left.
        val atStop = when {
            nextStop.isNotEmpty() -> nextStop
            nextIsDest -> dest
            else -> ""
        }
        val status = when {
            arrived -> "7"
            stationAction.contains("换乘") -> "6"
            stationAction.contains("进站") || title.contains("候车") -> "2"
            cardCount == 0 -> "5"
            atStop.isNotEmpty() -> "3"
            curStop.isNotEmpty() -> "5"
            else -> "3"
        }
        // A count in the name's slot is the count of stops left, and when 高德 has said nothing
        // else about the distance that count is the ride's progress.
        val left = if (stopsLeft > 0) stopsLeft else remain
        // The stop the card names for this milestone: the next one when it is known, else the one
        // the ride is at, else what the card's own words name. A stop repeated in two slots is one
        // stop, and the page drops the repeat, so nothing here should invent one.
        val here = atStop.ifEmpty { curStop }.ifEmpty { stationName }
            .ifEmpty { station(whereStation(card)) }
        val board = here.ifEmpty { stationName }
        val stops = ArrayList<String>(3)
        for (name in listOf(board, here, dest)) {
            if (name.isNotEmpty() && (stops.isEmpty() || stops.last() != name)) stops.add(name)
        }
        val two = stops.size <= 2 || status == "5" || status == "7"
        val coord = coords(trip)
        val leg = JSONObject()
            .put("transportType", kind)
            .put("lineName", line)
            .put("lineDirection", direction)
            .put("lineBgColor", color.ifEmpty { lineColor(line) })
            .put("remainStations", left)
            .put("isCurrent", true)
            .put("on_station", JSONObject()
                .put("stationName", board)
                .put("coord", coord))
            .put("off_station", JSONObject()
                .put("stationName", dest)
                .put("coord", coord)
                // The exits of the stop this leg ends at, as 高德's plan has them. SceneService
                // takes one of these - the one `exitName` names, and its last one when it names
                // none - and uses its own coordinate to look the arrival's landmark up
                // (`ya.b.N`), which is the only reason the plan's exit coordinates are here at
                // all; they used to be sent as an empty list, so the page had none to take. The
                // page reads it as GaoDePtPort: name, shield, status, status_desc and a coord.
                .put("port_list", ports(seg)))
        // The exit 高德 names for the stop this leg ends at: its own piece of the card's sentence
        // (「(B口)」), which the card's title does not carry and which the arrival's own card stops
        // carrying - so the last one this leg named stands in, the way its stop does
        // ([alightStop]). The exits themselves stay the plan's, with their own coordinates
        // ([ports]): a name on its own is not a place, and hanging one on the leg's own coordinate
        // would send the arrival's landmark lookup to the vehicle rather than to the way out.
        // SceneService keeps the same two apart - `exitName` for the words, `port_list` for the
        // points (`ya.b.N`).
        val named = parts.firstOrNull { isExit(it) }?.let { exitOf(it) }.orEmpty()
            .ifEmpty { exitOf(title) }
        if (named.isNotEmpty()) {
            exitStop = named
            exitLine = line
        }
        val exit = named.ifEmpty { if (exitLine == line) exitStop else "" }
        // Every arrival 高德 mentioned, in order (GaoDePtWaitInfo.realTime).
        val times = live?.optJSONArray("subway")
        val arrivals = JSONArray()
        if (kind == "2" && times != null) {
            for (i in 0 until times.length()) {
                val t = times.optJSONObject(i)?.optJSONArray("tripTime") ?: continue
                val first = t.optJSONObject(0) ?: continue
                arrivals.put(JSONObject()
                    .put("mainTitle", first.optString("mainTitle"))
                    .put("orderTiptext", first.optString("orderTiptext"))
                    .put("status", first.optInt("status", -1))
                    .put("titleRange", first.optInt("titleRange", 0))
                    .put("textColor", first.optString("mainColor"))
                    .put("isShowSignal", first.optBoolean("isShowSignal", false)))
            }
        }
        if (arriveText.isNotEmpty() && arrivals.length() == 0) {
            arrivals.put(JSONObject().put("mainTitle", arriveText)
                .put("orderTiptext", "").put("status", -1))
        }
        if (arrivals.length() > 0) {
            leg.getJSONObject("on_station").put("waitInfo",
                JSONObject().put("realTime", arrivals))
        }
        if (stops.size == 3) {
            // The middle stop, which is what makes the track three nodes rather than two.
            leg.put("via_st_list", JSONArray().put(JSONObject()
                .put("name", stops[1]).put("coord", coord).put("isTransferStation", false)))
        }
        val realtime = when {
            kind == "1" && arriveText.isNotEmpty() -> if (arriveText == "已进站") "车辆已进站" else arriveText
            // 高德's subway countdown is sometimes a time (「3分钟」) and sometimes the train's own
            // state (「即将进站」). 「进站」 is only the word 高德 leaves off, so a countdown that
            // already ends in it keeps its own words - appending unconditionally made
            // 「即将进站进站」.
            kind == "2" && countdown.isNotEmpty() ->
                if (countdown.endsWith("进站")) countdown else countdown + "进站"
            else -> ""
        }
        if (realtime.isNotEmpty()) {
            leg.put("on_station", JSONObject()
                .put("stationName", stationName)
                .put("coord", coord)
                .put("waitInfo", JSONObject().put("realTime",
                    JSONArray().put(JSONObject().put("mainTitle", realtime)))))
        }
        val navi = JSONArray().put(leg)
        if (next != null) {
            navi.put(JSONObject().put("transportType", next.optString("kind"))
                .put("lineName", next.optString("name")))
        }
        val count = cards.incrementAndGet()
        Xp.log(TAG + "ride #" + count + ": leg " + at + "/" + ((plan?.length() ?: 1) - 1) +
            " " + kind + " " + line + " remain=" + remain + " status=" + status +
            (if (stationName.isNotEmpty()) " at=" + stationName else "") +
            (if (exit.isNotEmpty()) " exit=" + exit + "/" + exitLine else " exit=-") +
            (if (realtime.isNotEmpty()) " rt=" + realtime else ""))
        // Every field 高德's GaoDePtIntentEntity carries, so what the page reads is the same
        // shape ColorOS's wrapper reads (its status/total*/entity*/arrived/offRoute/gpsSignalStatus).
        val location = live?.optJSONObject("locationData")
        // 高德's own GPS note rides on the card (`tip.text` "信号弱"), and it also sends the
        // status outright; either says the fix is poor.
        val gpsText = card?.optJSONObject("tip")?.optString("text").orEmpty()
        val gps = card?.optInt("gpsSignalStatus", 0) ?: 0
        tell(JSONObject()
            .put("status", status)
            .put("naviInfo", navi)
            .put("entityId", entityId(line, at))
            .put("entityName", line)
            .put("originStation", board)
            .put("destStation", dest)
            .put("exitName", exit)
            .put("guideInfo", card?.optString("remainMessage").orEmpty())
            .put("arrived", status == "7")
            .put("offRoute", false)
            .put("isPublic", true)
            .put("gpsSignalStatus", if (gps > 0 || gpsText.contains("弱")) 1 else 0)
            // How far down this leg the ride is, as 高德's own card has it (`location.persent`,
            // 0..1). Carried so the progress bar shows where the ride has got to rather than how
            // many stops are left.
            .put("legPercent", cardLocation?.optDouble("persent", -1.0) ?: -1.0)
            .put("totalDistance", metres(where?.optInt("remainLength", 0) ?: 0))
            .put("totalDuration", (where?.optInt("remainTime", 0) ?: 0).toDouble())
            .put("deepLink", card?.optString("scheme").orEmpty())
            .put("destLatitude", location?.optDouble("latitude", 0.0) ?: 0.0)
            .put("destLongitude", location?.optDouble("longitude", 0.0) ?: 0.0)
            .toString(), "update")
        // The arrival card's own life, and only the trip's own arrival gets it: a 「已到达」 on a
        // leg that still has another after it is that line ending, not the trip, and ColorOS keeps
        // its ordinary long silence for that one (its arrival flow only arms on the last segment).
        // Anything else the ride sends cancels the takedown, so a card that goes on moving is
        // never taken down mid-ride.
        arriving.removeCallbacks(takeArrivalDown)
        if (status == "7" && at >= (plan?.length() ?: 1) - 1) {
            arriving.postDelayed(takeArrivalDown, ARRIVED_MS)
        }
    }

    /** One identifier for the ride, stable across its updates (the card's own key). */
    private fun entityId(line: String, at: Int): String = "gaode-pt-" + line + "-" + at

    /** Metres as the words 高德 uses: 「3.0公里」 / 「800米」. */
    private fun metres(m: Int): String = when {
        m <= 0 -> ""
        m >= 1000 -> String.format("%.1f公里", m / 1000.0)
        else -> m.toString() + "米"
    }

    /**
     * The ride's own point, as the leg's coordinates. A bus carries it on its track; a subway has
     * no bus at all - its `realtime` is empty - and says where it is in `locationData` instead.
     * Without a point the page cannot look a landmark up and the ride falls back to the national
     * picture, so both are read, and 0 is only the answer when 高德 has given neither.
     */
    private fun coords(trip: JSONObject?): JSONObject {
        val track = trip?.optJSONObject("track")
        val lat = track?.optString("ys")?.toDoubleOrNull()
        val lng = track?.optString("xs")?.toDoubleOrNull()
        if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
            return JSONObject().put("lat", lat).put("lng", lng)
        }
        val at = live?.optJSONObject("locationData")
        return JSONObject()
            .put("lat", at?.optDouble("latitude", 0.0) ?: 0.0)
            .put("lng", at?.optDouble("longitude", 0.0) ?: 0.0)
    }

    private fun coord(lat: Double?, lng: Double?): JSONObject =
        JSONObject().put("lat", lat ?: 0.0).put("lng", lng ?: 0.0)

    /**
     * 高德's `titleItems` as the one sentence its pieces spell: 「1站」「后」「 · 」「邮轮中心」
     * 「出站」 is 「1站后 · 邮轮中心出站」. Its pieces carry their own spaces, and the gaps are
     * closed so the sentence can be searched.
     */
    private fun titleItems(items: JSONArray?): String {
        if (items == null) return ""
        val sb = StringBuilder()
        for (i in 0 until items.length()) {
            val t = items.optJSONObject(i)?.optString("text").orEmpty()
            if (t.isEmpty()) continue
            if (sb.isNotEmpty() && !t.startsWith(" ") && sb.last() != ' ') sb.append(' ')
            sb.append(t.trim())
        }
        return sb.toString().trim()
    }

    /** 「步行至 大学城南地铁站」 -> 「大学城南地铁站」. */
    /**
     * The place 高德's card names as where the ride is going: 「步行至 大学城南地铁站」 ->
     * 「大学城南地铁站」, and 「已到达 汕黄牛.牛肉海鲜自助」 -> 「汕黄牛.牛肉海鲜自助」.
     *
     * The arrival card says 已到达, not 至. Reading only 至 left it with no place at all, and the
     * card fell back to the bare 「到站 / 已到站」 - ColorOS's own arrival card puts the station in
     * its big text (`ya.b.P`, the off_station's name and then `destStation`), so what was missing
     * was the name, not the wording.
     */
    private fun destName(title: String): String =
        Regex("(?:已到达|至)\\s*(.+)$").find(title)?.groupValues?.get(1)?.trim().orEmpty()

    /**
     * Whether one of 高德's title slots holds a count of stops rather than a name. Its card fills
     * `titleItems[0]` with the stop's name while the ride is under way and with the count of stops
     * left in other phases - seen as 「7站」 and, on a later ride, as a bare 「2」. The two have to
     * be told apart before either is drawn, and a slot that is nothing but digits is a count: no
     * stop on a line is named 「2」.
     */
    private fun isCount(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 12) return false
        if (t.all { it.isDigit() }) return true
        return t.contains("站") && Regex("\\d+").containsMatchIn(t)
    }

    /**
     * The stop's name out of the card's own words, for the phases in which its name slot holds a
     * count instead: 「步行至 翁角路地铁站」 -> 「翁角路地铁站」.
     */
    private fun whereStation(card: JSONObject?): String {
        if (card == null) return ""
        for (key in listOf("subText", "mainText")) {
            val v = card.optString(key).trim()
            if (v.isEmpty() || isCount(v)) continue
            val m = Regex("[至到]\\s*(.+)$").find(v)
            if (m != null) return m.groupValues[1].trim()
        }
        val t = card.optString("title").trim()
        val m = Regex("[至到]\\s*(.+)$").find(t)
        return m?.groupValues?.get(1)?.trim().orEmpty()
    }

    /** 「大学城南(E口)」 -> 「大学城南」: a stop, with the exit it named taken off. */
    private fun station(text: String): String =
        text.replace(Regex("[\\(（][^)\\)）]*[\\)）]"), "").trim()

    /**
     * The card's sentence as its pieces, trimmed, the empties dropped: a real ride's
     * 「3站」「后」「 · 」「南村万博」「(B口)」「出站」.
     */
    private fun pieces(items: JSONArray?): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until (items?.length() ?: 0)) {
            val t = items!!.optJSONObject(i)?.optString("text").orEmpty().trim()
            if (t.isNotEmpty()) out.add(t)
        }
        return out
    }

    /**
     * The stop the card's words name: the piece after the 「·」 separator, and on a card without
     * one - the walk to the station, 「大学城南」「(E口)」「进站」 - the first piece that is not a
     * count, an exit, an action or the 「后」 between them.
     *
     * Read off the assembled sentence instead, 「3站 后· 南村万博 (B口) 出站」 does not fit: the
     * exit between the stop and the action keeps a pattern over the whole line from matching, and
     * the milestone was left with no station at all.
     */
    private fun stopIn(parts: List<String>): String {
        val sep = parts.indexOfFirst { it.contains('·') }
        if (sep >= 0) {
            // The stop is either its own piece after the separator or glued to it (「· 南村万博」),
            // and a separator with nothing after it is none of the pieces at all.
            val tail = parts[sep].substringAfter('·').trim()
            if (tail.isNotEmpty()) return station(tail)
            if (sep + 1 < parts.size) return station(parts[sep + 1])
        }
        for (p in parts) {
            if (p.contains('·')) continue
            if (isCount(p) || isExit(p) || ACTION.containsMatchIn(p) || p == "后") continue
            return station(p)
        }
        return ""
    }

    /** 「(E口)」 / 「(B口)」: the exit's own piece, which is not a stop's name. */
    private fun isExit(text: String): Boolean =
        text.length > 2 && (text.startsWith("(") || text.startsWith("（")) &&
            (text.endsWith(")") || text.endsWith("）"))

    /** The word 高德 ends the card's sentence with: what happens at the stop it names. */
    private val ACTION = Regex("(进站|出站|下车|换乘|上车)")

    /** What a payload's field is, whether 高德 sent it as the string it is or as a ready array. */
    private fun array(v: Any?): JSONArray? = when (v) {
        is JSONArray -> v
        is String -> runCatching { JSONArray(v) }.getOrNull()
        else -> null
    }

    /** 「大学城南(E口)」 -> 「E口」. */
    private fun exitOf(title: String): String {
        val m = Regex("[\\(（]([^)\\)）]+)[\\)）]").find(title) ?: return ""
        return m.groupValues[1].trim()
    }

    /**
     * The ride is over: nothing of it is held, and SystemUI is told so it takes the card and the
     * page down. `riding` is what says there is anything to take down - it used to be a flag
     * that only the ride's own end could clear, and nothing ever cleared it, so the card stayed
     * up until SystemUI's own ten-minute silence gave up on it.
     */
    private fun clear(action: String = "end") {
        arriving.removeCallbacks(takeArrivalDown)
        route = null
        alightStop = ""
        alightLine = ""
        exitStop = ""
        exitLine = ""
        walked = false
        opening = false
        live = null
        liveAt = 0L
        planCard = null
        if (!riding) return
        riding = false
        tell(null, action)
    }

    /** item[at].text of a card's titleItems / subTitleItems, or empty. */
    private fun text(items: JSONArray?, at: Int): String {
        val o = items?.optJSONObject(at) ?: return ""
        return o.optString("text").trim()
    }

    /** The line's colour from the plan capsule, or the page's blue when it only names a token. */
    /** The line's colour as 高德 gives it, and a neutral grey when it gives none. */
    private fun lineColor(line: String): String {
        synchronized(lineColors) { lineColors[line] }?.let { return it }
        val plans = plan ?: return NEUTRAL
        for (i in 0 until plans.length()) {
            val o = plans.optJSONObject(i) ?: continue
            if (o.optString("text").trim() != line) continue
            val bg = o.optString("bgColor").trim()
            if (bg.startsWith("#")) return bg
            when (bg) {
                "@Color_Hue220_L1" -> return "#4a86ff"
                "@Color_Text_Brand" -> return "#018237"
            }
        }
        return NEUTRAL
    }

    private fun station(name: String, lat: Double?, lng: Double?): JSONObject =
        JSONObject().put("stationName", name).put("coord", coord(lat, lng))

    fun describe(): String {
        val sb = StringBuilder("transit: acquires=").append(acquires.get())
            .append(" queries=").append(queries.get())
            .append(" lastQuery=").append(lastQuery)
            .append(" shares=").append(shares.get())
            .append(" deletes=").append(deletes.get())
        if (lastShareAt != 0L) {
            sb.append(" lastShare=").append(lastShare).append(' ')
                .append(SystemClock.uptimeMillis() - lastShareAt).append("ms ago")
        }
        sb.append(" live=").append(lastSent != null)
        sb.append('\n').append(AmapTransitIsland.describe())
        synchronized(wearable) {
            sb.append("\nscript: ").append(if (wearable.isEmpty()) "nothing yet" else
                wearable.values.joinToString("\n  "))
        }
        synchronized(payloads) {
            sb.append("\npayload: ").append(if (payloads.isEmpty()) "nothing yet" else
                payloads.values.joinToString("\n  "))
        }
        synchronized(bridged) {
            sb.append("\nbridge: ").append(if (bridged.isEmpty()) "nothing yet" else
                bridged.entries.joinToString(" ") { it.key + "=" + it.value })
        }
        sb.append("\nrom: ").append(rom).append("  spoofOppo=").append(spoofOppo)
        // Every segment the route plan has, which is what a multi-line trip comes as - one line
        // per segment, so the one being ridden can be seen among them.
        val segments = route?.optJSONArray("segmentlist")
        if (segments == null || segments.length() == 0) {
            sb.append("\nroute: nothing yet")
        } else {
            for (i in 0 until segments.length()) {
                val seg = segments.optJSONObject(i) ?: continue
                val stops = stations(seg)
                sb.append("\nroute[").append(i).append("] ")
                    .append(seg.optString("bus_key_name").ifEmpty { seg.optString("busname") })
                    .append(": ")
                    .append(if (stops.isEmpty()) "no stations" else stops.joinToString(" → "))
            }
        }
        sb.append("\ncard: ").append(cards.get()).append(" real ride card(s)")
        synchronized(sends) {
            sb.append("\nsends: ").append(if (sends.isEmpty()) "nothing yet" else
                sends.entries.joinToString(" ") { it.key.toString() + "x" + it.value.get() })
        }
        synchronized(ledger) {
            sb.append("\nledger: ").append(ledger.size).append(" kind(s)")
            for ((k, v) in ledger) {
                val size = v.substringAfter(' ').substringBefore('B')
                sb.append("\n  ").append(k).append("  ").append(size).append("B")
            }
        }
        return sb.toString()
    }
}
