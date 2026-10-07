package com.os4.musiccover

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A trip played through what 高德's process does with it - AmapTransitMilestones for the
 * milestone, AmapTransitEntity for the entity - and out of AmapTransitCard as ColorOS's card.
 *
 * The first trip is the real one of 2026-10-05 (广州 7号线, 大学城南 to 南村万博, then a walk to a
 * restaurant), its cards as 高德 sent them; the plan is that ride's own shape, written out here.
 * The second is a real plan with a change of line (7号线 to 13号线 at 裕丰围).
 *
 * Colours are not checked: android.graphics.Color is a stub on the JVM.
 */
class AmapTransitCardTest {

    private val clock = longArrayOf(0L)
    private val m = AmapTransitMilestones()

    // ------------------------------------------------------------------ the 10-05 ride

    private val ridePlan = JSONObject("""{
        "spoi":{"name":"我的位置"},
        "epoi":{"name":"汕黄牛.牛肉海鲜自助","coord":{"lon":"113.352","lat":"23.008"}},
        "expensetime":"2716","allLength":"9100","endfootlength":"1100","endfoottime":"900",
        "segmentlist":[{"bus_key_name":"7号线","busname":"地铁7号线(燕山--美的大道)","bustype":"2",
          "color":"86B81C","directionName":"美的大道","busid":"900000043742",
          "footlength":"743","foottime":"737",
          "on_station":{"name":"大学城南","start_time":"06:00","end_time":"23:16"},
          "via_st_list":[{"name":"板桥","coord":{"lon":"113.388","lat":"23.016"}},
                         {"name":"员岗","coord":{"lon":"113.365","lat":"23.015"}}],
          "off_station":{"name":"南村万博","is_trans":false},
          "outport":{"name":"B口","shield":"B","coord":{"lon":"113.348","lat":"23.004"}},
          "driver_coord_list":{"start":{"lon":"113.400","lat":"23.043"},"end":{"lon":"113.347","lat":"23.004"}}}]}""")

    private val rideCaps = JSONArray("""[
        {"capsuleType":"0","icon":"bus_foot_a","text":"","subText":"13","bgColor":"@Color_Hue220_L1"},
        {"capsuleType":"2","text":"7号线","bgColor":"#86B81C"},
        {"capsuleType":"0","icon":"bus_foot_a","text":"","subText":"14","bgColor":"@Color_Hue220_L1"}]""")

    private fun walkCard(index: Int, title: String) = JSONObject()
        .put("title", title).put("arrived", false).put("offRoute", false)
        .put("location", JSONObject().put("index", index).put("persent", 0.5).put("remainStations", 1))

    private fun rideCard(left: Int, persent: Double) = JSONObject()
        .put("title", "乘坐 地铁7号线").put("mainText", "7号线").put("arrived", false)
        .put("titleItems", JSONArray().put(t("${left}站")).put(t("后")).put(t(" · "))
            .put(t("南村万博")).put(t("(B口)")).put(t("出站")))
        .put("location", JSONObject().put("index", 1).put("persent", persent).put("remainStations", left))

    private fun t(s: String) = JSONObject().put("text", s)

    private val subwayTimes = JSONArray("""[{"mainTitle":"","orderTiptext":"下 1 辆","lineName":"7号线","lineDirection":"美的大道"}]""")

    /** One payload's worth: what the share does in update(), with the clock at [at] seconds. */
    private fun step(plan: JSONObject?, caps: JSONArray, card: JSONObject, at: Int, seconds: Long,
                     remain: Int = -1, stop: String = "", walkIsland: Boolean = false,
                     cards: Map<Int, JSONObject> = emptyMap()): AmapTransitCard.Card {
        clock[0] = seconds * 1000
        val now = clock[0]
        val n = caps.length()
        val walking = { i: Int -> AmapTransitEntity.walking(caps.getJSONObject(i)) }
        m.moveTo(at, n, now, walking) { i -> caps.getJSONObject(i).optString("capsuleType") == "2" }
        val held = m.held(now, walkIsland)
        val shown = held?.leg ?: at
        val legCard = cards[shown] ?: card
        val status: String
        var live: AmapTransitEntity.Live? = null
        when {
            held != null -> {
                status = held.status
                live = AmapTransitEntity.Live(0, 1.0, "", JSONArray())
            }
            walking(at) -> status = ""
            else -> {
                val seg = plan?.optJSONArray("segmentlist")?.optJSONObject(0)
                val total = (seg?.optJSONArray("via_st_list")?.length() ?: -1) + 1
                m.observe(remain, total, stop, now)
                live = AmapTransitEntity.Live(remain, -1.0, stop, subwayTimes)
                status = m.rideStatus(remain, at == n - 1, false, plan != null, now).status
            }
        }
        val entity = AmapTransitEntity.build(plan, caps, legCard, shown, status, live,
            if (plan == null) AmapTransitEntity.Fallback(ArrayList(m.seen), m.boardRemain) else null,
            AmapTransitEntity.exit(legCard).ifEmpty { "B口" }, AmapTransitEntity.sentence(legCard), "t")
        return AmapTransitCard.of(AmapTransitCard.Trip.parse(JSONObject(entity.toString())))!!
    }

    @Test fun theRideOf1005ReadsTheWayColorOSWould() {
        val plan = ridePlan
        assertTrue(AmapTransitEntity.fits(plan, rideCaps))

        // The walk to the station: ColorOS's silent walking card.
        var c = step(plan, rideCaps, walkCard(0, "步行至 大学城南地铁站"), 0, 0)
        assertEquals("walk", c.kind)
        assertEquals("步行至", c.leftWhite)
        assertEquals("大学城南", c.rightWhite)
        assertEquals("我的位置", c.primary)
        assertEquals("步行至大学城南", c.lockTitle)
        assertEquals("共步行743米，13分钟", c.lockSubtitle)

        // In the station, three stops ahead: waiting, the line's chip and where it goes.
        val first = rideCard(3, 0.02)
        c = step(plan, rideCaps, first, 1, 480, 3, "大学城南")
        assertEquals("2", c.status)
        assertEquals("pages/waiting", c.page)
        assertEquals("大学城南", c.primary)
        assertEquals("7", c.leftLine)
        assertEquals("", c.leftWhite) // no train time: ColorOS leaves the white half empty
        assertEquals("往美的大道", c.rightWhite)
        assertEquals("7号线(往美的大道)", c.lockSubtitle)
        assertEquals("首06:00 末23:16", c.waiting!![0].realtime1)
        assertEquals("详情关注站台信息", c.waiting!![0].realtime2)
        assertNull(c.stations)

        // 板桥 reached: 当前站 for a while, then 下一站 员岗.
        c = step(plan, rideCaps, rideCard(2, 0.44), 1, 957, 2, "板桥")
        assertEquals("5", c.status)
        assertEquals("当前站 板桥", c.primary)
        assertEquals("当前站", c.leftWhite)
        assertEquals("板桥", c.rightWhite)
        assertEquals("2站 南村万博下车", c.secondary)
        assertEquals(listOf("大学城南", "板桥", "员岗"), c.stations!!.map { it.name })
        assertTrue(c.atStation)
        assertEquals("板桥", c.landmarkStation)

        c = step(plan, rideCaps, rideCard(2, 0.44), 1, 1000, 2, "板桥")
        assertEquals("3", c.status)
        assertEquals("下一站 员岗", c.primary)
        assertEquals(listOf("板桥", "员岗", "南村万博"), c.stations!!.map { it.name })
        assertTrue(!c.atStation)

        // 员岗, the last stop before the end: 下一站即终点 draws two stops.
        c = step(plan, rideCaps, rideCard(1, 0.71), 1, 1160, 1, "员岗")
        assertEquals("当前站 员岗", c.primary)
        c = step(plan, rideCaps, rideCard(1, 0.71), 1, 1200, 1, "员岗")
        assertEquals("4", c.status)
        assertEquals("pages/on_bus_2", c.page)
        assertEquals("下一站 南村万博", c.primary)
        assertEquals("1站 南村万博下车", c.secondary)
        assertEquals(listOf("员岗", "南村万博"), c.stations!!.map { it.name })
        assertTrue(c.twoStation)

        // 高德 goes straight on to the walk; ColorOS's 到站 stays, with the exit, for 5 minutes.
        val last = rideCard(1, 0.71)
        val cards = mapOf(1 to last)
        c = step(plan, rideCaps, walkCard(2, "步行至 汕黄牛.牛肉海鲜自助"), 2, 1480, cards = cards)
        assertEquals("arrival", c.kind)
        assertEquals("7", c.status)
        assertEquals("南村万博", c.primary)
        assertEquals("南村万博", c.rightWhite)
        assertEquals("B口", c.leftLine)
        assertEquals("B口", c.secondaryLine)
        assertEquals("已到站", c.lockSubtitle)
        assertTrue(c.arrivalArt)
        c = step(plan, rideCaps, walkCard(2, "步行至 汕黄牛.牛肉海鲜自助"), 2, 1700, cards = cards)
        assertEquals("arrival", c.kind)

        // Five minutes on, the walk to the restaurant.
        c = step(plan, rideCaps, walkCard(2, "步行至 汕黄牛.牛肉海鲜自助"), 2, 1790, cards = cards)
        assertEquals("walk", c.kind)
        assertEquals("南村万博", c.primary)
        assertEquals("汕黄牛.牛肉海鲜自助", c.rightWhite)
        assertEquals("共步行1.1公里，15分钟", c.lockSubtitle)

        // The trip's end.
        val end = AmapTransitEntity.build(plan, rideCaps, JSONObject().put("title", "已到达 汕黄牛.牛肉海鲜自助"),
            2, AmapTransitCard.ARRIVE_FINAL_DESTINATION, null, null, "", "", "t")
        c = AmapTransitCard.of(AmapTransitCard.Trip.parse(end))!!
        assertEquals("final", c.kind)
        assertEquals("已到达 汕黄牛.牛肉海鲜自助", c.primary)
        assertEquals("已到达", c.rightWhite)
        assertEquals("全程46分钟", c.secondary)
    }

    @Test fun aSubwayArrivalGivesWayToItsWalkingNavigation() {
        step(ridePlan, rideCaps, rideCard(1, 0.7), 1, 0, 3, "大学城南")
        step(ridePlan, rideCaps, rideCard(1, 0.7), 1, 100, 1, "员岗")
        val cards = mapOf(1 to rideCard(1, 0.7))
        var c = step(ridePlan, rideCaps, walkCard(2, "步行至 汕黄牛.牛肉海鲜自助"), 2, 200, cards = cards)
        assertEquals("arrival", c.kind)
        c = step(ridePlan, rideCaps, walkCard(2, "步行至 汕黄牛.牛肉海鲜自助"), 2, 210, walkIsland = true, cards = cards)
        assertEquals("walk", c.kind)
    }

    @Test fun aSubwayArrivalEndsOnTheWayOut() {
        step(ridePlan, rideCaps, rideCard(1, 0.7), 1, 0, 3, "大学城南")
        step(ridePlan, rideCaps, rideCard(1, 0.7), 1, 100, 1, "员岗")
        val cards = mapOf(1 to rideCard(1, 0.7))
        var c = step(ridePlan, rideCaps, walkCard(2, "步行至 汕黄牛.牛肉海鲜自助"), 2, 200, cards = cards)
        assertEquals("arrival", c.kind)
        assertTrue(m.exited())
        assertTrue(!m.exited())
        c = step(ridePlan, rideCaps, walkCard(2, "步行至 汕黄牛.牛肉海鲜自助"), 2, 210, cards = cards)
        assertEquals("walk", c.kind)
    }

    /** The entity for capsule [current] with [status], as AmapTransitShare builds it. */
    private fun cardFor(card: JSONObject, current: Int, status: String,
                        live: AmapTransitEntity.Live?, walkNavi: JSONObject? = null): AmapTransitCard.Card {
        val entity = AmapTransitEntity.build(ridePlan, rideCaps, card, current, status, live, null,
            "", AmapTransitEntity.sentence(card), "t", walkNavi)
        return AmapTransitCard.of(AmapTransitCard.Trip.parse(JSONObject(entity.toString())))!!
    }

    @Test fun nearTheStationIsTheWaitingCard() {
        // 到达起始站附近: the ride after the walk, its stops all ahead, the walk's card for the rest.
        val c = cardFor(walkCard(0, "步行至 大学城南地铁站"), 1, AmapTransitCard.ARRIVE_ORIGIN_NEARBY,
            AmapTransitEntity.Live(3, -1.0, "", subwayTimes))
        assertEquals("waiting", c.kind)
        assertEquals(AmapTransitCard.ARRIVE_ORIGIN_NEARBY, c.status)
        assertEquals("大学城南", c.lockTitle)
    }

    @Test fun theSilentWalkHasItsButton() {
        val c = cardFor(walkCard(0, "步行至 大学城南地铁站"), 0, "", null)
        assertEquals("walk", c.kind)
        assertEquals("步行导航", c.button)
        assertEquals("步行至大学城南", c.lockTitle)
    }

    @Test fun theWalkingNavigationIsTheWalksCard() {
        // 10-05's channel 101, the first frame and one under way.
        val first = JSONObject("""{"status":1,"icon":9,"first_desc":"","second_desc":"直行97米",
            "remain_length":97,"remain_time":523,"route_total_dist":566,"route_remain_dist":566,
            "route_remain_time":523,"trigger_source":1,"isFromBus":true}""")
        var c = cardFor(walkCard(0, "步行至 大学城南地铁站"), 0, "", null, first)
        assertEquals("walk_navi", c.kind)
        assertEquals("", c.primary)
        assertEquals("直行97米", c.secondary)
        assertEquals("97米", c.rightWhite)
        assertEquals(0, c.progress)
        assertEquals("剩余566米，约9分钟", c.progressFrom)
        assertEquals("目的地", c.progressTo)
        assertTrue(!c.weakSignal)
        assertEquals("", c.button)
        val later = JSONObject("""{"status":1,"icon":9,"first_desc":"沿尚法街","second_desc":"直行270米",
            "remain_length":270,"route_total_dist":566,"route_remain_dist":299,"route_remain_time":279,
            "trigger_source":1,"isFromBus":true}""")
        c = cardFor(walkCard(0, "步行至 大学城南地铁站"), 0, "", null, later)
        assertEquals("沿尚法街", c.primary)
        assertEquals("直行270米", c.secondary)
        assertEquals(47, c.progress)
        assertEquals("剩余299米，约5分钟", c.progressFrom)
    }

    @Test fun aWeakGpsSaysSoOnTheWalk() {
        val weak = JSONObject("""{"status":1,"first_desc":"沿尚法街","second_desc":"直行270米",
            "remain_length":270,"route_total_dist":566,"route_remain_dist":299,"route_remain_time":279,
            "gpsSignalStatus":0,"trigger_source":1,"isFromBus":true}""")
        val c = cardFor(walkCard(0, "步行至 大学城南地铁站"), 0, "", null, weak)
        assertTrue(c.weakSignal)
        assertEquals("步行至大学城南", c.primary)
        assertEquals("卫星信号弱，请步行到开阔地带", c.secondary)
        assertEquals("步行至", c.leftWhite)
        assertEquals("大学城南", c.rightWhite)
    }

    @Test fun aWeakGpsMarksOnlyTheMovingCards() {
        val card = rideCard(2, 0.4).put("gpsSignalStatus", 0)
        val moving = cardFor(card, 1, AmapTransitCard.NEXT_STATION,
            AmapTransitEntity.Live(2, 0.4, "板桥", subwayTimes))
        assertTrue(moving.weakSignal)
        val waiting = cardFor(card, 1, AmapTransitCard.WAITING,
            AmapTransitEntity.Live(3, -1.0, "", subwayTimes))
        assertTrue(!waiting.weakSignal)
    }

    @Test fun theWayOutHoldsNothingElse() {
        step(ridePlan, rideCaps, rideCard(1, 0.7), 1, 0, 3, "大学城南")
        assertTrue(!m.exited())
    }

    @Test fun withoutAPlanTheRideNamesOnlyWhatItHasSeen() {
        assertEquals("waiting", step(null, rideCaps, rideCard(3, 0.02), 1, 0, 3, "大学城南").kind)
        var c = step(null, rideCaps, rideCard(2, 0.44), 1, 400, 2, "板桥")
        assertEquals("当前站 板桥", c.primary)
        // No name for the stop after 板桥: it stays 当前站 rather than guess.
        c = step(null, rideCaps, rideCard(2, 0.44), 1, 500, 2, "板桥")
        assertEquals("当前站 板桥", c.primary)
        c = step(null, rideCaps, rideCard(1, 0.7), 1, 700, 1, "员岗")
        c = step(null, rideCaps, rideCard(1, 0.7), 1, 800, 1, "员岗")
        assertEquals("下一站 南村万博", c.primary)
    }

    // ------------------------------------------------------------------ a change of line

    private val transferPlan = JSONObject("""{
        "epoi":{"name":"新塘"},"expensetime":"3600",
        "segmentlist":[
         {"bus_key_name":"7号线","bustype":"2","color":"86B81C","directionName":"燕山",
          "on_station":{"name":"大学城南"},"off_station":{"name":"裕丰围","is_trans":true},
          "via_st_list":[{"name":"深井"},{"name":"长洲"}],"footlength":"743","foottime":"737"},
         {"bus_key_name":"13号线","bustype":"2","color":"818530","directionName":"新沙",
          "on_station":{"name":"裕丰围"},"off_station":{"name":"新塘"},
          "via_st_list":[{"name":"双岗"},{"name":"南海神庙"},{"name":"夏园"}],
          "outport":{"name":"E3口","shield":"E3"},"footlength":"60","foottime":"51"}]}""")

    private val transferCaps = JSONArray("""[
        {"capsuleType":"0","icon":"bus_foot_a","subText":"13"},
        {"capsuleType":"2","text":"7号线","bgColor":"#86B81C"},
        {"capsuleType":"0","icon":"bus_foot_a","subText":"1"},
        {"capsuleType":"2","text":"13号线","bgColor":"#818530"},
        {"capsuleType":"0","icon":"bus_foot_a","subText":"3"}]""")

    private fun card7(left: Int) = JSONObject().put("title", "乘坐 地铁7号线").put("mainText", "7号线")
        .put("titleItems", JSONArray().put(t("${left}站")).put(t("后")).put(t(" · ")).put(t("裕丰围")).put(t("换乘")))
        .put("location", JSONObject().put("index", 1).put("remainStations", left))

    @Test fun aChangeOfLineReadsTheWayColorOSWould() {
        val plan = transferPlan
        assertTrue(AmapTransitEntity.fits(plan, transferCaps))
        step(plan, transferCaps, card7(3), 1, 0, 3, "大学城南")
        step(plan, transferCaps, card7(2), 1, 100, 2, "深井")
        step(plan, transferCaps, card7(1), 1, 200, 1, "长洲")
        // One stop to the change: 下一站即终点 becomes 下一站 (ya.b.W), around the transfer stop.
        var c = step(plan, transferCaps, card7(1), 1, 240, 1, "长洲")
        assertEquals("3", c.status)
        assertEquals("下一站 裕丰围", c.primary)
        assertEquals("1站 裕丰围换乘", c.secondary)
        assertEquals(listOf("长洲", "裕丰围", "双岗"), c.stations!!.map { it.name })
        assertEquals(listOf(false, true, false), c.stations!!.map { it.transfer })

        // Off at 裕丰围: 准备换乘, the next line's chip and its direction.
        val cards = mapOf(1 to card7(1))
        c = step(plan, transferCaps, walkCard(2, "步行至 裕丰围"), 2, 300, cards = cards)
        assertEquals("transfer", c.kind)
        assertEquals("6", c.status)
        assertEquals("准备换乘", c.primary)
        assertEquals("换乘", c.leftWhite)
        assertEquals("13", c.rightLine)
        assertEquals("往新沙", c.rightWhite)
        assertEquals("13号线(往新沙)", c.secondaryLine)
        assertEquals("13号线(往新沙)", c.lockSubtitle)
        assertEquals(listOf("长洲", "裕丰围", "双岗"), c.stations!!.map { it.name })

        // Then the walk between the platforms, to the next line's stop.
        c = step(plan, transferCaps, walkCard(2, "步行至 裕丰围"), 2, 340, cards = cards)
        assertEquals("walk", c.kind)
        assertEquals("裕丰围", c.primary)
        assertEquals("裕丰围", c.rightWhite)
        assertEquals("共步行60米，1分钟", c.lockSubtitle)
    }

    @Test fun wordsTheCardsAreBuiltFrom() {
        assertEquals("往美的大道", AmapTransitCard.x("美的大道方向"))
        assertEquals("往美的大道", AmapTransitCard.x("往美的大道"))
        assertEquals("7", AmapTransitCard.B("7号线", "2"))
        assertEquals("番29", AmapTransitCard.B("番29路(短线)", "1"))
        assertEquals("APM", AmapTransitCard.lineCode("APM线", "2"))
        assertEquals("1小时5分钟", AmapTransitCard.duration(3900))
        assertTrue(AmapTransitEntity.sameLine("7号线", "地铁7号线(燕山--美的大道)"))
        assertTrue(!AmapTransitEntity.sameLine("1号线", "11号线"))
        assertEquals("南村万博", AmapTransitEntity.alight(rideCard(3, 0.0)))
        assertEquals("B口", AmapTransitEntity.exit(rideCard(3, 0.0)))
    }
}
