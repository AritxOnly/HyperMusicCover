# ColorOS 17 锁屏高德公交/地铁逐站卡：逆向原理与移植现状

> ColorOS 侧逆向来源：`ColorOS17-v16-fuxi-FULL-20260928.zip`（super.img → system / system_ext），
> 高德地图 17.00.0.2005（腾讯应用宝，MD5 `f25c34c560e894f6d34f3b798e38a286`），反编译工具 jadx 1.5.3。
> 本模块侧的真机实测：2026-10-02，厦门地铁 2 号线一次完整行程（见 §4）。
> 下文类名带混淆的，均以这两个版本为准。

## 1. 这个功能在 ColorOS 上是怎么来的

ColorOS 17 锁屏上的高德「逐站地铁播报」**不是高德画的**，是 OPPO 自己的 SceneService 画的：

```
高德 JS 脚本
  └─ OppoIntelligentCard 设备（Java: il3，日志 tag LiveCardOppoIntelligentTemplate）
       └─ ContentProvider.call("shareIntent", {intentData=JSON})   authority = "IntelligentIntent"
            └─ SceneService · IntelligentIntentProvider
                 └─ GaoDe 公交模块：解析 JSON → 卡片数据（站名、线路色、地标背景图…）
                      └─ 流体云卡片 536879317
                           └─ SystemUIPlugin：锁屏沉浸式卡片（immersiveCardType = 2，模板渲染）
```

高德只提供**结构化的行程数据**；**每个站的背景图**是 OPPO 按站点坐标查内置地标表，再去 OPPO 的 CDN 取图。

和已经移植好的步行/骑行地图不同：

| | 步行/骑行地图（AmapNavScene） | 公交/地铁逐站（本页） |
|---|---|---|
| 卡片 id | 536879184 | 536879317 |
| immersiveCardType | 1（Surface，高德自己画） | 2（模板，系统画） |
| 画面由谁画 | 高德 `AMapImmerseNaviService` 交回 SurfacePackage | OPPO SceneService + SystemUIPlugin |
| 高德给的东西 | 一张地图画面 | 一段 JSON |

## 2. ROM 里的关键位置

| 组件 | 路径 | 作用 |
|---|---|---|
| SystemUIPlugin | `/system_ext/app/SystemUIPlugin/SystemUIPlugin.apk` | 锁屏流体云/沉浸式卡片的宿主 |
| 白名单 | SystemUIPlugin `res/raw/sys_systemui_seedling_package_config.xml` | 高德三张卡 `536878018 / 536879184 / 536879317` 均 `lockImmersiveEnable="1" lockImmersiveDefault="1"` |
| SceneService | `/my_stock/priv-app/SceneService/SceneService.apk` | 接收高德数据、生成公交卡片、选背景图 |
| 公交模块 | `com.coloros.scene.business.publicTransport.gaode.*` | 高德公交导航全部逻辑 |
| 地标表 | `com.oplus.sdp.lb.b`（高德）/ `lb.a`（百度） | 42 个城市、111 个地标、357 个坐标点，硬编码 |
| 图片 URL | `com.oplus.sdp.lb.c` | CDN 路径拼接 |

SceneService 的公交模块只在 **ColorOS 17 及以上**初始化（`GaoDePublicTransportModule.c()` 检查 `isAtLeastOS17`）。

## 3. OPPO 那条协议（澎湃上不走）

高德的脚本以 `il3`（日志 tag `LiveCardOppoIntelligentTemplate`）把 entity 交给 `IntelligentIntent` provider：
`queryFeature` / `shareIntent` / `deleteIntent`，intent 名 `com.autonavi.minimap#Navigation.NotifyPublicTransportStatus`，
`intentEntity` 即 SceneService 的 `GaoDePtIntentEntity`（字段见 `AmapTransitCard.Trip`）。

**澎湃上高德的脚本不 `bizBegin(10200)`，这条路本身没有 entity。** 但 `AmapOppoBridge.kt` 必须留着：

**高德只在通道上有已连接设备时才往 113 发数据。** 2026-10-06 实测：去掉桥，开始导航只有 `bizBegin(113)`、`bizBegin(103)`
和 103 的卡片，113 一条消息都没有（没有计划、没有实时）；装回桥，开始导航后 3.2 s 计划（`type 24`，21 KB）和实时（`type 25`）都到了。
桥做的事：把 OPPO 智能卡（10200 / `il3`）的设备配置注入每条通道的设备表（`xn0.a`），在脚本开每条通道时补 `bizBegin(10200)`，
提前给 `il3` 设好 provider 和 intent，并冒充 `IntelligentIntent` provider 让它判定为已连接。`il3` 转发过来的 `shareIntent` 只是
103/113 原始载荷的再包装，没有 `intentName`，应答后丢弃——数据从通道上直接读。

## 4. 澎湃上高德实际发的是什么（真机实测）

**关键结论：高德的脚本在小米机型上不 `bizBegin(10200)`**，也就是不启用 OPPO 智能卡那条路。所以 §3 的协议在真机上从来没有数据流过（早期探针长期 `acquires=0` 就是这个原因）。

高德实际开的是**它自己的两条通道**，通过 `NativesModuleWearable.sendMessage(bizType, payload)` 下发，两条都**每 1–3 秒一次**（103 和 113 并行，不是只在换站时）：

### bizType 103 · `third_sdk_oppo_aod` —— 行程计划卡

```json
{"cardData":{
  "planData":[{"icon":"bus_foot_a","subText":"13"},{"text":"7号线","bgColor":"#86B81C"},{"text":"3号线","bgColor":"#FFA500"},{"text":"番29路"}],
  "title":"步行至 大学城南地铁站","mainText":"4号线","subText":"大学城南(E口)",
  "remainMessage":"21分钟·08:21到达",
  "titleItems":[{"text":"大学城南"},{"text":"(E口)"},{"text":"进站"}],
  "subTitleItems":[{"text":"4号线"},{"text":"(南沙客运港方向)"}],
  "arrived":false,"location":{"index":0,"persent":0,"remainStations":1}
}}
```

- `planData` 是整条行程的胶囊，一个胶囊一段：步行是 `icon:"bus_foot_*"` 或 `capsuleType:"0"`，地铁 `capsuleType:"2"`，公交 `"1"`。**线路颜色就在胶囊的 `bgColor` 上**（`#86B81C`）。步行胶囊上的「13」是分钟数，不是线路。
- `title` 含「步行」的那几张卡是**步行段**，高德自己有岛，本模块不接管。
- `titleItems` **不是固定槽位**：真机发过「1站」「后」「 · 」「邮轮中心」「出站」，也就是「1站后 · 邮轮中心出站」——剩余站数在第 0 位。按位置读会把站数当站名（曾导致锁屏显示「下一站 2站」）。

### bizType 113 · `amap_glass` —— 实时数据

```json
{"datas":"[{\"type\":25,\"data\":{
  \"realtime\":{\"buses\":[{\"line\":\"440100017560\",\"station_index\":\"8\",
     \"trip\":[{\"grade_words\":\"已进站\",\"station_left\":\"0\",\"speed\":\"5\",
        \"track\":{\"xs\":\"113.38520500\",\"ys\":\"22.93589000\"}}]}]},
  \"subway\":[{\"lineId\":\"440100023034\",\"tripTime\":[{\"mainTitle\":\"2分钟\"}]}],
  \"arriveRemind\":{\"remainStopNum\":7,\"remainTime\":4631,\"remainLength\":26553}}}]"}
```

- `arriveRemind.remainStopNum`：**公交**的剩余站数；地铁不看它。
- `arriveRemind.curStopName` / `nextStopName`：**高德为「进行中的这一程」点名的两个站**，压过 103 卡片那句 `titleItems`——那句话指的是线路终点，不看它会把还有六站的行程显示成「下一站 终点站」。
- `realtime.buses[].trip[].track` 是车辆坐标（`xs` 经度、`ys` 纬度，字符串）。
- `subway[].tripTime[].mainTitle` 是地铁倒计时（「2分钟」）。

### bizType 113 · `type 24` —— 整条行程计划（开始导航时就到）

`bizBegin(103)` 之后约 30 ms 到（路线页每展示一个方案也会发一次，所以要和卡片的胶囊对得上才算这趟的）：

- `segmentlist[]`，每段一次乘车：`bus_key_name`（「7号线」）、`bustype`（2 地铁 / 1 公交）、`color`、`directionName`、`busid`、
  `on_station`（含 `start_time` / `end_time` 首末班）、`via_st_list[]`（名字 + 坐标）、`off_station`（`is_trans`）、
  `outport`（出站口，带坐标）、`inport`（进站口）、`footlength` / `foottime`（这段之前的步行）、`driver_coord_list`（两端坐标）；
- `spoi` / `epoi`（起终点名字和坐标）、`endfootlength` / `endfoottime`（最后一段步行）、`allLength`、`expensetime`（全程秒数）。

这就是 ColorOS `naviInfo[]` 需要的全部内容，**本模块据此拼出完整 entity**（§6）。

### 113 `type 25` 里认得的字段

- `locationData.groupIndex` = 当前是第几段（和 103 卡的 `planData` 胶囊同一个计数：步行 0 / 乘车 1 / 步行 2），
  `linkIndex` = 这段里第几站。`arriveRemind` 的站数只对 `groupIndex` 那一段有效（步行时它数的是别的）。
- `arriveRemind.curStopName` / `remainStopNum`：列车**到站**时才更新（地下没有 GPS，`speed` 恒为 0），站与站之间一动不动。
- `arriveRemind.tipType`：乘车中 4，**这段坐完 48**（10-05 下车那一刻和 103 卡换成步行卡同时出现）。
- 高德**不发「到站」卡**：下车时 103 卡直接从「乘坐 地铁7号线」跳到「步行至 目的地」，之后「已到达 X」+ `arrived:true`，紧跟 `bizEnd(103)`。

## 5. 每站背景图（地标图）

### 5.1 什么时候换图

- **途中**（status 3/4/5）：取「正在显示的那个站」的坐标（`via_st_list` 里同名站，找不到用 `off_station`），在 `destCitycode` 对应城市的地标表里找**任意锚点 0.8 km 以内**的第一个地标 → 显示它的动图。没匹配上就没有地标，本模块用全国默认图顶上（调暗）。列车每开过一站，背景图可能换成另一个地标。
- **到站**（status 7）：用出站口（`port_list` 里与 `exitName` 匹配的口）或下车站坐标查地标；
  - 地铁：地标 → 城市默认图 → 全国默认图；
  - 公交：只认地标，然后直接全国默认图。
  - 日/夜按该坐标当地日出日落判断（`pa.f`）。

### 5.2 图片地址（公开，无需鉴权，2026-10-01 实测）

```
https://ocs-cn-south1.heytapcs.com/pantanal-servicegov-cn/intent/baidu/
  busnav/<城市>/<地标>.webp        动图，807×378，29 帧
  busnav/<城市>/<地标>.png         静图，807×378
  landmark/<城市>/<地标>.png       地标名字图，423×66，白字
  busnav/<城市>/DefaultDay.png / DefaultNight.png
  busnav/subway/NationalDefaultDay.png / NationalDefaultNight.png
  busnav/bus/DefaultDay.png / DefaultNight.png
```

417 个地址中 365 个可用，缺的是少数地标和几个城市默认图，所以必须逐级回退。

### 5.3 地标表格式

```
城市目录, 高德城市码, [ 地标名, [ (lat, lng) ... ] ] ...
beijing,  010,  ForbiddenCity  (39.902978,116.399541) (39.914842,116.391537) ...
shanghai, 021,  TheBund        (31.241969,121.490214) ...
```

高德那一半是 GCJ-02，与高德下发的站点坐标一致；百度那一半是 BD-09，不能混用。

## 6. 移植到本模块（HyperOS）

**原则：ColorOS 的东西原样照搬，只有高德不告诉我们的才自己推。** ColorOS 上高德直接给 `status`（里程碑），
这里没有，只能从「卡片在哪一段」和「这段还剩几站」推出来；其余（卡片文案、选站、换乘、到站、终点卡、各种计时）全部是 SceneService 的规则。

```
高德进程                                                   SystemUI
NativesModuleWearable ── 103 卡 / 113 计划+实时 ──┐
                                                  ├─ AmapTransitShare（线程、计时、发送）
                         AmapTransitMilestones ───┤    └ AmapTransitEntity → GaoDePtIntentEntity
                                                  │         ├─ AmapTransitIsland（焦点通知）── AmapTransitCard
                                                  │         └─ op transit ───────────────────→ AmapTransitScene ── AmapTransitCard
```

### 6.1 实体：`AmapTransitEntity.kt`

- **胶囊就是分段**：103 卡的 `planData[]` 每个胶囊一段（步行胶囊 = 步行段），第 k 个乘车胶囊 ↔ 计划的第 k 个 `segmentlist`；
  两边线路名逐个对得上（「7号线」对「地铁7号线(燕山--美的大道)」，「1号线」不对「11号线」）才认这份计划是这趟的（`fits`）。
- 乘车段从计划填：线路/方向/颜色、上车站与首末班、途经站（带坐标，给地标用）、下车站、出站口；步行段填长度和时长。
- 后面还有乘车的那一段，下车站标成换乘站（`isTransferStation`）。
- 当前段补上：剩余站数、实时到站（地铁 `subway[].tripTime`、公交 `realtime.buses[].trip`，按 `busid` 认自己的线）、卡片句子里的出站口（「(B口)」）。
- **没有对得上的计划时**（模块中途重启、路线页的计划不是这趟）：只有胶囊和卡片的信息，途经站用「已经到过的站 + 不知道名字的占位」凑出 ColorOS 下标算法要的长度。

### 6.2 里程碑：`AmapTransitMilestones.kt`（纯 Kotlin，可在电脑上跑）

| 情形 | status | 说明 |
|---|---|---|
| 步行段（开头或换乘）离下一程车站不到 200 m（113 `groupRemainDistance`，回到 300 m 以上撤回） | 1 到达起始站附近 | 高德的阈值读不到，这是我们定的；GPS 弱距离不动时不出现，直接候车 |
| 第一次看到某个乘车段，且剩余站数 = 全部站数 | 2 候车 | 直到第一站过去 |
| 剩余站数减少（到了一站） | 5 当前站 | 停 30 s（`DWELL_MS`，不是 ColorOS 的数，模仿停站） |
| 之后 | 3 下一站 | 剩 1 站时 4 下一站即终点（ColorOS 的 `W()` 遇到换乘会改回 3） |
| 卡片离开一个乘车段、后面还有乘车 | 6 到达换乘站 | 保留 30 s，然后是换乘步行 / 下一条线的候车 |
| 卡片离开一个乘车段、后面没有乘车 | 7 到站 | 地铁保留 **5 min**（`GaoDePtRideCodeDeferBindManager`），刷码/刷卡出站（`RideCodeExit`）或高德的步行导航岛（1236）出现就提前结束；公交 30 s |
| 最后一段就是乘车，且高德说坐完（剩 0 / `tipType 48`） | 7 | 地铁 5 min、公交 35 s 后出终点卡（`GaoDePtFinalDestCardManager`） |
| 卡片 `arrived` / 「已到达 X」 | 8 终点 | 终点卡 30 s，之后什么都不收 |
| 当前段是步行 | — | ColorOS 的静默步行卡（`ya.n.f`），带「步行导航」按钮（`AmapFootNavi`，只在按下时启动） |
| 步行段且高德步行导航在跑（101 通道 `trigger_source 1`） | — | ColorOS 的步行导航卡（`ya.n.d`，弱 GPS 时 `E()`），同一张岛；高德自己的 1236 拦下，转向图取自 1236；点开是高德的锁屏地图 |
| 卡片 `offRoute` | — | 偏航卡（`ya.f`） |

没有计划时，下一站的名字不知道，就停在「当前站 X」而不是猜一个。

### 6.3 卡片：`AmapTransitCard.java`（SceneService `ya.b` 等逐函数移植）

`e0`（里程碑说哪一站）、`d0` / `X`、`Y`（「N站 XX下车 / 换乘」）、`l` / `h` / `m` / `o` / `n`（站点概览，两站或三站、换乘站、换乘线路角标）、
`ya.e` 三节点/两节点、`W`、`k0`、`f0` / `b0`、`j0`（公交↔地铁不画换乘）、候车卡 `p` / `q` / `k` / `g`（首末班、「列车预计 N 分钟进站」）、
到站卡 `b`（地铁有出站口时左半是出站口色块）、终点卡 `ya.a`（「已到达 X / 全程N分钟」）、偏航卡 `ya.f`、步行卡 `ya.n.f`、路由 `GaoDePtNaviSceneRouter.j`。
文案逐字取自 SceneService 的 `gaode_pt_*` 资源。离线测试：`AmapTransitCardTest`（10-05 真实行程 + 一个换乘计划，逐步断言）。

### 6.4 焦点通知：`AmapTransitIsland.kt`

| ColorOS 卡片 | 澎湃 `param_v2`（scene `template_v2`） |
|---|---|
| 胶囊左 / 右 | `param_island.bigIslandArea.imageTextInfoLeft / Right`；线路色那一截（`capsule*TextLine`）画成色块图片放 `picInfo`，白字进 `textInfo` |
| `cardPrimaryInfo` | `baseInfo.title` |
| `cardSecondaryLineName` + `cardSecondaryInfo` | `baseInfo.content` |
| 线路 + 方向 | `baseInfo.subContent` |
| `titleInLock` | `ticker` / `aodTitle` |
| `cardStationOverview` | `progressInfo`（设计图那根条：车头 / 站点针 / 终点旗） |
| 地标 | `bgInfo` |

id 1239；高德自己的 1237 在卡片在时拦掉；1236（高德步行导航岛）被监视，用于让出步行卡和结束地铁到站保留。
`AmapFocus.java`（SystemUI）让插件的授权链放行高德的焦点通知（`canCustomFocus` / `canPassXMSPermission`）。

### 6.5 锁屏页：`AmapTransitScene.java`

画同一张卡：线路色块 + 方向、主副文案（副文案前的线路色块：换乘的下一条线、到站的出站口；候车卡显示下一班车/首末班）、
两站或三站的概览（列车在两站之间还是停在中间那站，换乘站 ⇄ + 下一条线角标）、地标（在途：卡片点名那一站 0.8 km 内的地标；
到站：出站口 → 地标 → 城市默认（地铁）→ 全国默认）。步行卡不认领页面（那是高德自己的步行导航地图）。

## 7. 调试命令

```sh
# 高德进程状态：当前段 / status / 卡片种类 / 计划是否对得上 / 保留中的里程碑 / 终点卡
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --ez max true      # 每种载荷最近一份（整份）
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --ez events true   # 每次发送的时刻

# 把一份载荷当作刚收到（base64，103 卡或 113 datas）；begin/stop = bizBegin/bizEnd(103)
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --es transit raw --es json '<base64>'
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --es transit begin|stop
# 停站 30 s / 地铁到站 5 min 缩成十分之一（回放用），slow 恢复
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --es transit fast|slow
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --es transit demo|end

# 用上一次导航的真实计划逐站模拟整趟（十分之一时长，期间挡住高德的真实数据，不会在高德里起导航）
adb shell am broadcast --receiver-foreground -a com.os4.musiccover.AMAPPROBE --es transit sim
# 整趟回放（10-05 那次 7 号线 + 补写的计划）：.scratch/amap-ledger/replay1005.py
# 高德在后台会被 Greezer 冻住，发给它的广播都要带 --receiver-foreground，否则被扣下、乱序送达

# 只测 SystemUI 页面
adb shell am broadcast -a com.os4.musiccover.PROBE -p com.android.systemui --es op transit --ez demo true
adb shell am broadcast -a com.os4.musiccover.PROBE -p com.android.systemui --es op transit --es do end
```

## 8. 代码位置

| 文件 | 进程 | 作用 |
|---|---|---|
| `AmapTransitShare.kt` | 高德 | 读 103/113、线程与计时、ColorOS 的生命周期规则、发给 SystemUI 和岛、探针、`transit sim` |
| `AmapOppoBridge.kt` | 高德 | 给 113 通道一个「已连接设备」，否则高德不发计划和实时（§3） |
| `AmapTransitMilestones.kt` | 高德 | 推里程碑（纯逻辑） |
| `AmapTransitEntity.kt` | 高德 | 计划 + 胶囊 + 卡片 + 实时 → `GaoDePtIntentEntity` |
| `AmapTransitCard.java` | 两边 | SceneService 卡片构建器的移植 |
| `AmapTransitIsland.kt` | 高德 | 焦点通知（id 1239）与进度条三张图 |
| `AmapFootNavi.kt` | 高德 | 步行卡「步行导航」按钮：经 `IFootNaviService.startNaviPage` 让高德导航这段步行 |
| `RideCodeExit.kt` | SystemUI | 出站信号：乘车码页面到前台、小米智能卡的出站扣费通知 → 高德 `transit exited`、小爱建议 `METRO_TRIP do=exit` |
| `MetroCodeIsland.kt` | 小爱建议 | 乘车码岛；按 ColorOS 的行程规则只在起点、高德计划的上下车站弹（`AmapTransitShare.tellMetro` 发站名） |
| `AmapFocus.java` | SystemUI | 焦点插件授权放行 |
| `AmapTransitScene.java` | SystemUI | 锁屏页、地标图下载缓存 |
| `AmapTransitLandmarks.java` | 两边 | OPPO 地标表、CDN、0.8 km 匹配、按坐标认城市 |

## 9. 已知边界与风险

- **高德不给 status**：候车何时结束、停站多久、换乘/到站显示多久，都是推出来的（§6.2），不是 ColorOS 的原样。
  地铁站与站之间没有任何实时信号，所以「候车」会一直到第一站过去。
- **出站信号**：ColorOS 靠 OplusAppSwitchManager 认乘车码页面（小程序按 appId）、靠 OPPO 钱包的 `bus/swipe` 认刷卡。这里用 SystemUI 的 `MiuiTopActivityObserver` 认前台页面——拿不到小程序 appId，所以微信是「任一小程序」、支付宝是「打开就算」；刷卡认小米智能卡出站扣费后发的通知（正文「A -> B：x元」）。都只在地铁到站保留期间生效。开始导航不再自动进高德步行导航（原版高德也不进）。
- **真机只验过 10-05 那一次地铁行程的载荷**；公交（`realtime.buses` 的字段）、换乘、偏航、只有乘车没有末段步行的行程都没有真机记录，
  代码按 ColorOS 写，载荷读法是推测。
- 计划只在开始导航时发；模块或高德中途重启会丢计划，走没有计划的退化路径。
- 高德的通道载荷是私有格式；地标图来自 OPPO CDN，随时可能变。
