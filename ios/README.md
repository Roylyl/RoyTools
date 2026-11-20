# RoyTools iOS

RoyTools iOS包含音乐、代理和设置。工程采用SwiftUI，iOS26由系统为导航、底部标签栏、开关和播放进度滑块提供Liquid Glass样式，iOS17起沿用对应系统的原生控件。

## 构建

使用macOS、Xcode26或更新版本及Go1.26.10。从仓库根目录先构建Mihomo核心，生成`ios/Engine/RoyToolsCore.xcframework`，再准备GeoData：

```bash
bash ios/scripts/build-engine.sh
bash ios/scripts/fetch-geodata.sh
```

GeoData脚本会复用`ios/Engine/GeoData/`中已存在的非空文件，从上游下载其余文件。离线构建可将`geoip.metadb`、`geoip.dat`、`geosite.dat`和`ASN.mmdb`（也可命名为`GeoLite2-ASN.mmdb`）放进本机目录，然后运行`ROYTOOLS_GEODATA_DIR=/path/to/data bash ios/scripts/fetch-geodata.sh`。[MetaCubeX数据来源](https://github.com/MetaCubeX/meta-rules-dat#下载地址)

打开`ios/RoyTools.xcodeproj`并选择`RoyTools`方案。模拟器使用Xcode默认的本地签名，主应用和小组件扩展均可按以下方式构建。

```bash
xcodebuild -project ios/RoyTools.xcodeproj -scheme RoyTools \
  -configuration Debug -destination 'generic/platform=iOS Simulator' build
```

主应用、Packet Tunnel和WidgetKit扩展共用`Config/Version.xcconfig`，两个扩展会随主应用一起构建。真机运行时，在`RoyTools`、`RoyToolsPacketTunnel`和`RoyToolsWidgets`三个目标选择同一签名团队。主应用和Packet Tunnel目标需启用Network Extensions的Packet Tunnel能力。三个目标均需加入`group.com.roylyl.tools`应用组，以共享代理和小组件数据。[Network Extensions配置](https://developer.apple.com/documentation/xcode/configuring-network-extensions/) · [App Groups配置](https://developer.apple.com/documentation/xcode/configuring-app-groups)

使用免费开发者账户测试音乐和设置时，可在仓库外构建并安装无VPN测试版。[免费开发者安装方法](iOS免费开发者无VPN版本安装方法.md)

## 连接代理

在“代理→配置”导入HTTPS订阅或本地Clash/Mihomo YAML，打开“VPN开关”并接受系统的VPN授权提示。连接后可以选择代理组和节点。

|模式|规则来源与作用范围|
|---|---|
|默认|使用当前订阅或配置自带的规则。|
|规则|使用仓库规则包中的配置规则。|
|音乐|根据仓库的音乐域名清单，仅将RoyTools的匹配音乐请求送入隧道扩展提供的本机代理。|
|AI|使用仓库规则包中的AI规则，未匹配流量直连。|
|全局|通过所选代理线路转发流量。|
|直连|流量直接连接。|

“检查规则更新”从仓库根目录下载并校验规则。代理页同时显示本地规则版本和连接中的生效版本。订阅更新在“配置”内操作。[规则更新说明](../docs/ROUTING_RULES.md)

## 目录

|目录|内容|
|---|---|
|`RoyTools/Features/Music/`|同步Roylyl曲库，浏览歌曲、专辑、歌单并通过AVPlayer播放。|
|`RoyTools/Features/Proxy/`|配置导入、VPN控制、节点选择和仓库规则更新。|
|`RoyToolsWidgets/`|桌面小组件和控制中心代理开关。|
|`RoyToolsPacketTunnel/`|系统VPN扩展，承载网络隧道、规则切换和流量统计。|
|`Engine/`|Mihomo核心的iOS构建和Packet Tunnel桥接。|
|`SharedTunnel/`|主应用和隧道扩展共享的配置与消息格式。|
|`SharedWidgets/`|主应用和扩展共用的小组件数据。|

## 后台运行

Mihomo在系统管理的Packet Tunnel扩展中运行。主应用重新打开后向系统读取连接状态。音乐档的匹配音频先下载到本机缓存，再交给播放器。

播放音乐后，可在锁屏和控制中心查看曲目信息，播放、暂停、切歌或拖动进度。退到桌面后，播放器继续按当前队列和播放模式接续。打开音乐设置中的“预加载下一首”，可在当前歌曲播放期间提前载入下一首。

音乐继续使用Roylyl云端曲库。两端的分流规则从仓库根目录发布，格式见[规则更新说明](../docs/ROUTING_RULES.md)。
