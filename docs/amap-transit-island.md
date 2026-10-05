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

## 3. OPPO 那条协议（本模块只在桥上用它）

高德脚本要以 `il3` 的身份把数据交给 `IntelligentIntent`：

1. `acquireUnstableContentProviderClient("IntelligentIntent")`；
2. `call("queryFeature", "querySupportIntent", {intentName})`，只有回 `{"code":0,"data":"{\"querySupportIntent\":true}"}` 才算支持；
3. `call("shareIntent", null, {intentData})`；结束用 `deleteIntent` / `deleteEntity`。
   另有 `getSid`、`enableIntelligentIntent`、`querySupportIntentByPackage`。

- intent 名：`com.autonavi.minimap#Navigation.NotifyPublicTransportStatus`
- 返回统一是 `Bundle{ "result": CallResult JSON }`，`CallResult = {code, message, data}`；code 0 成功，1003 不支持。
- `intentData` 外层：`{intentName, intentVersion, identifier, timestamp, serviceId{}, intentAction{actionType, actionStatus,…}, intentEntity{…}, extra{}}`。
- `intentEntity` 就是 SceneService 的 `GaoDePtIntentEntity`：`status`（里程碑 1-7）、`naviInfo[]`（每段一段，当前段 `isCurrent`）、`destCitycode`、`destStation` / `destLatitude` / `destLongitude`、`exitName`、`guideInfo`、`deepLink`、`arrived` / `offRoute` / `gpsSignalStatus`、`path[]`（整条折线，页面用不到）。

`naviInfo[]` 每项：`transportType`（`0`步行 `1`公交 `2`地铁 `12`轮渡 `13`索道 `100`打车 `102`骑行）、`lineName` / `lineDirection`、`lineBgColor` / `lineTextColor` / `borderColor`、`remainStations`、`on_station{stationName, waitInfo{realTime[]}}`、`off_station{stationName, coord, port_list[{name, shield, coord, status}]}`、`via_st_list[{name, coord, isTransferStation}]`。

**里程碑 `status`**（SceneService 的枚举，也是页面文案的依据）：

| code | 含义 | 主文案 |
|---|---|---|
| 1 | 到达起始站附近 | 上车站名 |
| 2 | 候车 | 上车站名 + 实时到站 |
| 3 | 下一站 | 「下一站 XX」 |
| 4 | 下一站即终点 | 「下一站 XX」（**高德不发 4**，保留兼容） |
| 5 | 到达普通站 | 「当前站 XX」 |
| 6 | 到达换乘站 | 「准备换乘」+「可换乘 N 号线」 |
| 7 | 到站 | 「已到达 XX」+ 出站口 |

副文案优先 `guideInfo`，否则「N站 XX下车」。当前下标 = `clamp(via.size − remainStations, 0, via.size−1)`（`com.oplus.sdp.ya.e`）。

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

### 剩余站数的取法（`AmapTransitShare.show`）

| 段类型 | 用的字段 |
|---|---|
| 公交 | `arriveRemind.remainStopNum`（更细，优先） |
| 地铁 | `cardData.location.remainStations` |
| 兜底 | `titleItems` 拼回整句后抠出的「N站」 |

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

### 6.1 高德进程 · `AmapTransitShare.kt`

两件事并行：

**（a）冒充 `IntelligentIntent` provider**（§3 那条协议）
- hook `ContentResolver.acquire(Unstable)ContentProviderClient`：只有原本返回 **null** 且问的是 `IntelligentIntent` 时才换成 Settings 的 client 交回去（真 ColorOS 上不动）；
- hook `ContentProviderClient.call`：只拦这一批 client，按 SceneService 的格式回答 `queryFeature` / `shareIntent` / `deleteIntent` / `getSid`，不会真的打到 Settings；
- 收到合法的 `shareIntent` 就把 `intentEntity`（去掉 `path`）转给 SystemUI（`op transit`）。

**（b）桥接并直接读通道**
- `bridgeOppo`：把 10200（`thid_sdk_template_oppo_intelligent` → `il3`）的设备配置注入进 `xn0.a` 返回的设备表，让**脚本会开的每条通道**都把 `il3` 一起建起来；同时在 `NativesModuleWearable.bizBegin/bizBeginWithData` 旁路调用 `WearableService`，把 `bizBegin(10200)` 连同 `{authority, intentName}` 的 begin data 一起补上。这样 `il3.isSupport` 问到的就是我们自己的假 provider，OPPO 那条路真正跑起来。（代价是这套注入是否真的贡献了数据，只能靠探针的 `shares=` / `bridge:` 两行判断。）
- `watchWearable`：hook `NativesModuleWearable` 的 `bizBegin / bizBeginWithData / bizEnd / sendMessage / sendNotify / sendLockScreenMessage`，`sendMessage(103|113, payload)` 送进 `ride()`。
- `ride()` + `show()`：把 103 和 113 合成一份 ColorOS 形状的 `GaoDePtIntentEntity`——线路、方向、线路色、剩余站数、里程碑 status、上下车站、出站口、实时到站、坐标、deepLink——再走 `tell()` 交给 SystemUI。**SystemUI 端和焦点岛都不用改**，因为喂给它们的是同一份形状。
- 步行段（`title` 含「步行」）不接管，交给高德自己的岛；行程结束 `clear()` 撤销。
- 状态重复时 60 秒（`KEEPALIVE_MS`）补一次，免得 SystemUI 把长区间当结束。

### 6.2 高德进程 · `AmapTransitIsland.kt` 与 `AmapFocus.java`

高德在澎湃上只有步行/骑行发焦点通知（id 1236），公交/地铁没有。所以**以高德身份补发一条焦点通知**：

- **id 1239**（避开 1236，以及高德自己的 `XiaomiUAConnectedDevice` 1237）；卡在时把 1237 拦掉（`handle()` hook `NotificationManager.notify`），免得一条行程两个岛。
- 三种样式，探针可切（`--es island card|template|flat`）：
  - **`template`（默认）**：系统大模板 `param_v2`——baseInfo（里程碑大字 + 线路和方向 + 副文案）、multiProgressInfo（按剩余站数走的进度条，线路色，副文案在条子上方重复一行）、picInfo（线路号圆牌）、bgInfo（地面）。
  - **`card`**：`miui.focus.rv` 自定义布局 `res/layout/mc_transit_card.xml`，由 SystemUI 从**模块包**inflate（高德有 `QUERY_ALL_PACKAGES`，能引用）。176dp、24dp 圆角，地面（线路色渐变 + 地标图，在**高德进程**里下载缓存）、线路色胶囊、方向、大字里程碑、剩余站数、底部三节点轨道（复用 `AmapTransitScene.Track`）。ticker / AOD / 超级岛在 `miui.focus.param.custom`。
  - **`flat`**：protocol 1 两行小模板。
- `FLOAT_AT = {4,6,7}`：下一站即终点、换乘、到站时上浮一次；同一状态重发不浮。
- **`AmapFocus.java`（SystemUI 进程）是这套能成立的前提**。焦点插件会把 1239 直接丢掉：

  ```
  FocusPlugin: onInflateSuccess 0|com.autonavi.minimap|1239|null|10385
  FocusPlugin: onAuthFailed     0|com.autonavi.minimap|1239|null|10385  com.autonavi.minimap
  FocusPlugin: removeByKey / removeFocusNotificationByKey / removeIslandDataByKey
  ```

  死因不是跨包 `RemoteViews`（那只解释 `onInflateSuccess`），而是 `FocusNotificationController.fetchAuthResult` 里的 `canCustomFocus(pkg)`——一张云端名单，高德不在上面。`canPassXMSPermission` 是同一条路上更早的一道门。

  `NotificationSettingsManager` 定义在控制中心插件自己的 APK 里，由 SystemUI 运行时才构造的 class loader 加载，所以 `AmapFocus` 盯着 `BaseDexClassLoader` 的构造函数等它出现，然后对 `com.autonavi.minimap` 这一个包名的 `canCustomFocus` / `canPassXMSPermission` 答 true，别的一概不动。入口在 `Main.java`。

### 6.3 SystemUI · `AmapTransitScene.java`

一个新的 `ImmersiveScene`，进程内自己画，照 OPPO 五一路那种样式：

- 线路色底、线路/方向、里程碑文案（`Frame.milestone`）、**横向三节点实时进度**（当前站居中加粗，换乘站画成带 ⇄ 的胶囊并在上方挂换乘线路号方形徽标，颜色取换乘那条线的颜色）；
- 三节点只画**真正被点名的站**：高德经常把同一个站放在两个槽位（下一站和终点常常同站），重复的只留一个，空位从**右往左**排（一个名字站在它旁边那站该在的位置，不是被顶到左边）；名字和轨道不重叠；
- 中间是地标动图和名字图；地面（线路色 → 近黑 + 地标静图）画在 **shade 窗口之下的 `CountdownScene.GroundSurface`** 上——锁屏玻璃行采样的是窗口后面的东西，窗口里的 View 采不到；
- 地标动图**只播一遍就停**（`AnimatedImageDrawable` `setRepeatCount(0)`），状态真的变了才 `replayArt()` 重播；keepalive 重发不重播；
- 换乘线路号取自当前段之后的第一段（`Trip.nextLine`）；
- 公交/地铁段时由它接管高德的焦点岛（在 `ImmersiveHost.SCENES` 里排在地图**前面**），步行段仍交给 `AmapNavScene` 的地图；`STALE_MS` 十分钟无数据视为结束。

`AmapTransitLandmarks.java` 是 OPPO 地标表 + CDN + 0.8 km 匹配（原样照搬 §5.3）。

## 7. 调试命令

```sh
# 高德进程，一次拿全：脚本对设备层的调用、每条通道的采样、
# 假 provider 的计数、焦点通知状态、bridge 结果、send 频率
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --ez full true
# 只取某一部分
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --ez max true     # 整本 ledger（一次行程的全部载荷）
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --ez events true  # 每次发送的时刻和大小（看间隔）

# 把一段真载荷重新走一遍 ride()（试载荷形状，不用坐车）
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --es transit raw --es json '<base64>'

# 切换焦点通知样式并立刻重发
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --es island card|template|flat

# 整条链路演示：高德进程假装收到一段地铁数据 → 焦点岛 + 转给 SystemUI
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --es transit demo
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --es transit end
# 逐段（进站/乘车/换乘/到达）
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --es transit 乘车

# 只测 SystemUI 页面（北京 1 号线，下一站天安门东，会匹配故宫地标图）
adb shell am broadcast -a com.os4.musiccover.PROBE -p com.android.systemui --es op transit --ez demo true
adb shell am broadcast -a com.os4.musiccover.PROBE -p com.android.systemui --es op transit --es json '<intentEntity JSON>'
adb shell am broadcast -a com.os4.musiccover.PROBE -p com.android.systemui --es op transit --es do end
# 不点焦点岛，直接开关这一页
adb shell am broadcast -a com.os4.musiccover.PROBE -p com.android.systemui --es op immersive --es id amap-transit --es do open
adb shell am broadcast -a com.os4.musiccover.PROBE -p com.android.systemui --es op immersive --es id amap-transit --es do close
```

## 8. 代码位置

| 文件 | 进程 | 作用 |
|---|---|---|
| `AmapTransitShare.kt` | 高德 | 假 `IntelligentIntent` provider；桥接 10200；读 103/113 合成 entity 转给 SystemUI |
| `AmapTransitIsland.kt` | 高德 | 公交/地铁段的焦点通知（id 1239，自定义大卡 / 大模板 / 小模板） |
| `res/layout/mc_transit_card.xml` | SystemUI inflate | 焦点大卡的布局（`res/raw/mc_keep.xml` 保住它不被 R8 删） |
| `AmapFocus.java` | SystemUI | 让高德的焦点卡过 `FocusPlugin` 的授权链 |
| `AmapImmerse.kt` | 高德 | 启动上面的 hook；SystemUI 重启时重发最后一次状态；探针 |
| `AmapTransitScene.java` | SystemUI | 数据模型、选站/选图规则、图片下载缓存（`cache/mc-transit/`）、页面绘制 |
| `AmapTransitLandmarks.java` | SystemUI | OPPO 地标表（42 城）、CDN 地址、0.8 km 匹配 |
| `ImmersiveHost.java` | SystemUI | 场景列表里排在地图前面，只在公交/地铁段认领高德的焦点岛 |
| `Main.java` | SystemUI | `op transit`、`AmapFocus.install` |

## 9. 已知边界与风险

- **真机验证范围**：2026-10-02 的厦门地铁 2 号线一次行程（103/113 通道、载荷读法、焦点卡上屏都以此为准）。**公交路径（`transportType 1`）与换乘站（status 6）没有真机记录**，代码有分支但不等于验证过。
- **桥接 10200 是否真的贡献数据未知**：`shares=` 长期为 0 也说明不了问题（§4 的数据全走 103/113）。要判断只能看探针的 `bridge:` 和 `shares=`。
- **息屏（AOD）表现没有验证记录**：岛的 `aodTitle/aodPic` 和页面的 doze 分支都在，但没见实测。
- 高德的通道载荷是**私有格式**，随版本可能变；升级高德后 `ride()` 的读法要重新对。
- 图片来自 OPPO CDN，OPPO 随时可能改路径或加鉴权；地标缺失靠逐级回退兜住。
