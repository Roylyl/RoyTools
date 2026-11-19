# RoyTools iOS

RoyTools iOS包含音乐、代理、灵动岛和设置。工程采用SwiftUI，iOS26由系统为导航、底部标签栏、开关和播放进度滑块提供Liquid Glass样式，iOS17起沿用对应系统的原生控件。

## 构建

使用macOS、Xcode26或更新版本及Go1.26.10。从仓库根目录先构建Mihomo核心，生成`ios/Engine/RoyToolsCore.xcframework`，再准备GeoData：

```bash
bash ios/scripts/build-engine.sh
bash ios/scripts/fetch-geodata.sh
```

GeoData脚本会复用`ios/Engine/GeoData/`中已存在的非空文件，从上游下载其余文件。离线构建可将`geoip.metadb`、`geoip.dat`、`geosite.dat`和`ASN.mmdb`（也可命名为`GeoLite2-ASN.mmdb`）放进本机目录，然后运行`ROYTOOLS_GEODATA_DIR=/path/to/data bash ios/scripts/fetch-geodata.sh`。[MetaCubeX数据来源](https://github.com/MetaCubeX/meta-rules-dat#下载地址)

打开`ios/RoyTools.xcodeproj`并选择`RoyTools`方案。从仓库根目录构建无签名的模拟器版本：

```bash
xcodebuild -project ios/RoyTools.xcodeproj -scheme RoyTools \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

主应用、Packet Tunnel和Live Activity扩展共用`Config/Version.xcconfig`，两个扩展会随主应用一起构建。真机运行时，在`RoyTools`、`RoyToolsPacketTunnel`和`RoyToolsIslandWidget`三个目标选择同一签名团队。主应用和Packet Tunnel目标需要启用Network Extensions的Packet Tunnel能力，并加入`group.com.roylyl.tools`应用组。[Network Extensions配置](https://developer.apple.com/documentation/xcode/configuring-network-extensions/) · [App Groups配置](https://developer.apple.com/documentation/xcode/configuring-app-groups)

## 连接代理

在“代理→配置”导入HTTPS订阅或本地Clash/Mihomo YAML，打开“VPN开关”并接受系统的VPN授权提示。连接后可以选择代理组和节点。

|模式|规则来源与作用范围|
|---|---|
|默认|使用当前订阅或配置自带的规则。|
|规则|使用仓库规则包中的配置规则。|
|音乐|根据仓库的音乐域名清单，仅将RoyTools的匹配音乐请求送入隧道扩展提供的本机代理。|
|AI|使用仓库规则包中的AI规则，未匹配流量直连。|

“检查规则更新”从仓库根目录下载并校验规则。代理页同时显示本地规则版本和连接中的生效版本。订阅更新在“配置”内操作。[规则更新说明](../docs/ROUTING_RULES.md)

## 目录

|目录|内容|
|---|---|
|`RoyTools/Features/Music/`|同步Roylyl曲库，浏览歌曲、专辑、歌单并通过AVPlayer播放。|
|`RoyTools/Features/Proxy/`|配置导入、VPN控制、节点选择和仓库规则更新。|
|`RoyTools/Features/Island/`|用ActivityKit管理实时活动和采样项。灵动岛展示由系统与机型决定。|
|`RoyToolsIslandWidget/`|锁屏和灵动岛的WidgetKit布局。|
|`RoyToolsPacketTunnel/`|系统VPN扩展，承载网络隧道、规则切换和流量统计。|
|`Engine/`|Mihomo核心的iOS构建和Packet Tunnel桥接。|
|`SharedTunnel/`|主应用和隧道扩展共享的配置与消息格式。|
|`SharedIsland/`|主应用和扩展共用的活动数据模型。|

## 后台运行

Mihomo在系统管理的Packet Tunnel扩展中运行。主应用重新打开后向系统读取连接状态。后台模式`audio`只用于实际的音乐播放。音乐档的匹配音频先下载到本机缓存，再交给播放器。

灵动岛使用ActivityKit。代理速度读取隧道扩展的流量统计，退出前台后旧读数标为过期。屏幕刷新率显示RoyTools前台画面的更新频率。

音乐继续使用Roylyl云端曲库。两端的分流规则从仓库根目录发布，格式见[规则更新说明](../docs/ROUTING_RULES.md)。
