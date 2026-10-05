# 新对话提示词（高德公交/地铁逐站岛移植）

仓库 `zyl6932/HyperMusicCover`（HyperOS 的 LSPosed 模块），在分支 `claude/zen-cannon-m7khd8` 上继续。
先读 `docs/amap-transit-island.md`——它已按 2026-10-02 的真机实测重写过，是当前的准确版本。

## 现状（截至 `5f38418`，2026-10-02）

链路是通的：高德进程接住行程数据 → 焦点岛 + SystemUI 逐站页都能上屏。**注意：本文档的旧版里列的三个问题
（card 样式不显示 / acquires=0 / 真实导航未验证）都已作废**，别照着旧结论查。

已经落地的关键点：

- **数据来源不是 OPPO 的 `IntelligentIntent`**。高德脚本在小米机型上不 `bizBegin(10200)`，那条路没数据。
  真机数据走高德自己的 **bizType 103**（OPPO AOD 行程卡：线路胶囊 + 颜色 + `titleItems`）和
  **bizType 113**（`amap_glass` 实时：剩余站数、车辆坐标、地铁倒计时），两条都每 1–3 秒一次。
  `AmapTransitShare.ride()`/`show()` 把它们合成 ColorOS 形状的 `GaoDePtIntentEntity` 交给 SystemUI。
- **焦点卡曾经被插件丢掉**，死因是 `FocusNotificationController.fetchAuthResult` 里的
  `canCustomFocus(高德)`（云端名单），不是跨包 RemoteViews。`AmapFocus.java` 盯 `BaseDexClassLoader`
  等控制中心插件的 loader 出现，只对 `com.autonavi.minimap` 答 true，把它修好了。
- 10200 的桥接（`bridgeOppo`，把 il3 的设备配置注入 `xn0.a` 的设备表 + 补 `bizBegin`）**仍在**，
  但有没有真的贡献数据没验证过。
- `titleItems` 是一句话切成碎片，不是固定槽位；剩余站数公交看 `arriveRemind.remainStopNum`、
  地铁看 `location.remainStations`；`arriveRemind.curStopName/nextStopName` 压过卡片那句话。
  这些读法全部以真机载荷为准，改动前先看 `docs/amap-transit-island.md` §4。

## 待办（按优先级）

1. **公交路径（`transportType 1`）和换乘站（status 6）没有真机记录**。代码有分支，但 2026-10-02 只跑了
   厦门地铁 2 号线。坐一次公交、坐一次要换乘的线路，然后：

   ```sh
   adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --ez full true
   adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --ez max true    # 整本 ledger
   adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --ez events true # 发送间隔
   ```

   重点看 `arriveRemind` 在公交上有没有 `remainStopNum`、`location.remainStations` 在公交上是什么、
   换乘时 103 的 `planData` 和 `mainText` 怎么走。
2. **判断桥接 10200 是不是白做**。看探针的 `shares=` 是否长期为 0、`bridge:` 里各通道的结果。
   若无贡献，删掉 `bridgeOppo` 那一套（`AmapTransitShare.kt:500-567` 及相关状态），能省不少复杂度。
3. **验一次息屏**：岛的 `aodTitle/aodPic`、页面的 doze 分支（`lift`、`CountdownScene.GroundSurface`）
   都没有实测记录。注意息屏录屏录不到，得看真机。
4. **分支落后 main 三个提交**（`ea45a40` / `4afcbc6` / `65defc5`，动的是首页、设置页、SkeuoKit、
   strings），要进 main 得先合。
5. 焦点卡默认是 `template`（系统大模板，带进度条），`card` 和 `flat` 是备用。确认过没人用之后再决定
   要不要删。

## 调试命令（完整表在 `docs/amap-transit-island.md` §7）

```sh
# 高德进程：脚本调用、通道采样、provider 计数、焦点岛状态、bridge、发送频率
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --ez full true
# 把一段真载荷重新走一遍 ride()，试形状不用坐车
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --es transit raw --es json '<base64>'
# 切换焦点卡样式
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --es island card|template|flat
# 整条链路演示（高德进程灌演示数据 → 焦点岛 + SystemUI 页）
adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --es transit demo
```

## 构建

云端容器里需要自己装 Android SDK（platform `android-37.0`，build-tools 36），并写 `local.properties`
（`sdk.dir=/opt/android-sdk`）。Maven Central 偶尔返回 429，重试即可。release 构建：

```sh
./gradlew :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease
```

没有正式签名，出的包是 debug 签名，装之前要先卸载正式版。
