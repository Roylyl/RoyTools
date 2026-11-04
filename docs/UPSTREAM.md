# 上游来源与修改记录

更新日期：2026-11-04。本文记录RoyTools1.1.0的来源、改动与证据边界。项目计划以[Roylyl/RoyTools](https://github.com/Roylyl/RoyTools)发布；本地整理不表示已经推送或发行。根[LICENSE](../LICENSE)保留GNU GPLv3第3版原文，衍生组合项目整体按该版提供，第三方既有条款继续保留。

## Android代理工程

RoyTools整合的是收到的Clash-Meta-Plus本地修改工程，继续保留其GPLv3许可证、原NOTICE、代理源码及版权声明。本次没有可继承的完整Git历史，不把新建仓库的历史冒充上游历史。

继承的2026-09-13记录说明：该工程最初基于[MetaCubeX/ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid)的源码归档，修改前版本为2.11.33，归档不含`.git`。旧记录将归档时间与提交[`c454d0e73b047d9f4c0173e8d35088f08b65bc98`](https://github.com/MetaCubeX/ClashMetaForAndroid/commit/c454d0e73b047d9f4c0173e8d35088f08b65bc98)关联，其父提交[`da8db40cfbc1f882a379d850c0b106c1a0dc09e1`](https://github.com/MetaCubeX/ClashMetaForAndroid/tree/da8db40cfbc1f882a379d850c0b106c1a0dc09e1)对应v2.11.33。

这些是继承的归档恢复线索，不是本次证明整个源树等同于某次Git提交的声明。本次访问`Roylyl/Clash-Meta-Plus`公开地址返回404，不将其作为当前可公开取得的来源保证。ClashMetaForAndroid承接Clash for Android，原作者及贡献者保留各自版权。

## 代理核心

历史记录将恢复的核心定位为[MetaCubeX/mihomo](https://github.com/MetaCubeX/mihomo)提交[`ac017cdd246ce8bd547653d927e7bf77d7ee73d5`](https://github.com/MetaCubeX/mihomo/tree/ac017cdd246ce8bd547653d927e7bf77d7ee73d5)，源码位于[`core/src/foss/golang/clash`](../core/src/foss/golang/clash)。其[GPLv3许可证](../core/src/foss/golang/clash/LICENSE)与内部第三方声明保留。

RoyTools在该副本增加按本地自然日和网络类型统计代理业务用量的代码、Android JNI桥及持久化修改，不能描述为未经修改的上游核心。构建入口为[`scripts/build-native.ps1`](../scripts/build-native.ps1)，标签、模块替换及依赖以随附Go清单为准。Go工具链来源为[MetaCubeX/go](https://github.com/MetaCubeX/go)，Android运行时补丁保留在[补丁目录](../.github/patch)。对应源码应包含构建材料，不能只发布预编译`.so`。

## 刷新率与内置Shizuku

[Adaptive-Hz](https://github.com/mahmutaunal/Adaptive-Hz)提供刷新率引擎基础，采用MIT，[完整许可证](../vendor/Adaptive-Hz-LICENSE)保留。RoyTools增加汉化、统一页面、四档模式、0.25秒步进、无线辅助配置及通知和快捷入口。

[Shizuku](https://github.com/RikkaApps/Shizuku)的server、starter、common和所需manager源码位于[`vendor/Shizuku`](../vendor/Shizuku)，主体采用[Apache-2.0](../vendor/Shizuku/LICENSE)。API、server-shared及rish等来自[Shizuku-API](https://github.com/RikkaApps/Shizuku-API)，采用[MIT](../vendor/Shizuku/API-LICENSE)。Maven API/provider直接版本为13.1.5；vendor源码缺少独立Git元数据，不能仅凭该版本推断全部文件来自同一精确修订。

RoyTools做了应用身份、包名、服务接入及配对配置修改，用于本应用内部功能。本项目不是Shizuku官方发行版本，原权利人保留版权。`libadb.so`与BoringSSL的历史版本溯源缺口见[第三方说明](../THIRD_PARTY.md#boringssl与无线配对原生库)。

## 音乐网页与音源

音乐模块代码来自[Roylyl/roylyl.github.io的music目录](https://github.com/Roylyl/roylyl.github.io/tree/main/music)，仓库[许可证](https://github.com/Roylyl/roylyl.github.io/blob/main/LICENSE)为GPLv3。本地网站仓库最近提交记录为`455005d`，RoyTools副本在接入后继续修改；该记录不表示音乐副本逐文件等同于这一提交。

RoyTools保留网页音频和缓存引擎，增加Android桥、媒体通知、统一设置及批量离线下载交互，并修改页面、手势、主题和导航。音源与关联曲库资源来自另一个仓库[Roylyl/Music](https://github.com/Roylyl/Music)。按维护者要求打包音乐模块、曲库索引与歌单，音频、歌词和专辑封面从云端按需加载，不内置媒体文件。曲库目录及原始封面统一从`https://raw.githubusercontent.com/Roylyl/Music/main/`加载。这不表示媒体取得GPL许可或已获得额外商用、再分发授权；原`redistributionAllowed=false`字段和权利边界见[THIRD_PARTY.md](../THIRD_PARTY.md#音乐网页音源仓库与媒体内容)。

## 设计参考与图形

用户指定的本地ASRtestII-V4参考工程用于配色、页面布局、圆角、控件及品牌排版参考。其根许可证及[公开ASRtestII许可证](https://github.com/Roylyl/ASRtestII/blob/main/LICENSE)为Apache-2.0，[完整副本](licenses/ASRtestII-Apache-2.0.txt)随文档保留。参考不表示将其识别模型、音频或第三方库自动纳入RoyTools的可再分发内容。

RoyTools图标、启动页两行文字和控件由本工程资源实现；代理猫图标、部分基础界面与通知图形包含继承的上游素材。代码许可证不自动授予上游品牌、Apple、Google、Shadowrocket、QQ音乐、Apple Music等名称、标识、字体或宣传素材的使用权。相关名称用于说明来源与设计参考，不代表官方隶属、赞助或认可。

## RoyTools显著修改

2026年11月整合期间，本工程更名为RoyTools，包名改为`com.roylyl.tools`，版本为1.0.0。显著修改包括：

- 音乐、代理、刷新率与总设置的统一底部导航、页面结构及回退。
- 汉化、浅深色主题、圆角按钮、原生开关、弹窗和系统短提示。
- 无线调试配对、辅助服务配置及统一后台运行设置。
- 发行页更新、应用内下载窗口、分模块日志及导出。
- 当日代理业务用量、控制中心按钮、桌面快捷方式及RoyTools小组件。
- 保留原播放引擎的Android媒体控制、播放手势和离线下载入口。

以上为衍生分支修改声明，不表示重新创作全部代理核心、刷新率引擎或Shizuku服务。原NOTICE不会被新的RoyTools声明覆盖，分发要求见[THIRD_PARTY.md](../THIRD_PARTY.md)。

## 历史文档

[`docs/upstream`](upstream)保留收到的旧贡献约定与隐私文本，仅用于来源追溯，不替代现行文档，也不应被解释为本分支要求贡献者授权闭源分发。当前条款见[CONTRIBUTING.md](../CONTRIBUTING.md)与[PRIVACY_POLICY.md](../PRIVACY_POLICY.md)。
