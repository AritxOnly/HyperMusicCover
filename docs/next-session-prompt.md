# 新对话提示词（高德公交/地铁卡 · 第五轮）

仓库 `zyl6932/HyperMusicCover`（HyperOS 的 LSPosed 模块），分支 `claude/zen-cannon-m7khd8`。
先读这两份文档，它们是当前的准确版本：

- `docs/amap-transit-island.md` —— 数据来源、架构、ColorOS 规则的对应、调试命令
- `docs/coloros-island-stages.md` —— ColorOS 每个里程碑的小岛/锁屏显示、生命周期表

ColorOS 反编译：`D:\coloros17-re\jadx-full\SceneService\sources\`（**不是** `jadx\`，那份缺卡片构建器 `ya.*`）。
`.scratch/` 在仓库上一级：`C:\Users\。\Desktop\music lockscreen\.scratch\amap-ledger\`。

## 一、上一轮（2026-10-06）干了什么

用户要求「完全同步 ColorOS 17 的地铁出行逻辑，顺便 review」。做法：

1. **卡片构建器逐函数移植**：`AmapTransitCard.java` = SceneService `ya.b`（各里程碑卡片、选站、换乘、到站、候车）、
   `ya.e`（三节点/两节点）、`ya.k`（概览）、`ya.a`（终点卡）、`ya.f`（偏航卡）、`ya.n.f`（静默步行卡）、路由 `j()`；文案逐字。
2. **实体按 ColorOS 的形状拼完整**：`AmapTransitEntity.kt` 用开始导航时的整条计划（113 `type 24`）+ 103 卡片胶囊 +
   113 实时，拼出含全部分段、途经站、换乘站、出站口的 `GaoDePtIntentEntity`。上一版只有一段、途经站是假的一个，
   靠 SystemUI 那边的补丁规则凑。
3. **里程碑推导抽成纯类**：`AmapTransitMilestones.kt`（候车/当前站/下一站/即终点/换乘/到站保留）。
4. **生命周期照 ColorOS**：地铁到站保留 5 min、最后一段是乘车时的终点卡、终点卡 30 s、按状态自动消失（30 s/15 min/30 min）、
   `bizEnd(103)` 的忽略条件。
5. **删掉的**：`isOppo` 伪装、ROM 探测；（OPPO 桥一度删掉，实测后发现它是 113 通道有数据的前提，已作为 `AmapOppoBridge.kt` 恢复，见文档 §3）；
   焦点通知的三种只供探针用的样式和 `mc_transit_card.xml`；`simulate()` 假卡片；`AmapFocus` 每次通知都打一行日志。
6. 离线测试 `AmapTransitCardTest`（10-05 真实行程逐步断言 + 一个换乘计划），全部 72 个单测通过。

## 二、没验的（下一轮第一件事）

真机已验（`0.7.10-coloros-bridge.20261006`，710072）：10-05 回放全程正确；用户实际的 4 段换乘路线（12号线→11号线外环→城际→11号线机场线）
经 `transit sim` 逐站模拟全程正确，锁屏页终点卡 30 s 自撤。**还没有真坐一趟。**

1. 回放：重启高德和 SystemUI 后跑 `python .scratch/amap-ledger/replay1005.py`，看每一步的 `transit:` 行和岛的日志行，
   同时看手机上小岛 / 展开卡 / 锁屏页。
2. 真坐一趟地铁：最想确认的是
   - 开始导航那一刻 `type 24` 计划是否到了且对得上（探针 `fits=true`，`plan[0]` 有途经站）；
   - 候车 → 当前站 → 下一站 → 下一站即终点 → 下车后「到站 + 出站口」保留，起步行导航后让给高德的步行岛；
   - 小岛左半的线路色块图片（候车的「7」、到站的「B口」）、右半的「往XX」在插件里是否正常显示（`picInfo` 在右半是第一次用）。
3. 换乘、公交、偏航、只有乘车没有末段步行的行程：仍然没有真机载荷。

## 三、推出来、不是 ColorOS 原样的地方

- 候车何时结束：地铁站间没有任何实时信号，只能等第一站过去。
- 停站 30 s（`DWELL_MS`）、换乘/公交到站保留 30 s：ColorOS 显示到高德说下一件事为止，这里高德当场就说了，只好给个停站时长。
- 刷乘车码出站：ColorOS 用它提前结束地铁到站卡，这里没有信号，用「高德步行导航岛出现」代替。

## 四、构建与安装

```sh
./gradlew :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease \
  -PmcVersionCode=<比已装的大> -PmcVersionName=0.7.10 -PmcVersionSuffix=-<主题>.2026100X
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am force-stop com.autonavi.minimap    # 装模块不会重载
./gradlew :app:testDebugUnitTest --tests com.os4.musiccover.AmapTransitCardTest   # 离线测卡片
```

SystemUI 侧（`AmapTransitScene` / `AmapTransitCard` / `AmapFocus`）要重启 SystemUI 才生效，**重启前先问用户**。
