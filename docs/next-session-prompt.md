# 新对话提示词（高德公交/地铁逐站卡 · 第三轮）

仓库 `zyl6932/HyperMusicCover`（HyperOS 的 LSPosed 模块），分支 `claude/zen-cannon-m7khd8`。
先读这两份文档，它们是当前的准确版本：

- `docs/amap-transit-island.md` —— 架构、数据来源、地标、调试命令
- `docs/coloros-island-stages.md` —— ColorOS 每个里程碑的小岛/锁屏各显示什么

**`.scratch/` 不在仓库里**，在仓库的上一级：`C:\Users\。\Desktop\music lockscreen\.scratch\`。
这一轮的工具和真载荷都在 `.scratch/amap-ledger/`（见 §五）。

## 一、这一轮干了什么（3 个提交，都没推）

分支现在**领先 `origin/main` 6 个提交**（其中 `123d657` 及以上是本轮之后的）。

| 提交 | 内容 |
|---|---|
| `e8b4396` | 到达站取值改按 `ya.b.P`（这腿的下车站 → 卡上地名 → 句子）；到达卡 30 秒自撤（`showFinalDestCard` 的 `30000`）；`remaining()` 补 `ya.b.l0` 闸门；`lineAt` 越界返回 null；默认线色 `#4A86FF`；静默窗口 10 → 30 分钟（`ya.b.h`） |
| `a681985` | 出站口：`port_list` 从计划的 `outport` 填真坐标（不再拿名字伪造一个带腿坐标的口）；`exit(Trip)` 补「取最后一个口」；到达卡第二行/小岛左半在地铁且有出口时用出口；出口名跨卡记住 |
| `97326bd` | 步行导航：新增 `AmapFootNavi`，调高德 `IFootNaviService.startNaviPage`；`AmapImmerse.loader()`；接在行程开头那张步行卡上 |

真机验过的都在提交说明里。**没验的全在 §三。**

## 二、下一轮第一件事：修步行导航的触发（用户点名的 bug）

**现象**：退出导航之后再重新进入导航，**不会**自动进步行导航了。

**成因**：`AmapTransitShare.walked` 是**一次性闩**，只在 `clear()` 里复位（`AmapTransitShare.kt:1772`）。
退出导航不一定走 `clear()`，于是第二次进导航时它还是 `true`，开头那段步行卡进来直接 `return`。

**方向（用户明确要求）**：**改用 OPPO 的做法**——ColorOS 不是「步行卡到了」触发的，是**点击**触发的：
卡片按钮 → `…RouterActivity`（`method = publicTransportNaviBeginNaviBtnClick`）→
`beginWalkAndBikeInTripNaviOnSilentClick`。所以要去**钩高德自己的「开始导航」**，
让触发跟着那次点击/导航启动走，而不是闩在某一类载荷上。

参考实现（SceneService 里百度那侧的孪生，已反出来）：
`.scratch/oppo-island/amap-transit-reverse/SceneService/publicTransport/baidu/click/BaiduPublicTransportRouterActivity.java`
——它的 `a()` 就是 `beginWalkAndBikeInTripNaviOnSilentClick`：拿缓存的步行实体，`status==1` 才启，
然后才走 deepLink 开地图 app。

高德侧要钩的那个「开始导航」在它的路线页里；**高德自己的 dex 在本机有**：
`scratch/coloros/gaode.apk`（17.00.0.2005，和之前逆向同一版），可以用
`./scratch/tools/jadx/bin/jadx -d <out> --single-class <FQN> scratch/coloros/gaode.apk` 单类反，
或用 `scratch/dexgrep.py <apk> <needle>...` 按字符串反查谁在用。
（本机那两份 `SceneService.apk` **都不是**逆向所依据的版本，`gaode/click/` 包在其中根本不存在，别再去找。）

## 三、待办与待验（按优先级）

1. **修上面那个触发 bug**（按 OPPO 的做法）。
2. **行程最后那段步行的导航**：ColorOS 在那里也给卡和按钮，但触发点是「车到站」，时机要单独定（自动拉起会很唐突）。
3. **「到达目的地」卡**（`pages/arrival`，`ya/a.b()`）：大字 = 导航目的地名（`stageOverview.destinationName`），
   副文案 = 全程时长，带一张 `map_bg.png` 路径图；末段到站后地铁排 300 秒（等出站码）/ 公交轮渡索道 35 秒显示。
   要新页面和素材，是剩下最大的一块。

**验不到的（手上没这种数据）**

- 出口**坐标**那条路——真计划里所骑那一腿没有 `outport`（13号线 那段有：「E3口」「通往新塘火车站」）
- **中途换乘的步行**守卫——抓的那趟车没有中途步行（022 是最后一段，走的是「撤卡」那条路）
- status **1/2（候车/上车）**、**6（换乘）**、**公交路径**——都要真数据
- **高德被冻住时那 30 秒会不会迟**——要真行程（模块的定时器在**高德进程**里，ColorOS 的在系统进程里）
- 计划**匹配成功**那条路（即 `segment()` 认了计划）——只有人造对照验过

## 四、已知死路（别再撞）

- **ColorOS 那条路走不通**：`beginWalkAndBikeInTripNaviOnSilentClick` 依赖 OPPO 版高德下发的
  `GaoDeWalkingAndCyclingIntentEntity`（带 `GaoDeWalkRideLifecycleStatus` 1/2/3）。本机高德**不发这个实体**。
  所以只能走高德自己的 `IFootNaviService.startNaviPage`（已在用）。
- **`isOppo` 开关没用**：答成 true 脚本仍然只 `bizBegin(113)/bizBegin(103)`，不开 `bizBegin(10200)`。
- **逆向 OPPO 拿站点表是死路**：`via_st_list` 在 SceneService 里只有 gson 反序列化，是实体自带的。
- **`AMAPPROBE --es transit 进站|乘车|换乘|到达` 走的是 `simulate()`**，绕过脚本直接调 `ride()`；
  而且**手里计划只有一条线时会越界崩**（它取 `lines[1]`，异常吞在接收器里）——测脚本行为时别拿它当证据。
- **`simulate()` 的换乘态要用干净态**（先 force-stop 高德）才跑得起来，否则上面的越界。

## 五、这一轮的工具与坑（都在 `.scratch/amap-ledger/`）

- `payloads/` —— 2026-10-05 那趟 7号线 的 31 份真载荷（`000-113` … `030-103`，另有 `NNN-other` 的 101 通道）。
- `plan7.json` —— 从被 8000 字符上限截断的 ledger dump 里救回来的**真计划**（7号线 大学城南→裕丰围、13号线 裕丰围→新塘）。
  `recover.py` 是救它的脚本。
- `replay.py` —— 把一份真载荷送进模块的 `raw` 入口并读回结果；`bothsides.py` —— 同时读高德侧与 SystemUI 侧；
  `islandrun.py` —— 高德退后台、逐步慢放（**要看岛必须用这个**）；`timing.py` —— 计时。
- `extract24.py` / `segdump.py` / `reslook.py` —— 从探针 dump 里取载荷、看计划字段、把 aapt2 的 resources dump 解成「资源名 → 文案」。
- `gd_res.txt` —— 高德 APK 的 resources dump（`aapt2 dump resources`），查字符串用。

**踩过的坑（下一轮别重复）**

- **每次构建必须换版本后缀**。我用同一个 `-exitport`/710048 编了两次，`versionName` 检查分辨不出来，
  装上的是被 kill 的那次留下的 APK，白跑两轮验证。
- **岛只在高德不在前台时才画**（用户指出）。在高德前台测 = 测用户看不到的状态。
  后台测时证据要读**模块写进 logcat 的行**和 **`dumpsys notification` 里的岛通知**（`pkg=com.autonavi.minimap … id=1239`），
  别依赖向可能已冻结的进程投广播。
- **无线 adb 端口会变**（41817 → 36651）：`adb mdns services` 看新的，再 `adb connect <ip>:<port>`。
- **Git Bash 会把 `/sdcard/...` 改写成 Windows 路径**：先 `export MSYS_NO_PATHCONV=1`。
- **设备的 shell 会被括号噎住**：`--es footnavi '大学城南(E口),…'` 报 `syntax error: unexpected '('`——
  本地引号在传到设备那一层已经没了。带括号的文案要么换掉，要么自己转义。
- **模块的 class loader 看不到高德的类**：`Class.forName("com.autonavi.common.model.GeoPoint", false, <模块的>)`
  答 `no class`。用 `AmapImmerse.loader()`（`handle(cl)` 存下来的那个）。

## 六、调试命令

```sh
# 高德进程：状态 + tail（含 route: 行、ride # 行，ride 行现在带 exit=）
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --ez max true     # ledger
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --ez events true  # 发送时刻与间隔
# 重放一份真载荷（base64，脚本里做的）
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --es transit raw --es json '<base64>'
# 直接让高德开一段步行导航（名字,纬度,经度；名字别带括号）
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --es footnavi '大学城南E口,23.044146,113.399217'
# SystemUI 侧：它实际持有的帧
adb shell am broadcast -a com.os4.musiccover.PROBE -p com.android.systemui --es op transit
# 不点小岛直接开关页面
adb shell am broadcast -a com.os4.musiccover.PROBE -p com.android.systemui --es op immersive --es id amap-transit --es do open
```

## 七、构建

```sh
./gradlew :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease \
  -PmcVersionCode=<比已装的大> -PmcVersionName=0.7.10 -PmcVersionSuffix=-<这次的主题>.20261006
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am force-stop com.autonavi.minimap    # 装模块不会重载，必须重启高德
```

改动落在 SystemUI 侧时（`AmapTransitScene` / `AmapTransitIsland` 的常量）还要重启 SystemUI 才验得了：
`adb shell "su -c 'killall com.android.systemui'"`，等约 4 秒探针才注册得上。**重启前要先问用户。**

写代码的坑：`AmapTransitScene.java` 是 **Java**（Kotlin 的 `isNotEmpty()` 之类不能用）；Kotlin 里注意别用 `top` 这类容易遮蔽的名字。
