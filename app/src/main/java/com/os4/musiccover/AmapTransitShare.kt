package com.os4.musiccover

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
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

    /**
     * How often an unchanged state is passed on anyway, so SystemUI knows the navigation is
     * still on even when 高德 shares nothing new between two stations.
     */
    private const val KEEPALIVE_MS = 60_000L

    /** The clients handed out for the authority; weak, so a released one is forgotten. */
    private val ours: MutableSet<ContentProviderClient> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

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

    fun handle() {
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
        if (name != INTENT_NAME) {
            Xp.log(TAG + "shareIntent for $name, not ours")
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
        tell(null, method)
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
        } catch (t: Throwable) {
            Xp.log(TAG + "tell failed: $t")
        }
    }

    /** SystemUI started over: the last state again, if the navigation has not ended since. */
    fun resend() {
        val entity = lastSent ?: return
        lastSentAt = SystemClock.uptimeMillis()
        send(entity, "resend")
    }

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
        return sb.append(" live=").append(lastSent != null).toString()
    }
}
