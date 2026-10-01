# 新对话提示词（高德公交/地铁逐站岛移植）

仓库 `zyl6932/HyperMusicCover`（HyperOS 的 LSPosed 模块），在分支 `claude/zen-cannon-m7khd8` 上继续。先读 `docs/amap-transit-island.md`（逆向原理和全部细节）。

## 背景
ColorOS 17 锁屏的高德公交/地铁逐站卡不是高德画的。高德脚本用 `ContentProviderClient.call("shareIntent")` 把行程 JSON 交给 OPPO 的 `IntelligentIntent`（intent `Navigation.NotifyPublicTransportStatus`），由 OPPO SceneService 画卡（卡片 536879317）。每站背景图是 OPPO 按站点坐标查内置地标表（42 城，半径 0.8 km）得到的，图在 OPPO 的公开 CDN 上。

## 已完成（都已提交并推送，最新 `c521436`）
- `AmapTransitShare.kt`（高德进程）：冒充 `IntelligentIntent`。hook `acquireUnstableContentProviderClient`，换成 Settings 的 client，再拦截 `call`，按 SceneService 的格式回答，最后用 `op transit` 转给 SystemUI。
- `AmapTransitIsland.kt`（高德进程）：澎湃上高德只有步行/骑行才发焦点通知，所以公交/地铁段由模块以高德身份补发焦点通知（id 1239，显示期间压住高德自己的 1237）。有三种样式，探针可切换：`--es island card|template|flat`。
  - `card`（默认）：`miui.focus.rv` 自定义布局 `res/layout/mc_transit_card.xml`，用模块包名构造 RemoteViews，超级岛走 `miui.focus.param.custom`。
  - `template`：系统大模板 `param_v2`（baseInfo / multiProgressInfo / bgInfo）。
  - `flat`：protocol 1 的两行模板。**只有这个在真机上显示正常**。
- `AmapTransitScene.java`（SystemUI）：锁屏全屏逐站页，点岛打开，照 OPPO 五一路的样式：横向三节点进度，换乘站是 ⇄ 胶囊加线路号徽标，中间地标动图。演示数据下真机显示正常。
- `AmapTransitLandmarks.java`：OPPO 地标表。`Main` 里有 `op transit`，`AmapImmerse.kt` 负责启动和探针。

## 当前问题（按优先级）
1. **`card` 样式在澎湃上不显示**。探针返回 `island: style=card posted=true art=yes`，没有 error，但看不到卡片。
   - 猜测：澎湃不接受跨包的 `miui.focus.rv`（通知属于高德，RemoteViews 的布局却来自模块包）。
   - 先看 `adb logcat | grep -iE "RemoteViews|inflat|focus"` 确认。
   - 备选方案 A：把整张卡在高德进程里画成一张 Bitmap，套用**高德自己 APK 里**只含一个 ImageView 的布局发出（RemoteViews 包名用高德，避开跨包）。要先用 aapt2 在高德 17.00.0.2005 的资源里找合适的布局。
   - 备选方案 B：试 `--es island template` 看系统大模板能不能显示。
   - 备选方案 C：模块本来就 hook 了 SystemUI，可以在 SystemUI 里直接给 id 1239 的通知行画自定义卡片。
2. **真实导航还没验证**。到目前为止探针一直是 `acquires=0 queries=0 shares=0`（数据全是 demo 灌的）。要坐一次地铁并开高德导航，再跑 `adb shell am broadcast -a com.os4.musiccover.AMAPPROBE` 看 `transit:` 那一行。
   - `c521436` 的探针还会记录高德脚本请求了哪些设备通道（`NativesModuleWearable`，OPPO 卡是 bizType 10200）。
   - 如果 `acquires` 一直为 0：说明高德的加密脚本（`assets/ajx.bundle/bundles.oajx`）在小米机型上没有启用 OPPO 通道，需要另找数据来源，比如伪装机型，或者在脚本注册设备的地方动手。
3. 确认到站、换乘时岛会不会重新上浮。

## 调试命令
- 高德端演示（带岛）：`adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --es transit demo`（结束用 `end`）
- 切换样式：`adb shell am broadcast -a com.os4.musiccover.AMAPPROBE --es island card|template|flat`
- SystemUI 端：`adb shell am broadcast -a com.os4.musiccover.PROBE -p com.android.systemui --es op transit --ez demo true`
- 打开/关闭页面：`... --es op immersive --es id amap-transit --es do open|close`

## 构建
云端容器里需要自己装 Android SDK（platform `android-37.0`，build-tools 36），并写 `local.properties`（`sdk.dir=/opt/android-sdk`）。Maven Central 偶尔返回 429，重试即可。release 构建：

```sh
./gradlew :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease
```

没有正式签名，出的包是 debug 签名，装之前要先卸载正式版。

## 附件（需要我手动上传）
- `amap-transit-reverse.zip`：反编译材料（SceneService 公交模块、SystemUIPlugin 宿主、高德 OPPO 通道类、地标 JSON、示例图）。来源：ROM `ColorOS17-v16-fuxi-FULL-20260928`，高德 17.00.0.2005。
- 高德 APK 可从腾讯应用宝 CDN 下载：`http://imtt.dd.qq.com/16891/apk/F25C34C560E894F6D34F3B798E38A286.apk`（MD5 f25c34c5…a286）。
- 截图：OPPO 五一路参考卡片、当前真机效果。
