# ColorOS 17 高德公交/地铁卡：小岛在各里程碑分别显示什么

> 来源：`D:\coloros17-re\`（ColorOS17-v16-fuxi-FULL-20260928 全量逆向）里的
> `jadx-full/SceneService/sources/`（`jadx/` 那份不全，缺 `ya.*`；2026-10-06 用 `-Xmx3g -j 2` 重新反编译），以及 `D:\coloros17-re\apks\SceneService.apk` 的资源表。
> 卡构建器是 `com.oplus.sdp.ya.b`（日志 tag `GaoDePt_BusOrSubwayParser`），
> 里程碑枚举是 `com.coloros.scene.business.publicTransport.gaode.constant.GaoDePublicTransportNavMilestone`。
> 字符串取自 `com.oplus.sdp.dc.b`（R id 持有类）的 id → `aapt2 dump resources`。
> 行号以本目录里那份 jadx 输出为准（它是 Kotlin 反编译，方法名保持单字母）。

## 1. 一张卡有三个部位，键名各不相同

同一份卡数据里，三个地方各读各的键（`b.java` 的五个渲染器全都写这一套）：

| 部位 | 键 | 说明 |
|---|---|---|
| **小岛（收起胶囊）** | `capsuleLeftShowIcon` / `capsuleLeftIcon` / `capsuleLeftTextWhite` / `capsuleLeftTextLine` / `capsuleLeftLineBgColor` / `capsuleRightTextWhite` / `capsuleRightTextGray` / `capsuleRightTextLine` / `capsuleRightLineBgColor` | 左半 + 右半，各有一段「白字」和一段「线路色底的字」 |
| **锁屏那一行** | `iconInLock` / `titleInLock` / `subTitleInLock` | 与应用无关，卡自己带 |
| **展开大卡** | `cardPrimaryInfo` / `cardSecondaryInfo` / `cardStationOverview` / `cardBgColor` / `cardJumpLink`；到站还多 `cardDestinationTEXT` / `cardDestinationIcon` / `cardShowCover` / `cardShowPath`；候车多 `cardWaitingInformation` | 站点概览、进度、地标背景都在这里 |

`capsuleLeftTextWhite` 是纯白字，`capsuleLeftTextLine` 是**用线路底色画的那一段**（`capsuleLeftLineBgColor`），两者同时只用一个，另一个置 `""`。`com.oplus.sdp.jb.a.f(transportType)` 给的是交通方式图标（地铁/公交），`com.oplus.sdp.jb.a.b()` 是锁屏那个图标。

## 2. 里程碑 → 用哪个渲染器

`b.java` 里 `e0(entity, current, status)` 按里程碑选渲染器（约 804 行起）：

| 高德 status | 枚举 | 渲染器 |
|---|---|---|
| 1 | `ARRIVE_ORIGIN_NEARBY` 到达起始站附近 | `c()` |
| 2 | `WAITING` 候车 | `c()` |
| 3 | `NEXT_STATION` 下一站 | `K()` |
| 4 | `NEXT_DESTINATION` 下一站即终点 | `K()` |
| 5 | `ARRIVE_COMMON_STATION` 到达普通站 | `a()` |
| 6 | `ARRIVE_TRANSFER_STATION` 到达换乘站 | `d()` |
| 7 | `ARRIVE_LINE_DESTINATION` 到站 | `b()` |

选不出来时打 `buildInitData skip: no renderer matched` 并**整卡不下发**。另有 `D(entity)` 返回页面路由名 `"pages/on_bus"`（它的 switch 分支体 jadx 没能还原，只剩返回值）。

## 3. 每个里程碑，小岛和锁屏显示什么

| status | 小岛左 | 小岛右 | 锁屏 title / subtitle |
|---|---|---|---|
| **1** 到达起始站附近 | 「往X」白字 **+** 线路名（线路底色）——唯一同时用两段的 | 空 | 站名 / 候车信息 |
| **2** 候车 | 「往X」白字 **+** 线路名（线路底色） | 空 | 站名 / `线路名(方向)` |
| **3** 下一站 | **「下一站」**（白字） | **站名** | **「下一站 %s」** / guideInfo |
| **4** 下一站即终点 | **「下一站」** | **站名** | **「下一站 %s」** / guideInfo |
| **5** 到达普通站 | **「当前站」** | **站名** | **「当前站 %s」** / guideInfo |
| **6** 到达换乘站 | **「换乘」** | 线路名（线路色）+ 「往X」 | **「准备换乘」** / … |
| **7** 到站 | 「到站」白字；地铁且知道出站口时改为 **出站口**（如「B口」，线路底色） | **下车站名** | 下车站名 / **「已到站」** |

两种白/灰的分配也值得照抄：status 3/4/5/7 的小岛是 `capsuleLeftTextWhite` 放固定词（下一站/当前站），`capsuleRightTextWhite` 放站名；status 6 反过来，左边「换乘」是固定词、右边是线路名用底色画。

**status 7 有两条分支**（`ya.b.b`），分的是「是不是地铁、知不知道出站口」，与是否已出站无关：公交、或地铁但 `exitName` 为空、或这段没有途经站（概览第一项的线路色拿不到）时，左半是图标 +「到站」，副文案「已到站」；地铁且有出站口时 `capsuleLeftShowIcon=false`、`capsuleLeftTextLine=出站口`、`capsuleLeftLineBgColor=线路色`——**小岛左半是带线路色的出站口胶囊**，大卡副文案也换成同色的出站口。右半永远是下车站名，锁屏副标题永远是「已到站」。到站卡还带 `cardDestinationIcon` / `cardShowCover`（出站口坐标的地标图，`ya.b.O`），`cardShowPath=false`。

## 4. 文案是怎么拼出来的

| 方法（`b.java`） | 产出 | 用在 |
|---|---|---|
| `Q(current, type)` | 线路方向（实时项的 `lineDirection`，否则段的） | status 1/2 小岛右 |
| `x(rawDirection)` | 去掉结尾的「方向」再套 `gaode_pt_direction_to` = **「往%1$s」** | Q/S 的包装 |
| `T(waitInfo)` | `线路名(方向)` | status 2 锁屏副标题 |
| `S(current, type)` | 实时到站文案；纯数字套 `gaode_pt_subway_capsule_arrive` = **「%1$d分钟」** | 候车 |
| `Y(entity, navi, current, status)` | 有 `guideInfo` 就用它；否则套 `gaode_pt_stations_before_arrive` = **「%1$d站 %2$s下车」**、带换乘时 `gaode_pt_stations_before_transfer` = **「%1$d站 %2$s换乘」**、拿不到站名时 `..._fallback` = **「%1$d站后下车」** | status 3/4/5 的 `subTitleInLock` |
| `P(entity, current)` | 下车站名（`off_station.stationName`，退回 `destStation`） | status 7 小岛右和锁屏标题 |
| `X(via, remain, on, off)` | 当前站名（`com.oplus.sdp.e.b` 算下标） | 站名兜底 |
| `W(rawStatus, navi, current)` | 把 status 4 校正成 3（`NEXT_DESTINATION` 且下一段就是终点时） | 进 `e0` 之前 |
| `U(current)` / `c0(stageOverview)` | 下车站名 / 当前段 | 大卡 |

固定词都来自资源表：`gaode_pt_next_station`=「下一站」、`gaode_pt_next_station_with_name`=「下一站 %1$s」、`gaode_pt_current_station`=「当前站」、`gaode_pt_current_station_with_name`=「当前站 %1$s」、`gaode_pt_transfer`=「换乘」、`gaode_pt_prepare_transfer`=「准备换乘」、`gaode_pt_destination`=「目的地」、`gaode_pt_capsule_arrive_station`=「到站」、`gaode_pt_card_arrived_station`=「已到站」、`gaode_pt_enter_station`=「进站」。

## 5. MIUI 那半边：`imageTextInfoLeft` 确实是视觉左边

本模块发的是澎湃的键，得确认它俩的左右跟 OPPO 是不是同一个方向。反编译 `miui.systemui.plugin`
（`D:\coloros17-re\tools\jadx`）后，插件自己的名字就拿出了三层证据：

| 层 | 证据 |
|---|---|
| 槽位 | `IslandTemplateFactory.chooseModule` 把大岛分成 `AREA_LEFT` / `AREA_SMALL` / `AREA_RIGHT` 三个槽各自选模块；左槽读 `getImageTextInfoLeft()`，右槽读 `getImageTextInfoRight()` |
| 模块 | 左槽 `type=1` → `MODULE_IMAGE_TEXT_1`；右槽 `type=2` → `MODULE_IMAGE_TEXT_2` |
| 布局 id | 左模块绑 `R.id.island_container_module_text`（`IslandTextViewHolder.java:295`），右模块绑 **`R.id.island_container_module_right_text`**（`IslandRightTextViewHolder.java:320`） |

持有它们的字段名也一致：`IslandImageTextViewHolder.textViewHolder` 是 `IslandTextViewHolder`，
`IslandImageTextView2Holder.textViewHolder` 是 `IslandRightTextViewHolder`。

所以 `imageTextInfoLeft` ↔ 视觉左、`imageTextInfoRight` ↔ 视觉右，和 ColorOS 的
`capsuleLeft` / `capsuleRight` 同向，照名字平移即可，不需要镜像。

## 6. 与本模块的对照

**2026-10-06 起本模块逐函数移植了这套规则**（`AmapTransitCard.java`），小岛左右两半就是 ColorOS 的
`capsuleLeft*` / `capsuleRight*`：白字进 `textInfo`，线路底色那一截（`capsule*TextLine`）画成色块图片进 `picInfo`。
§3 的表就是现在的实际显示，`AmapTransitCardTest` 按 10-05 真实行程逐步断言。

## 7. 生命周期（谁让卡片出现、保留、消失）

| ColorOS 类 | 规则 | 本模块 |
|---|---|---|
| `GaoDePtNaviSceneRouter.j` | 偏航 → 终点(8) → 端内结束(9) → 步行/骑行 → 打车 → 公交地铁 | `AmapTransitCard.of` 同序 |
| `GaoDePtNaviSceneRouter.h` | 无新数据：status 8 30 s、status 7 15 min、其余 30 min 后撤卡 | `AmapTransitShare.silence` |
| `GaoDePtRideCodeDeferBindManager` | 地铁 status 7 且下一段是步行：步行卡推迟 5 min，刷乘车码或步行导航开始则提前 | `AmapTransitMilestones` 的到站保留；高德步行岛（1236）出现即提前 |
| `GaoDePtFinalDestCardManager` | 最后一段是公共交通且 status 7：地铁 5 min / 公交 35 s 后出终点卡 | `AmapTransitShare.finalCard` |
| `GaoDePtNaviIntentHandler.l` + `ya.a` | 终点卡 30 s；之后同一行程的数据全部忽略 | `showFinal` + `finalShown` |
| `GaoDePtDismissHandler.b` | 删除意图：终点卡在/待出、或正在最后一段步行时忽略 | `bizEnd(103)` 时同样判断 |
| `GaoDePtWalkRideHandler` / `ya.n.f` | 步行段：静默步行卡「步行至 XX / 共步行N米，M分钟」 | 步行段卡片；高德自己的步行岛在时让出 |

ColorOS 的 status 由高德给；澎湃上高德不给，`AmapTransitMilestones` 从「卡片在哪段 + 剩几站」推（见 `amap-transit-island.md` §6.2）。
