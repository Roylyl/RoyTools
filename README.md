<p align="center">
  <img src="RoyTools-icon.png" width="120" alt="RoyTools应用图标">
</p>

<h1 align="center">RoyTools</h1>

<p align="center">在Android、iPhone和iPad上播放和离线下载音乐，使用Mihomo代理。Android还支持Telegram通知转发和刷新率控制。</p>

<p align="center">
  <a href="https://github.com/Roylyl/RoyTools/releases/latest"><img src="https://img.shields.io/github/v/release/Roylyl/RoyTools?style=flat-square&amp;display_name=tag&amp;label=%E5%B7%B2%E5%8F%91%E8%A1%8C&amp;color=007AFF" alt="最新公开发行版本"></a>
  <a href="#快速开始"><img src="https://img.shields.io/badge/Android-12%2B-007AFF?style=flat-square" alt="Android12及以上"></a>
  <a href="#ios安装"><img src="https://img.shields.io/badge/iOS-17%2B-555555?style=flat-square" alt="iOS17及以上"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/Roylyl/RoyTools?style=flat-square&amp;label=%E8%AE%B8%E5%8F%AF&amp;color=555555" alt="开源许可证"></a>
  <a href="https://github.com/Roylyl/RoyTools/releases"><img src="https://img.shields.io/github/downloads/Roylyl/RoyTools/total?style=flat-square&amp;label=%E7%B4%AF%E8%AE%A1%E4%B8%8B%E8%BD%BD&amp;color=007AFF" alt="发行附件累计下载量"></a>
  <a href="https://github.com/Roylyl/RoyTools/stargazers"><img src="https://img.shields.io/github/stars/Roylyl/RoyTools?style=flat-square&amp;label=Stars&amp;color=555555" alt="GitHubStars数量"></a>
</p>

<p align="center">
  <a href="https://github.com/Roylyl/RoyTools/releases/latest">Android下载</a> · <a href="#ios安装">iOS安装</a> · <a href="#从源码构建">源码构建</a> · <a href="CHANGELOG.md">更新日志</a> · <a href="https://github.com/Roylyl/RoyTools/issues">问题反馈</a>
</p>

## 快速开始

### Android安装

Android版适用于Android12及以上的arm64-v8a设备。下载APK后安装，覆盖安装需沿用相同签名。要使用当前源码中的改动，可在本机构建APK。[GitHub发行页](https://github.com/Roylyl/RoyTools/releases/latest) · [Android构建](#android构建)

### iOS安装

iOS和iPadOS支持17及以上版本。使用macOS上的Xcode构建和安装完整版，真机VPN和跨扩展共享数据所需能力为Packet Tunnel与App Groups。使用免费开发者账户时，可以在仓库外生成无VPN测试版，用于音乐和设置。[iOS构建与签名](ios/README.md) · [免费开发者安装方法](ios/iOS免费开发者无VPN版本安装方法.md)

### 开始使用

- 打开“音乐→全部歌曲”，也可从专辑或歌单选曲。iOS26及以上采用Liquid Glass控件，iPad版按窗口宽度调整导航、列表和全屏播放页。
- 使用代理时，打开“代理→配置”，导入自己的HTTPS订阅或本地Clash/Mihomo YAML。选中配置后回到代理首页，打开VPN开关并按系统提示授权。

## 音乐

- 按歌名、歌手或专辑搜索，也可浏览收藏和歌单。在“音乐→设置→检查曲库更新”手动同步曲库和歌单。日常打开应用时读取本地目录，离线也可查看上次同步的内容。[Roylyl/Music曲库](https://github.com/Roylyl/Music) · [Roylyl Music网页](https://github.com/Roylyl/roylyl.github.io/tree/main/music)
- 底部迷你播放器可左右滑动切歌，点按进入全屏播放。全屏页提供封面、同步歌词、播放模式、队列和定时关闭。“下一首播放”按添加顺序插入待播队列。iOS可在锁屏和控制中心播放、暂停、切歌和拖动进度。
- 可下载单曲、专辑或歌单。“已下载曲目”里的“下载全部曲目”同时加入对应歌词和封面。“边听边存”默认开启，歌曲完整缓存后自动移入已下载列表。“管理缓存”可查看和清理音频、歌词、封面，也可单独补下载歌词或封面。
- “正在下载”逐项显示进度和速度。可同时下载1至5项，任务进行时可继续添加，也可优先下载、移除单项、暂停或清空队列。Android使用三点菜单，iOS使用列表滑动操作。

## 代理

|模式|流量走向|
|---|---|
|默认|沿用当前订阅或配置自带的模式与规则。|
|规则|按Roylyl分流规则处理，节点来自当前配置。|
|音乐|仅代理RoyTools获取曲库、音频、歌词和封面的匹配请求。|
|AI|使用AIforHK的AI域名和IP规则，未匹配流量直连。|
|全局|全部流量通过所选代理线路。|
|直连|全部流量直接连接。|

- “配置”支持编辑、删除以及单独或批量更新订阅。“代理节点”支持选代理组、组内切换、单测与批量测速。
- “局域网共享”可设置固定端口，并显示可复制的HTTP和SOCKS5地址。使用同一Wi-Fi或iPhone个人热点时，在客户端填入对应地址和端口。

Android和iOS均可在代理首页检查规则更新。规则、音乐和AI模式共用仓库发布的分流数据，更新会保留已选节点，离线时使用本地规则。规则版本独立于应用版本。[规则清单](routing-version.json) · [规则数据](routing-rules.json) · [更新说明](docs/ROUTING_RULES.md)

## Android专属功能

### 通知转发

打开“通知转发→转发设置”，将Android通知发到Telegram。在Telegram中联系@BotFather，发送`/newbot`创建机器人。进入与新机器人的会话，点击“开始”并发送一句话，然后在RoyTools填入Bot Token、获取Chat ID、授予通知使用权并发送测试消息。

RoyTools会过滤重复、常驻和分组摘要等通知。待发任务断网后会重试，超过24小时则过期。Chat入口可与配置的会话双向收发文字、图片、视频和文件，也支持从系统分享菜单导入。聊天记录保存在本机，可在“转发设置→数据管理”清理。[隐私说明](PRIVACY_POLICY.md)

### Android刷新率

刷新率总开关默认关闭。开启后可在“自适应、低刷新率、高刷新率”之间切换。自适应模式在触控时升频，达到所设静置时间后降频。低、高档的可用刷新率由设备和系统决定。

1. 手机连上Wi-Fi并开启开发者选项。允许RoyTools通知，在刷新率页打开总开关，点“配置辅助服务→打开无线调试”。
2. 在系统设置开启无线调试，选择“使用配对码配对设备”。保持窗口打开，在RoyTools的配对码通知中输入6位配对码。
3. 配对后RoyTools会尝试自动连接。若提示填写端口，使用系统“无线调试”首页上的连接端口。回到RoyTools，看到“辅助服务运行中”即可选择模式。

## 小组件与设置

双端桌面小组件可启停代理、切换代理模式、播放、暂停和切歌。iOS18及以上可在控制中心添加代理开关。“关于RoyTools”可按模块查看、导出和清空日志。反馈问题时，请注明应用版本、系统版本和复现步骤，并隐去日志中的订阅和节点凭据。[提交问题](https://github.com/Roylyl/RoyTools/issues)

## 从源码构建

仓库包含[完整Android工程](android/)和[完整iOS工程](ios/)。当前源码版本均为2.3.0。安装包、本地签名和调试工程放在仓库外。

### Android构建

准备JDK21和Android SDK35，在仓库根目录执行：

```bash
bash android/gradlew -p android :app:assembleMetaDebug
```

APK输出到`android/app/build/outputs/apk/meta/debug/`。首次构建会准备GeoData。Windows入口、离线构建输入、原生核心重建和发行签名见[Android构建说明](docs/BUILDING.md)。

### iOS构建

准备macOS、Xcode26或更新版本及Go1.26.10。在仓库根目录构建Mihomo核心、准备GeoData，再用Xcode打开工程。

```bash
bash ios/scripts/build-engine.sh
bash ios/scripts/fetch-geodata.sh
open ios/RoyTools.xcodeproj
```

## 来源与许可

RoyTools的代理功能基于Clash-Meta-Plus、[ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid)和[Mihomo](https://github.com/MetaCubeX/mihomo)。Android刷新率模块使用[Adaptive-Hz](https://github.com/mahmutaunal/Adaptive-Hz)、[Shizuku](https://github.com/RikkaApps/Shizuku)及[Shizuku-API](https://github.com/RikkaApps/Shizuku-API)。通知转发参考[sms-telebot](https://github.com/teslor/sms-telebot)与[NotificationForwarder](https://github.com/ItsAzni/NotificationForwarder)，界面设计参考[ASRtestII](https://github.com/Roylyl/ASRtestII)。

项目以[GNU GPLv3](LICENSE)分发，上游组件保留各自的版权和许可。歌曲、歌词和专辑封面适用各自的权利条件。[上游来源](docs/UPSTREAM.md) · [第三方许可与媒体说明](THIRD_PARTY.md) · [版权声明](NOTICE) · [贡献指南](CONTRIBUTING.md)
