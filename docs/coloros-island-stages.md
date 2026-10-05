# ColorOS 17 高德公交/地铁卡：小岛在各里程碑分别显示什么

> 来源：`D:\coloros17-re\`（ColorOS17-v16-fuxi-FULL-20260928 全量逆向）里的
> `jadx/SceneService/sources/`，以及 `D:\coloros17-re\apks\SceneService.apk` 的资源表。
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
| **7** 到站 | 「到站」白字；出站后改成 **出站口**（如「B口」，线路底色） | **下车站名** | 下车站名 / **「已到站」** |

两种白/灰的分配也值得照抄：status 3/4/5/7 的小岛是 `capsuleLeftTextWhite` 放固定词（下一站/当前站），`capsuleRightTextWhite` 放站名；status 6 反过来，左边「换乘」是固定词、右边是线路名用底色画。

**status 7 有两条分支**：还没出站时 `capsuleLeftShowIcon=true` + 左「白字」= 离开的那站；到站后改写为 `capsuleLeftShowIcon=false`、`capsuleLeftTextLine=出站口`、`capsuleLeftLineBgColor=线路色`——也就是**小岛左半变成一个带线路色的出站口胶囊**，右半是下车站名。状态 7 还会置 `cardShowCover` / `cardShowPath`，让大卡露出地标图。

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

本模块的 `AmapTransitIsland` 走的不是这套键（那是 ColorOS 的 `sdp` 卡，澎湃上不存在），它发的是 `miui.focus.param` 的 `param_v2`。但**语义可以照搬**：

- 本模块 status 3/5 的「下一站 / 当前站」正是 ColorOS 的 `gaode_pt_next_station` / `gaode_pt_current_station`，措辞一致；
- ColorOS 的小岛左右是「固定词 + 站名」两段，本模块的 `param_island.bigIslandArea` 也是 `imageTextInfoLeft`（线路徽标 + 里程碑）/ `imageTextInfoRight`（剩余站数），结构同源；
- **status 6（换乘）小岛左边是「换乘」而不是站名**，右边才是线路名 —— 本模块目前在换乘时仍按「当前站/下一站」处理，这一条可以对齐；
- **status 7 小岛左半是出站口胶囊**（线路底色）—— 本模块的 `exitName` 只进了 entity（给页面用），没有进焦点卡的小岛，可以补。
