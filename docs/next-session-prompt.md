# 新对话提示词（高德公交/地铁卡 · 第四轮）

仓库 `zyl6932/HyperMusicCover`（HyperOS 的 LSPosed 模块），分支 `claude/zen-cannon-m7khd8`。
先读这两份文档，它们是当前的准确版本：

- `docs/amap-transit-island.md` —— 架构、数据来源、地标、调试命令
- `docs/coloros-island-stages.md` —— ColorOS 每个里程碑的小岛/锁屏各显示什么

**`.scratch/` 不在仓库里**，在仓库的上一级：`C:\Users\。\Desktop\music lockscreen\.scratch\`。
本轮的工具、截图、探针脚本都在 `.scratch/amap-ledger/`。

## 一、这一轮干了什么

| 提交 | 内容 |
|---|---|
| `ceff59c`（上一轮留下的） | 上一轮的交接文档 |
| 本轮一个提交 | 会话门 + 步行从计划起步 + OPPO 对齐 + 进度条换 `progressInfo` |

**设备上装着的是 `0.7.10-designbar.20261005`**，它**不含**最后那个「步行从计划起步」的改动
（源码里有，没构建；用户叫停时正在构建）。

## 二、下一轮第一件事：进度条上那三张图（用户点名，且是最难看的地方）

进度条已经换成官方那条**设计图同款**了（`progressInfo`，见 §三），结构全对：
连续条、已走段实心、车头骑在填充边缘、站点图钉、终点旗帜、剩余段灰色虚线。

**问题是那三张图是我用 `drawRoundRect` / `drawCircle` 拼的，丑，不是设计图的东西。**
设计图在 `C:\Users\。\Desktop\设计图.jpg`，上一轮的成品在
`.scratch/amap-ledger/shots/bar2_full.png`（对比着看就知道差多少）。

用户已经拍板：**用矢量 `Path` 重画这三张**（`AmapTransitIsland.kt` 里的
`vehicle()` / `pin()` / `flag()` / `carGlyph()`）。目标对着设计图：

| 图 | 生成函数 | 设计图里长什么样 |
|---|---|---|
| 车头 | `vehicle(subway, bg)` | 侧视地铁列车，**斜鼻子**、连续腰线、两扇窗、转向架；白车身 + 线路色腰线；**高度约是条高的 2 倍** |
| 站点针 | `pin(bg)` | 小巧的定位针（圆头 + 收尖），线路色，针头里一个白色地铁前脸 |
| 终点旗 | `flag()` | 同样的针形，灰色，白色三角旗 |

**踩过的坑**：第一版车头画成正视（96×96 方块），完全不对——车头是骑在**横条**上的，必须侧视。
第二版把尺寸调到 132×56 又太大（设计图约是条高的 2 倍），把上面的文字都压住了、图钉和旗帜顶出卡片底沿。

**还没解决**：见 §四「插件的约束」——条高和图标位是插件定死的，设计图那种留白和比例
能不能在它里面实现，**重画之前先确认**，不然画得再好也可能被布局压扁。

## 三、这一轮改了什么（都在源码里）

### 3.1 步行导航的触发（提示词上一轮的 §二，那个 bug 已修）

**`bizBegin(103)` / `bizEnd(103)` 就是导航的开关**，2026-10-05 实测：

- 停在**路线页**（没点开始导航）：只有 `bizEnd(201) bizEnd(202) bizBegin(113)`
- 点**「开始导航」**之后才出现 `bizBegin(103)`
- **退出导航** → `bizEnd(103)`；**再进去** → `bizBegin(103)` 再来一次

所以 `AmapTransitShare.channel()` 用它当会话门：`bizBegin(103)` **上升沿**复位
`walked` 和 `riding`（**两个一起**——`riding` 也会陈旧，前段步行卡会因此掉进 `ride()` 两个分支都不进的死路），
`bizEnd(103)` 只关闸不 `clear()`（`clear()` 会把刚发的到达卡撤掉、取消它那条 30 秒定时器）。

实测：退出导航再重进，`walk to ... -> started walk` **两轮都出现了**。**这个 bug 修好了。**

### 3.2 步行导航起步太慢（本轮新发现，用户点名「黄花菜都凉了」）

**现象**：点了「开始导航」，步行导航 **26～59 秒**才起来（两次实测：58.8s / 26.5s）。

**真因**（ledger 实证）：高德在「开始导航」那一刻发的那张 103 卡**只有 `planData`、没有 `title`**，
而 `ride()` 第一句就是 `if (title.isEmpty()) return`，所以那张卡被丢掉；真正带
`title: 步行至 大学城南地铁站` 的步行卡**要等 26～59 秒**才来（跟高德自己的步行阶段初始化有关，
延迟是变的，不受我们控制）。

**已写进源码、未构建未验**：`walkToFirstStop()` —— 不等卡，**从计划里直接起步**。
计划的 `segmentlist[0]` 里有：

```
on_station.name = "大学城南"                     ← 步行要去的站
inport.name     = "E口"
inport.coord    = {lon: 113.399217, lat: 23.044146}   ← 步行终点坐标
```

而这份计划在 `bizBegin(103)` 之后约 **30 毫秒**就到（113 通道的 `type 24`）。
另外那张空卡也**有用**：它的 `planData[0]` 是步行胶囊（`icon` 以 `bus_foot` 开头或 `capsuleType == "0"`），
`opensWithWalk()` 用它判断这趟是不是「开头有一段步行」。

**下一轮要做的**：构建、装上、走一遍 §六 的测试，确认「点完一两秒内起步」。

### 3.3 按 OPPO 对齐的部分（用户说「所有的一切都跟 OPPO 的设计」）

对着 SceneService 的 `com.oplus.sdp.ya.b` 看，**分段进度（`cardStationOverview`）只在
status 3/4/5/6 有**（`K()` 3/4、`a()` 5、`d()` 6）；**1/2 没有**（只有 `cardWaitingInformation`，
一个候车车辆列表）**7 也没有**（`b()` 给的是地标）。它的 `cardStationOverview` 里
**一个字符串都没有**——只有 `stationList` / `isCurStation` / `isTwoStation` / `curIndex`。

据此改了：

- `AmapTransitIsland.PROGRESS_AT = setOf("3","4","5","6")` —— 进度条只在这几个里程碑画
- `AmapTransitScene` 的 status 1/2 分支**不再设 `f.nodes`** —— 沉浸页也不画站点轨道
- `AmapTransitShare.show()` 里 `cardLocation`：**步行卡的 `location` 不是这一腿的**
  （它的 `persent: 0.5` 是"走到一半"、`remainStations: 1` 是"还有一站到地铁站"），
  以前被当成 4 号线的进度，于是候车时显示「已走 50%、剩 1 站」

### 3.4 文案重复（用户报的）

- **`即将进站进站`**：`countdown + "进站"` 在高德的 `mainTitle` 已经以「进站」结尾时重复了。
  改成只在结尾不是「进站」时才拼。
- **同一句话出现两次**：`baseInfo.subContent` 和 `multiProgressInfo.title` 放的是同一个
  `f.secondary`。按 OPPO 砍掉了条上的标题（`cardStationOverview` 本来就不带文字）。

## 四、插件的约束（**重画图标之前必须知道**）

`miui.systemui.plugin` 反编译在 `.scratch/hyperos-plugin/sources/`，本轮的 view holder 在
`.scratch/hyperos-plugin/mp/sources/`。要点：

1. **`multiProgressInfo` 和 `progressInfo` 抢同一个槽位，前者先判**（`TemplateFactoryV3`）。
   我们**只发 `progressInfo`**，所以插件画的是 `ModuleProgressViewHolder`。
2. `progressInfo` 校验：`progress >= 0` 且 `colorProgress` 非空，否则整条不画
   （日志 `progressInfo param error`）。
3. **三张图都从 `miui.focus.pics` 按名字取**（`picForward` / `picMiddle` / `picEnd` 的值就是键名）。
   模块在 `AmapTransitIsland.publish()` 里把三张 bitmap 塞进那个 Bundle。
4. 车头位置：`setProgressThumb` 用 `(progress * width / 100) - imageWidth/2`，**居中骑在填充边缘**。
   填充是 `colorProgress → colorProgressEnd` 的渐变。
5. **条高、图标的槽位和边距都是插件的 `R.dimen` / 布局定死的**，我们只能给图和百分比。
   设计图那种「车头 2 倍条高、留白充足」未必能在这个容器里实现——**先量清楚再画**。
6. 图钉和旗帜在 `progress_point1` / `progress_point2`；全部图为空时那个模块可能整个不显示。

## 五、已知死路（别再撞）

**本轮新撞的**

- **静态挖 AJX 里的 JS 不行，是以天计的工程。** 公交行程导航（`amapuri://tripService/...`）
  Java 侧**完全不存在**：dex 里搜不到 `tripService` / `amap_glass` / `third_sdk_oppo_aod`，
  连 `amapuri` 都没有；没有公交导航的 Java 页类。JS 在 `.oajx` v2 容器里
  （`/data/data/com.autonavi.minimap/files/ajx-biz/db/`，读取器 `libajxbiz2.so`），
  样本熵 7.64/8、无 zstd/zlib 魔数，只链了 libzstd+libz 却扫不到任何加密常数。
  要读它得先复原他们的反射/序列化格式（`parseChunks` 用相对偏移表 + 数据驱动访问器）。
  **结论：绕开，用 `bizBegin(103)`（§3.1）。** 详见记忆 `amap-ajx-bundles-are-oajx-v2`。
- **MIUI 节点条（`multiProgressInfo`）的 `Point` 图标槽也没意义了**：插件写死 `null`，
  虽然能钩 `Point` 构造器塞图标进去（实测能把车头放上轨道），但那条不是设计图的样式，
  已改用 `progressInfo`。记忆 `miui-progress-node-icon` 记了钩法，留作参考。

**上一轮记的（仍然有效）**

- **ColorOS 那条路走不通**：`beginWalkAndBikeInTripNaviOnSilentClick` 依赖 OPPO 版高德下发的
  `GaoDeWalkingAndCyclingIntentEntity`，本机高德**不发这个实体**。只能走高德自己的
  `IFootNaviService.startNaviPage`（已在用）。
- **`isOppo` 开关没用**：答成 true 脚本仍然只 `bizBegin(113)/bizBegin(103)`。
- **逆向 OPPO 拿站点表是死路**：`via_st_list` 在 SceneService 里只有 gson 反序列化。
- **`AMAPPROBE --es transit 进站|乘车|换乘|到达` 走的是 `simulate()`**，绕过脚本直接调 `ride()`；
  测脚本行为时别拿它当证据。
- **每次构建必须换版本后缀**（`-PmcVersionCode` 也要加一）。同一个后缀编两次，装上的是上一次的 APK。

## 六、怎么验（下一轮用这个）

脚本：`.scratch/amap-ledger/walktest.sh`（读高德进程的日志，抽 `bizBegin/bizEnd`、
`walk to`、`navigation started` 的时间和顺序）。

**测试步骤**（要真行程，模拟测不出来）：

1. 打开高德，搜一条公交/地铁线路
2. 点**「开始导航」** → 期望 **一两秒内**自动进步行导航（这一轮的目标就是消灭那 26～59 秒）
3. **退出导航**
4. **再点「开始导航」** → 期望**也能**自动进步行导航（上一轮的 bug，已修，别回归）

读结果：`sh walktest.sh`，看 `bizBegin(103)` 到 `walk to` 之间隔了几毫秒。

**别的探针**

```sh
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE                        # 状态
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --ez max true          # ledger（最近的载荷）
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --es transit demo      # 造一个假行程的岛
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --es transit end       # 撤掉
adb shell am broadcast -a com.os4.musiccover.PROBE -p com.android.systemui --es op transit
```

**岛只在高德不在前台时才画**——测的时候先回桌面（`adb shell input keyevent KEYCODE_HOME`），
否则看到的是"没有岛"。

## 七、构建

```sh
./gradlew :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease \
  -PmcVersionCode=<比已装的大> -PmcVersionName=0.7.10 -PmcVersionSuffix=-<这次的主题>.2026100X
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am force-stop com.autonavi.minimap    # 装模块不会重载，必须重启高德
```

改动落在 SystemUI 侧时（`AmapTransitScene` / `AmapTransitIsland`）还要重启 SystemUI：
`adb shell "su -c 'killall com.android.systemui'"`，等约 4～7 秒探针才注册得上。
**重启前要先问用户。**

写代码的坑：`AmapTransitScene.java` 是 **Java**（Kotlin 的 `isNotEmpty()` 之类不能用）；
Kotlin 里注意别用 `top` 这类容易遮蔽的名字。

## 八、没验的

- **§3.2 的 `walkToFirstStop()`**：源码里有，**没构建、没装、没测**（用户叫停时正在构建）。
  下一轮第一件事之一就是把它验掉。
- **§3.4 的重复修复**：`multiProgressInfo.title` 那版验过（`"title":""`）。进站重复词没单独验过。
- **§3.3 的 status 1/2 改动**：要真的候车状态才看得到，本轮的假数据是 status 5。
- **前段步行的数字**（不拿步行卡的 `location`）：要真的在走路。
- **真机没有的数据**：出口坐标那条路、中途换乘的步行、status 1/2/6、公交路径——
  都还是上一轮那句「手上没这种数据」。
