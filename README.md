<p align="center">
  <img src="RoyTools-icon.png" width="120" alt="RoyTools应用图标">
</p>

<h1 align="center">RoyTools</h1>

<p align="center">把音乐播放、网络代理和自适应刷新率放进一个Android应用。</p>

<p align="center">
  <a href="build.gradle.kts"><img src="https://img.shields.io/badge/version-1.0.0-007AFF?style=flat-square" alt="版本1.0.0"></a>
  <a href="#运行要求"><img src="https://img.shields.io/badge/Android-12%2B-007AFF?style=flat-square" alt="Android12及以上"></a>
  <a href="#运行要求"><img src="https://img.shields.io/badge/ABI-arm64--v8a-555555?style=flat-square" alt="arm64-v8a架构"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPLv3-555555?style=flat-square" alt="GNU GPLv3许可证"></a>
  <a href="https://github.com/Roylyl/RoyTools/releases"><img src="https://img.shields.io/badge/下载-APK-007AFF?style=flat-square" alt="下载APK"></a>
  <a href="https://github.com/Roylyl/RoyTools/issues"><img src="https://img.shields.io/badge/反馈-Issues-555555?style=flat-square" alt="问题反馈"></a>
</p>

<p align="center">
  <a href="#快速开始">快速开始</a> · <a href="#主要功能">主要功能</a> · <a href="docs/BUILDING.md">开发文档</a> · <a href="#上游与设计参考">上游来源</a> · <a href="https://github.com/Roylyl/RoyTools/releases">发行页</a>
</p>

## 快速开始

1. 前往[发行页](https://github.com/Roylyl/RoyTools/releases)，下载RoyTools的APK并安装。
2. 打开“音乐”，进入全部歌曲、专辑或歌单即可联网播放。应用附带曲库索引，音频、歌词和专辑封面从云端加载，无需填写音乐服务配置。
3. 需要代理时，进入“代理”导入自己的订阅地址或配置文件，再开启连接并接受Android的VPN授权。项目不提供节点、订阅或代理账户。
4. 需要刷新率控制时，在“刷新率→配置辅助服务”中完成手机端无线调试配对，再选择刷新率模式。

## 运行要求

- Android12及以上，arm64-v8a设备。
- 首次播放及下载音乐资源需要网络；完整下载到本机的曲目可离线播放。
- 代理连接需要自行准备可用订阅或配置。
- 刷新率控制需要相应系统权限；首次无线激活需连接Wi-Fi、开启开发者选项及无线调试。设备重启后可能需要重新激活。
- 可用刷新率取决于屏幕与系统实际提供的档位，应用不能增加硬件不支持的刷新率。

## 主要功能

### 音乐

- 全部歌曲、专辑、歌单和音乐设置，支持搜索、歌手筛选及排序。
- 全屏封面与滚动歌词、播放队列、定时停止、上一首/下一首及播放模式。
- 底部胶囊播放器支持左右滑动切歌，支持后台播放、媒体通知和系统媒体控制。
- 歌单、专辑与整个曲库批量下载，歌词与封面也可分别批量保存。
- 下载窗口显示百分比、进度和MB/s，支持取消与后台下载，通知栏同步显示进度。
- 已下载曲目管理复用搜索与筛选；单曲删除采用两次点击确认，也可统一删除下载与缓存。
- 缓存上限、下一首预加载，以及默认开启的全屏播放保持亮屏。

音频与歌词来源为[Roylyl/Music](https://github.com/Roylyl/Music)，专辑封面来自[音乐网页](https://roylyl.github.io/music/)的云端资源。APK不内置音频、歌词或专辑封面文件；下载后保存在应用本地缓存中。

### 代理

- 基于ClashMetaForAndroid衍生工程与mihomo核心，支持配置导入、订阅更新、节点选择和测速。
- 首页四档代理模式：默认、规则、全局、直连。
- 保留应用访问控制、网络、DNS、覆写和外部资源等配置入口。
- 首页显示代理流量速度，常驻通知可显示流量。
- 桌面小组件显示当前节点及当日Wi-Fi/移动数据代理业务用量，并可切换代理模式。

### 刷新率

- 关闭、60Hz、120Hz、自适应四档控制。
- 静置降频延迟以0.25秒递增，默认0.5秒。
- 内置Shizuku相关服务及无线配对流程，通过通知输入配对码，减少切换页面操作。
- 刷新率常驻通知可直接切换四档模式，控制中心提供自适应刷新率开关。

### 总设置与快捷控制

- 跟随系统、浅色和深色主题，默认跟随系统。
- 统一后台运行设置、应用图标与最近任务显示控制。
- 音乐、代理、刷新率和应用日志集中查看、分别导出，也可统一清空。
- 应用内检查GitHub发行页更新，可手动检查或开启每小时自动检查，支持下载、后台进度和系统安装器。
- 桌面长按快捷操作、控制中心代理开关和RoyTools横版4×2小组件；总设置内提供手动添加教程。

## 常用设置

- 音乐下载与缓存：进入“音乐→设置”，可下载整个曲库、管理已下载曲目、调整缓存上限或清理缓存。
- 全屏保持亮屏：位于“音乐→设置→播放设置”，默认开启。
- 后台运行与主题：进入底部“设置”，可调整主题及后台运行方式。
- 添加快捷工具：进入“设置→快捷控制”，按教程添加控制中心代理开关或RoyTools桌面小组件。
- 应用更新：进入“设置→应用更新”，手动检查或开启每小时自动检查。
- 查看与导出日志：进入“设置→关于RoyTools”，选择对应模块。

## 问题反馈

使用中遇到问题，可在[Issues](https://github.com/Roylyl/RoyTools/issues)说明应用版本、Android版本和复现步骤。需要附日志时，先检查并移除订阅地址、节点密码、令牌等私人信息。

<details>
<summary>源码与开发</summary>

需要自行构建或参与开发时，请阅读[构建文档](docs/BUILDING.md)和[贡献说明](CONTRIBUTING.md)。工程使用JDK21、Android SDK35与Gradle Wrapper，发行签名及原生核心重建步骤在构建文档中说明。

</details>

## 上游与设计参考

RoyTools整合并修改了下列项目，保留原作者、贡献者的版权与许可声明：

|项目|在RoyTools中的用途|许可证|
|---|---|---|
|Clash-Meta-Plus本地分支、[ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid)|代理Android工程、VPN、配置与设置基础|GPLv3|
|[mihomo](https://github.com/MetaCubeX/mihomo)|代理核心及修改后的当日代理用量统计|GPLv3|
|[Adaptive-Hz](https://github.com/mahmutaunal/Adaptive-Hz)|刷新率控制引擎与辅助服务|MIT|
|[Shizuku](https://github.com/RikkaApps/Shizuku)、[Shizuku-API](https://github.com/RikkaApps/Shizuku-API)|内置权限服务、无线配对及接口|Apache-2.0、MIT|
|[Roylyl Music网页](https://github.com/Roylyl/roylyl.github.io/tree/main/music)|音乐播放器、筛选、缓存和歌单交互|GPLv3代码|
|[ASRtestII](https://github.com/Roylyl/ASRtestII)|配色、页面布局与控件设计参考|Apache-2.0|

详细来源、修改范围、原始许可副本和第三方组件说明见[UPSTREAM.md](docs/UPSTREAM.md)、[THIRD_PARTY.md](THIRD_PARTY.md)与[NOTICE](NOTICE)。本项目是独立衍生应用，上游项目名称用于说明来源。

## 许可与资源

RoyTools整体以[GNU GPLv3第3版](LICENSE)分发，既有第三方代码保留各自许可。分发修改后的APK时，应同时提供与该版本匹配的完整对应源码及构建材料。

音乐播放器代码与歌曲、歌词、封面等内容分别适用各自权利条件。音乐仓库或网页代码的开源许可，不自动授予第三方媒体的再分发权；媒体来源和现有权利标记见[第三方说明](THIRD_PARTY.md#音乐网页音源仓库与媒体内容)。

应用不附带代理订阅、节点凭据或签名私钥。运行时导入的配置、下载缓存和日志保存在用户设备中；公开源码不包含维护者的手机导出、个人代理配置或本机开发记录。