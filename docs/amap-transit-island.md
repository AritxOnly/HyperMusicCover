# ColorOS 17 锁屏高德公交/地铁导航岛：逆向原理

> 来源：`ColorOS17-v16-fuxi-FULL-20260928.zip`（super.img → system / system_ext），
> 高德地图 17.00.0.2005（腾讯应用宝，MD5 `f25c34c560e894f6d34f3b798e38a286`，与用户手机上的版本一致）。
> 反编译工具 jadx 1.5.3。下文类名带混淆的，均以这两个版本为准。

## 1. 一句话结论

ColorOS 17 锁屏上的高德「逐站地铁播报」**不是高德画的**，而是 **OPPO 自己的 SceneService 画的**：

```
高德 JS 脚本
  └─ OppoIntelligentCard 设备（Java: il3）
       └─ ContentProvider.call("shareIntent", intentData=JSON)   authority = "IntelligentIntent"
            └─ SceneService · IntelligentIntentProvider
                 └─ GaoDe 公交模块：解析 JSON → 拼卡片数据（站名、线路色、地标背景图…）
                      └─ 流体云 Seedling 卡片 536879317
                           └─ SystemUIPlugin：锁屏沉浸式卡片（immersiveCardType = 2，模板渲染）
```

高德只提供**结构化的行程数据**（线路、站点、坐标、剩余站数）。**每个站的背景图**是 OPPO 根据站点坐标
查一张内置的「城市地标表」，再到 OPPO 的 CDN 上取图片。

这和已经移植好的步行/骑行地图不同：

| | 步行/骑行地图（已移植：AmapNavScene） | 公交/地铁逐站（本次） |
|---|---|---|
| 卡片 id | 536879184 | 536879317 |
| immersiveCardType | 1（Surface，高德自己画） | 2（模板，系统画） |
| 画面由谁画 | 高德 `AMapImmerseNaviService` 交回 SurfacePackage | OPPO SceneService + SystemUIPlugin |
| 高德给的东西 | 一张地图画面 | 一段 JSON |

## 2. ROM 里的几个关键位置

| 组件 | 路径 | 作用 |
|---|---|---|
| SystemUIPlugin | `/system_ext/app/SystemUIPlugin/SystemUIPlugin.apk` | 锁屏流体云/沉浸式卡片的宿主 |
| 白名单 | SystemUIPlugin `res/raw/sys_systemui_seedling_package_config.xml` | 高德三张卡 `536878018 / 536879184 / 536879317` 均 `lockImmersiveEnable="1" lockImmersiveDefault="1"` |
| SceneService | `/my_stock/priv-app/SceneService/SceneService.apk` | 接收高德数据、生成公交卡片、选背景图 |
| 公交模块 | `com.coloros.scene.business.publicTransport.gaode.*` | 高德公交导航全部逻辑 |
| 地标表 | `com.oplus.sdp.lb.b`（高德）/ `lb.a`（百度） | 42 个城市、111 个地标、357 个坐标点，硬编码 |
| 图片 URL | `com.oplus.sdp.lb.c` | CDN 路径拼接 |

SceneService 的公交模块只在 **ColorOS 17 及以上**初始化（`GaoDePublicTransportModule.c()` 检查 `isAtLeastOS17`）。

## 3. 高德 → OPPO 的协议

### 3.1 高德侧（`il3`，日志 tag `LiveCardOppoIntelligentTemplate`）

1. JS 通过 `onReceiveBizBeginData` 传入 `authority`（`IntelligentIntent`）和 `intentName`。
2. `isSupport`：`acquireUnstableContentProviderClient("IntelligentIntent")`，然后
   `call("queryFeature", "querySupportIntent", {intentName})`，读 `result` 字段的 JSON：
   `{"code":0, "data":"{\"querySupportIntent\":true}"}` 才算支持。
3. `send`：`call("shareIntent", null, {intentData: <JSON 字符串>})`。

### 3.2 OPPO 侧（`IntelligentIntentProvider` / `IntentShareDataProcessor`）

- `call` 支持 `queryFeature`、`shareIntent`、`deleteIntent`、`deleteEntity`、`getSid`。
- 返回 `Bundle{ "result": CallResult JSON }`，`CallResult = {code, message, data}`。
- intent 名：**`com.autonavi.minimap#Navigation.NotifyPublicTransportStatus`**。
- `intentData` 外层结构：

```json
{
  "intentName": "Navigation.NotifyPublicTransportStatus",
  "intentVersion": "...", "identifier": "...", "timestamp": 0,
  "serviceId": { },
  "intentAction": { "actionType": "fact", "actionTime": {"startTime":0,"endTime":0},
                    "actionStatus": 0, "actionPolicy": { } },
  "intentEntity": { ... 见 3.3 ... },
  "extra": { }
}
```

`intentEntity` 按字段区分三种：有 `orderStatus` 是打车，有 `route_type / first_desc / remain_length`
是步行/骑行段，其余是**公交/地铁**（`GaoDePtIntentEntity`）。

### 3.3 公交实体 `GaoDePtIntentEntity`

| 字段 | 含义 |
|---|---|
| `status` | 里程碑，见下表 |
| `naviInfo[]` | 每一段行程，当前段 `isCurrent=true` |
| `destCitycode` | 高德城市码（`010` 北京、`021` 上海…），查地标表用 |
| `destStation` / `destLatitude` / `destLongitude` | 终点 |
| `exitName` | 出站口 |
| `guideInfo` | 引导文案 |
| `deepLink` | 点击跳回高德 |
| `arrived` / `offRoute` / `gpsSignalStatus` | 到达 / 偏航 / GPS 弱 |
| `path[]` | 整条路线折线（很大，渲染用不到） |

`naviInfo[]` 每一项（`GaoDePtNaviInfoItem`）：

| 字段 | 含义 |
|---|---|
| `transportType` | `0` 步行 · `1` 公交 · `2` 地铁 · `12` 轮渡 · `13` 索道 · `100` 打车 · `102` 骑行 |
| `lineName` / `lineDirection` | 线路名 / 方向 |
| `lineBgColor` / `lineTextColor` / `borderColor` | 线路颜色 |
| `remainStations` | 剩余站数 |
| `on_station` | `{stationName, start_time, end_time, waitInfo{realTime[]}}` |
| `off_station` | `{stationName, coord{lat,lng}, open_direction, port_list[{name, shield, coord, status}]}` |
| `via_st_list[]` | 途经站 `{name, coord{lat,lng}, isTransferStation}` |

### 3.4 里程碑 `status`

| code | 含义 | OPPO 卡片主文案 |
|---|---|---|
| 1 | 到达起始站附近 | 上车站名 + 实时到站 |
| 2 | 候车 | 上车站名 + 「列车预计 N 分钟进站」 |
| 3 | 下一站（非终点） | 「下一站 XX」 |
| 4 | 下一站即终点 | 「下一站 XX」 |
| 5 | 到达普通站 | 「当前站 XX」 |
| 6 | 到达换乘站 | 「准备换乘」 |
| 7 | 公交/地铁到站 | 「已到达 XX」+ 出站口 |

副文案优先用 `guideInfo`，否则用「N站 XX下车」/「N站 XX换乘」。

「下一站 / 当前站」的取法（`com.oplus.sdp.ya.e`）：当前下标 = `clamp(via.size − remainStations, 0, via.size−1)`，
下一站 = 下标 +1，越界则取 `off_station`。

## 4. 每站背景图（地标图）

### 4.1 什么时候换图

- **途中**（status 3 / 4 / 5）：取「正在显示的那个站」的坐标（`via_st_list` 里同名站，找不到用 `off_station`），
  在 `destCitycode` 对应城市的地标表里，找**任意锚点 0.8 km 以内**的第一个地标 → 显示它的动图。
  没匹配上就不显示地标。所以列车每开过一站，背景图可能换成另一个地标。
- **到站**（status 7）：用出站口（`port_list` 里与 `exitName` 匹配的口）或下车站坐标查地标；
  - 地铁：地标 → 城市默认图 → 全国默认图；
  - 公交：只认地标。
  - 日/夜：按该坐标当地日出日落判断（OPPO `pa.f`）。

### 4.2 图片地址（公开，无需鉴权，2026-10-01 实测）

```
https://ocs-cn-south1.heytapcs.com/pantanal-servicegov-cn/intent/baidu/
  busnav/<城市>/<地标>.webp        动图，807×378，29 帧
  busnav/<城市>/<地标>.png         静图，807×378
  landmark/<城市>/<地标>.png       地标名字图，423×66，白字
  busnav/<城市>/DefaultDay.png / DefaultNight.png
  busnav/subway/NationalDefaultDay.png / NationalDefaultNight.png
  busnav/bus/DefaultDay.png / DefaultNight.png
```

例：`busnav/qingdao/MayFourthSquare.webp`（青岛五四广场「五月的风」）。
417 个地址中 365 个可用，缺的是少数地标和几个城市默认图，所以必须逐级回退。

### 4.3 地标表格式

```
城市目录, 高德城市码, [ 地标名, [ (lat, lng) ... ] ] ...
beijing,  010,  ForbiddenCity  (39.902978,116.399541) (39.914842,116.391537) ...
shanghai, 021,  TheBund        (31.241969,121.490214) ...
```

高德那一半是 GCJ-02 坐标，与高德下发的站点坐标一致；百度那一半是 BD-09，不能混用。

## 5. SystemUIPlugin 里的沉浸式宿主（ColorOS 17 的变化）

- 卡片数据里的 `extensibleActionMap` 带 `immersiveCardType`、`liveAlertService`、`requestLockScreenImmersive`。
- `immersiveCardType == 1`：SurfaceView 宿主，进场缩放 1.1，带上下遮罩（地图）。
- `immersiveCardType == 2`：模板卡片的 `ViewSize.f`（全屏尺寸）+ `immersiveFullBg` 背景，系统进程内渲染（公交/地铁）。
- 其它：进场缩放 0.4（倒计时这类）。
- `infoBounds` 改为跟随流体云视图位置，默认 `Rect(0, 400, w, h−500)` 再上下各内缩 12dp；
  `criticalBounds` 仍是屏幕 0.6 高度处 100px 的方块（与 ColorOS 16 一致）。

## 6. 移植到本模块（HyperOS）的方案

HyperOS 上没有 SceneService，高德拿不到 `IntelligentIntent`，就认为「不支持」，不发数据。方案：

1. **高德进程**（`AmapTransitShare.kt`，由 `AmapImmerse.handle` 启动）
   - hook `ContentResolver.acquire(Unstable)ContentProviderClient("IntelligentIntent")`：
     原本返回 null 时，换成一个 Settings provider 的 client 交回去，并记住它；
   - hook `ContentProviderClient.call`：只拦截这个 client，按 SceneService 的格式回答
     `queryFeature` / `shareIntent` / `deleteIntent`，不会真的打到 Settings；
   - 收到公交 JSON 后去掉 `path`，经 `ProbeGuard` 广播给 SystemUI（`op transit`）。
   - 真有 IntelligentIntent provider 的手机（真 ColorOS）不受影响：只替换 null。
   - **补发焦点通知**（`AmapTransitIsland.kt`）：高德在澎湃上只有步行/骑行才发焦点通知（id 1236），
     公交/地铁段没有。所以每收到一次公交状态，就以高德身份发一条 **id 1239** 的焦点通知
     （1237 被高德自己的 XiaomiUAConnectedDevice 占了，见 §7；我们的卡在时，高德自己的 1237 会被拦下）。
     澎湃的 protocol 1 扁平模板是一行小卡，放不下 ColorOS 那张卡，所以默认用**自定义布局**
     （`miui.focus.rv`，模块自己的 `res/layout/mc_transit_card.xml`，SystemUI 从模块包里 inflate；
     高德有 QUERY_ALL_PACKAGES，能引用模块的包）：
     - 线路色渐变底 + 当前站附近的地标图（同页面的选图规则，在高德进程里下载缓存，到了再静默重发一次）；
     - 左上线路色胶囊「地铁1号线」+ 方向；大字里程碑「下一站 / 当前站 / 准备换乘 / 已到达」；下面「N站 XX下车」；
     - 底部三节点进度（与锁屏页同一个 `AmapTransitScene.Track`），换乘站 ⇄ 胶囊 + 换乘线路号徽标；
     - 状态栏 ticker / AOD / 超级岛（`param_island`）放在 `miui.focus.param.custom`。
     备选样式：`template` = 系统大模板（param_v2：baseInfo + multiProgressInfo + bgInfo + picInfo），
     `flat` = 原来的小模板。到「下一站下车 / 换乘 / 到站」时上浮一次。步行段或行程结束就撤掉。
     锁屏上点它打开逐站页，别处点它回到高德导航。
2. **SystemUI**（`AmapTransitScene.java`）
   - 一个新的 `ImmersiveScene`，在进程内自己画，照 OPPO 五一路那种样式：线路色底、线路/方向、里程碑文案、
     **横向三节点实时进度**（当前站居中加粗，换乘站画成带 ⇄ 的胶囊并在上方挂一个「线路号」方形徽标，
     颜色是要换乘那条线的颜色，如绿色 8 号线），中间地标背景图和名字图（锁屏页不放乘车码按钮）；
   - 换乘线路号取自 naviInfo 里当前段之后的第一段（`Trip.nextLine`）；
   - 选图规则照搬 4.1，地标表照搬 4.3（`AmapTransitLandmarks.java`）；
   - 公交/地铁段时由它接管高德的焦点岛；步行段仍交给原来的 `AmapNavScene` 地图。
3. **调试**

```sh
# 高德进程：是否请求过 IntelligentIntent、问了什么、发了几次（transit: ...），
# 焦点通知状态（island: ...），以及高德脚本对设备层做了什么（script: bizBegin(10200) 之类）
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE

# 切换焦点通知样式并立刻重发：card（默认，自定义大卡）/ template（系统大模板）/ flat（原小模板）
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --es island card

# 整条链路演示（推荐）：高德进程假装收到一段地铁数据 → 发焦点岛 + 转给 SystemUI；锁屏点岛即可打开
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --es transit demo
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --es transit end

# 只测 SystemUI 页面：灌一段演示数据（北京 1 号线，下一站天安门东，会匹配故宫地标图）
adb shell am broadcast -a com.os4.musiccover.PROBE -p com.android.systemui --es op transit --ez demo true
# 不点焦点岛，直接打开 / 关闭这一页
adb shell am broadcast -a com.os4.musiccover.PROBE -p com.android.systemui --es op immersive --es id amap-transit --es do open
adb shell am broadcast -a com.os4.musiccover.PROBE -p com.android.systemui --es op immersive --es id amap-transit --es do close
# 结束行程
adb shell am broadcast -a com.os4.musiccover.PROBE -p com.android.systemui --es op transit --es do end
```

### 代码位置

| 文件 | 进程 | 作用 |
|---|---|---|
| `AmapTransitShare.kt` | 高德 | 冒充 `IntelligentIntent`，接住 JSON 转给 SystemUI |
| `AmapTransitIsland.kt` | 高德 | 公交/地铁段补发焦点通知（id 1239，自定义大卡 / 大模板 / 小模板） |
| `res/layout/mc_transit_card.xml` | SystemUI inflate | 焦点通知大卡的布局 |
| `AmapImmerse.kt` | 高德 | 启动上面的 hook；SystemUI 重启时重发最后一次状态；探针多一行，`--es transit demo/end` |
| `AmapTransitScene.java` | SystemUI | 数据模型、OPPO 的选站/选图规则、图片下载缓存（`cache/mc-transit/`）、页面绘制 |
| `AmapTransitLandmarks.java` | SystemUI | OPPO 地标表（42 城）、CDN 地址、0.8 km 匹配 |
| `ImmersiveHost.java` | SystemUI | 场景列表里排在地图前面，只在公交/地铁段认领高德的焦点岛 |
| `Main.java` | SystemUI | `op transit` |

## 7. 尚未验证 / 风险

- **真机结果（2026-10-01）：`acquires=0`**——高德在澎湃上没有启用 OppoIntelligentCard。查了高德 Java 侧：
  - 设备层入口是 AJX 模块 `com.amap.bundle.wearable.ajx.NativesModuleWearable`（`bizBegin / bizBeginWithData /
    sendMessage / bizEnd`），按 bizType 建设备：`xn0` 里 `10200 → jl3 → "thid_sdk_template_oppo_intelligent" → il3`，
    `20001 → bv6 → "third_sdk_xiaomi_ua_notify" → jk7`（XiaomiUAConnectedDevice，发 id 1237 的小焦点通知，
    只有 mainTitle / subTitle / scheme）。
  - **Java 侧对 10200 没有任何机型判断**（没有 IDeviceConfigFilter，只有 102 有）；不建 il3 是因为
    脚本根本没调 `bizBegin(10200)`——判断在加密脚本里。
  - 现在探针会记下脚本对设备层的每种调用（`script:` 行）。坐一次车后看：有没有 `bizBegin(10200)`；
    有没有 `bizBegin(20001)` / `sendMessage(20001)`（若有，说明高德在澎湃上本来就会推公交文字，只是内容很少）。
- 已确认：高德在澎湃上公交/地铁导航不发焦点通知，所以由模块补发（见上）。补发的通知能否被系统
  认作焦点通知，取决于澎湃对高德的焦点白名单，需要真机看一眼。
- 高德多久发一次数据（每站一次还是每秒一次）未知，SystemUI 端按 10 分钟无数据视为结束。
- 图片来自 OPPO CDN，OPPO 随时可能改路径或加鉴权。
