# 新对话提示词（高德公交/地铁逐站岛 · 第二轮）

仓库 `zyl6932/HyperMusicCover`（HyperOS 的 LSPosed 模块），分支 `claude/zen-cannon-m7khd8`。
先读两份文档，它们是当前的准确版本：

- `docs/amap-transit-island.md` —— 架构、数据来源、地标、调试命令
- `docs/coloros-island-stages.md` —— ColorOS 每个里程碑的小岛/锁屏各显示什么，以及**键名 ↔ 视觉左右**的判据

## 一、这一轮最大的收获：站点表拿到了

上一轮的问题是「拿不到 `via_st_list`，所以报不出下一站」。**答案在 113 通道的 `type 24` 里**：

```
113 sendMessage 的 datas 里，一条是 type 7（naviType），一条是 type 24，一条是 type 25
             type 25 = 实时数据（当前站、剩余站数、倒计时）—— 一直读的就是它
             type 24 = 整条路线计划 —— 站点表在这里
```

`type 24` 的 `segmentlist[]` 每段就是一条腿，字段是：

```json
{ "bus_key_name":"7号线", "busname":"地铁7号线(…)", "color":"…", "directionName":"…",
  "cityCode":"440100", "inport":{"name":"E口"}, "outport":{"description":"通往D口-…"},
  "on_station":  {"name":"大学城南", "id":"…", "coord":{…}},
  "via_st_list": [{"name":"深井",…,"is_trans":true}, {"name":"长洲",…}],
  "off_station": {"name":"裕丰围", "id":"…"} }
```

实测（一条四段路线，**开路线详情页即可，不必坐车**）：

```
route[0] 7号线:                   大学城南 → 深井 → 长洲 → 裕丰围
route[1] 13号线:                  裕丰围 → 双岗 → 南海神庙 → 夏园 → 南岗 → 沙村 → 白江 → 新塘
route[2] 城际(白云机场北-深圳机场): 新塘南 → 东莞西 → 虎门北 → 长安 → 深圳机场
route[3] 11号线(机场线):           机场 → 碧海湾 → 宝安
```

**为什么以前没看到**：两件事叠加——ledger 当时「每通道只留最新一条」（被 type 25 覆盖），而读取只取 `datas[0]`。两处都已修（按 `type` 取、ledger 按载荷形状分桶）。

## 二、当前未提交的改动（4 个文件，`git status` 可见）

```
 M AmapImmerse.kt          -- 探针加了 --es oppo true|false
 M AmapTransitIsland.kt    -- 小岛左右用 Frame 的新字段；--es island scene 不再清空 scene
 M AmapTransitScene.java   -- Frame.islandLeft/Right、status 6 小岛右半是「要换乘的线路」
 M AmapTransitShare.kt     -- 主体：type 24 解析、边界修复、review 修复
```

里面包含：

1. **type 24 解析** → `route`；`sequence(line)` 把当前段拼成 `on → via… → off`；`show()` 的「下一站」优先取序列里当前站的后一站，高德的 `nextStopName` 退为兜底。
2. **一批边界修复**（都是既存 bug，不是新引入的）：
   - `lastRide` 从未赋值 → `clear()` 永远空转 → **行程结束从不撤卡**；`riding` 只置 true 从不复位
   - 步行卡的「行程是否结束」用了**两个不同坐标系的索引**（高德的 `location.index` 数是它自己 `planData` 的腿，含步行胶囊；模块的 `plan` 滤掉了步行）→ 改用卡片自己的 `planData.length()`
   - `delete()`（高德删意图）不清状态 → 行程结束后下一个迟到载荷**把卡贴回来** → 改走 `clear()`
   - status 6 小岛右半写的是「正在离开的线路」→ 改成 `nextLine`
   - `stopIn()` 假设「·」是独立片段；`dest` 退回被播报的站名而非线路终点；`--es island scene` 把 scene 清空
3. **code-review 的 11 条全部处理**：段名匹配加了两轮 + 「匹配位置前一位是数字就拒绝」（`1号线` 不再中 `11号线`）；`watchRom` 只调 `is*` 谓词 + `getName`/`getVersion`；ledger 上限降到 16×8000 字符；`shape()` 对短载荷早退。
4. **刚修的一处**：换计划时**不再清空 `route`**。路线详情每显示一条线就发一次 103 计划卡，原来每发一次就把刚到的站点表清掉——多段路线下 `route:` 永远显示 `nothing yet`，就是这个。

提交状态：HEAD 是 `049df20`（文档）/ `502a9ad`（代码），**比远端领先 2 个提交、没推**；上面 4 个文件的改动**还没提交**。

## 三、待验证（按优先级）

1. **`route[0..n]` 逐段列出**——装最新包后**打开一条多段路线详情**即可，不用坐车。
   上一版因为「换计划清 route」显示 `nothing yet`；这一版应该正常。**这是最先要跑的**。
2. **下一站的逐站命名**——**必须坐车**。`riding` 只有在「非步行」的行程卡到达后才为 true，路线详情页不会置它。
   期望：每一站都报「下一站 X」，包括中间站（不再只在剩一站时才敢说终点）。
3. **「当前站 / 下一站」的取舍**（设计问题，需要你拍板）：有了路线之后状态恒为 status 3，**「当前站」基本不再出现**。要区分「停在站台」和「在两站之间」，现有数据里只有 `locationData.speed`（停站时为 0）可用。

## 四、已知死路（别再撞）

- **`isOppo` 开关没用**：把它答成 true（`AMAPPROBE --es oppo true`），脚本仍然只 `bizBegin(113)` / `bizBegin(103)`，**不开 `bizBegin(10200)`**。所以脚本判断机型的依据不是（或不只是）`isOppo`。剩下 `getName`（现在是 `MIUI`）和 `getVersion`（`V816`）没试过，探针里都有。
- **逆向 OPPO 拿站点表是死路**：`via_st_list` 在 SceneService 里**只有 gson 反序列化**，全库搜过、没有任何地方构造或补全它——它是高德实体自带的。OPPO 在「没有这张表」时的降级是退回 `onStation`，**和我们旧行为一致**。
- `AMAPPROBE --es transit 进站|乘车|换乘|到达` 走的是 `simulate()`，**绕过脚本直接调 `ride()`**——测脚本行为（`script:` 行）时它永远不动，别拿它当证据。

## 五、调试手法与坑

- **Greezer**：高德退到后台会被系统冻结，探针广播被拒（logcat 里 `Greezer Denial … need cached broadcast`）。探针突然哑了，先把高德拉到前台。
- **adt 无线端口会变**：先 `adb mdns services` 找，再 `adb connect <ip>:<port>`。
- **装模块不会重载**：装完必须 `adb shell am force-stop com.autonavi.minimap` 再启，否则测的是旧代码。
- **探针**：
  ```sh
  adb shell am broadcast -a com.os4.musiccover.AMAPPROBE                 # 状态 + tail（含 route: 行）
  adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --ez max true   # ledger（每种载荷形状最后一条）
  adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --ez events true
  adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --es transit raw --es json '<base64>'  # 重放真载荷
  adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --es oppo true  # 机型欺骗开关（默认关）
  ```
- **读「实际下发了什么」**：系统会把整份 `miui.focus.param` 打进 logcat。
  `.scratch/amap-ledger/show_island.py <serial>` 能解析出**小岛左右、卡片大字/灰字、进度**。
- **抓取脚本**：`.scratch/amap-ledger/capture-device.sh`（推到手机上跑，写 `/sdcard/Download/amap-ride.log`，不依赖 adb/电脑）。上次那趟车的原始载荷在 `.scratch/amap-ledger/payloads/`（31 份，可直接 `raw` 重放）。
- **离线预览地标合成**：`.scratch/amap-ledger/preview.py <at> <zoom> <down>` —— 不用装包就能看背景图效果。

## 六、构建

本机已有 SDK（`C:\Android\Sdk`）和仓库里的 `release.jks`，**直接在 Windows 上编**：

```sh
./gradlew :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease \
  -PmcVersionCode=<比已装的大> -PmcVersionName=0.7.10 -PmcVersionSuffix=-<这次的主题>.20261005
adb install -r app/build/outputs/apk/release/app-release.apk
```

写代码时的坑：`AmapTransitScene.java` 是 **Java**，Kotlin 的 `isNotEmpty()` 之类不能用（写 `!s.isEmpty()`）；Kotlin 里注意别用 `top` 这类容易遮蔽的名字（踩过一次，报 `Unresolved reference 'and'`）。
