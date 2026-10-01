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
2. **SystemUI**（`AmapTransitScene.java`）
   - 一个新的 `ImmersiveScene`，在进程内自己画：线路色底、站名、剩余站、三站进度条、地标背景图和名字图；
   - 选图规则照搬 4.1，地标表照搬 4.3（`AmapTransitLandmarks.java`）；
   - 公交/地铁段时由它接管高德的焦点岛；步行段仍交给原来的 `AmapNavScene` 地图。
3. **调试**：`adb shell am broadcast -a com.os4.musiccover.AMAPPROBE` 能看到高德是否请求过
   `IntelligentIntent`、问了什么、发了几次。

## 7. 尚未验证 / 风险

- **最大的未知**：高德的 JS（`assets/ajx.bundle/bundles.oajx`，已加密）在非 OPPO 机型上是否会启用
  OppoIntelligentCard 设备。启用了，上面的方案就能拿到数据；没启用，`acquires=0`，需要再想办法
  （例如伪装机型，或直接在 JS 设备注册处动手）。必须真机跑一趟公交/地铁导航确认。
- 高德在 HyperOS 上公交导航时是否也发焦点通知（模块靠点焦点岛打开页面）。
- 高德多久发一次数据（每站一次还是每秒一次）未知，SystemUI 端按 10 分钟无数据视为结束。
- 图片来自 OPPO CDN，OPPO 随时可能改路径或加鉴权。
