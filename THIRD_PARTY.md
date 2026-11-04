# 第三方组件与数据来源

核查日期：2026-11-04。RoyTools是整合并修改上游工程得到的衍生项目，整体采用GNU GPLv3第3版，全文见[LICENSE](LICENSE)。第三方既有代码保留各自的版权与许可，不能用应用名称、致谢或根许可证替代授权。

## 主要来源

|组件或来源|用途|已核实的许可与随附文本|
|---|---|---|
|Clash-Meta-Plus本地修改分支|代理Android工程、配置、VPN、通知和设置基础|收到的分支采用GPLv3，沿用根[LICENSE](LICENSE)与原[NOTICE](NOTICE)。历史来源见[UPSTREAM.md](docs/UPSTREAM.md)。|
|[ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid)|Clash-Meta-Plus的Android上游，承接Clash for Android|[上游GPLv3许可证](https://github.com/MetaCubeX/ClashMetaForAndroid/blob/main/LICENSE)。保留原作者及贡献者声明，不冒充官方版本。|
|[mihomo](https://github.com/MetaCubeX/mihomo)|代理核心及修改后的当日代理业务用量统计|GPLv3：[本地许可证](core/src/foss/golang/clash/LICENSE)、[固定来源提交的许可证](https://github.com/MetaCubeX/mihomo/blob/ac017cdd246ce8bd547653d927e7bf77d7ee73d5/LICENSE)。|
|[Adaptive-Hz](https://github.com/mahmutaunal/Adaptive-Hz)|刷新率引擎与辅助服务|MIT，Copyright(c)2025 Mahmut Alperen Ünal。[完整许可证](vendor/Adaptive-Hz-LICENSE)、[上游许可证](https://github.com/mahmutaunal/Adaptive-Hz/blob/main/LICENSE)。|
|[Shizuku](https://github.com/RikkaApps/Shizuku)|内置服务端、starter、无线配对和所需manager代码|主体Apache-2.0：[完整许可证](vendor/Shizuku/LICENSE)、[上游许可证](https://github.com/RikkaApps/Shizuku/blob/master/LICENSE)。|
|[Shizuku-API](https://github.com/RikkaApps/Shizuku-API)|API、provider、server-shared及rish等接口|MIT，Copyright(c)2021 RikkaW。[完整许可证](vendor/Shizuku/API-LICENSE)、[上游许可证](https://github.com/RikkaApps/Shizuku-API/blob/master/LICENSE)。Gradle直接使用api/provider13.1.5。|
|[RikkaX](https://github.com/RikkaApps/RikkaX)|preference-multiprocess1.0.0与parcelablelist2.0.1|对应版本本地Maven POM及[上游根许可证](https://github.com/RikkaApps/RikkaX/blob/master/LICENSE)均声明MIT，Copyright(c)2017 Rikka。[完整副本](docs/licenses/RikkaX-MIT.txt)。|
|[Roylyl Music网页](https://github.com/Roylyl/roylyl.github.io/tree/main/music)|网页播放、缓存、筛选、曲库及歌单交互，经Android适配|网页代码采用仓库[GPLv3许可证](https://github.com/Roylyl/roylyl.github.io/blob/main/LICENSE)。歌曲、歌词、封面另见下文。|
|[ASRtestII](https://github.com/Roylyl/ASRtestII)|用户指定的配色、页面布局、圆角、控件及品牌排版参考|本地参考工程及[公开许可证](https://github.com/Roylyl/ASRtestII/blob/main/LICENSE)均为Apache-2.0。[完整副本](docs/licenses/ASRtestII-Apache-2.0.txt)。参考不表示引入其ASR模型、音频或全部依赖。|

根[NOTICE](NOTICE)原有Clash、AOSP、AndroidX声明原样保留，并补充RoyTools及合并模块的来源。各文件原有的许可头和版权不能被统一的RoyTools版权声明覆盖。

## BoringSSL与无线配对原生库

`vendor/Shizuku/manager/src/main/jni/CMakeLists.txt`声明链接`boringssl::crypto_static`，`adb_pairing.cpp`使用其加密接口。工程随附`refresh/src/main/jniLibs/arm64-v8a/libadb.so`，但收到的文件与构建资料没有给出足以确认该二进制所用BoringSSL精确修订、完整构建输入和相应通知的记录。

[Shizuku公开构建文件](https://github.com/RikkaApps/Shizuku/blob/master/manager/build.gradle)引用`io.github.vvb2060.ndk:boringssl:20250114`，这是定位线索，不能据此认定现有二进制由该版本构建。[BoringSSL当前主线LICENSE](https://github.com/google/boringssl/blob/master/LICENSE)包含Apache-2.0及支持代码说明，也不能替代历史二进制实际版本的条款。

本次保留Shizuku完整许可证、原生源码和上述来源事实，没有编造BoringSSL版本或宣布其历史核查已完成。发行时仍需补齐实际依赖版本与完整许可证/NOTICE，或以固定、可追溯且许可明确的输入重新构建。增加致谢、根GPL文件或当前主线许可证不能补足这项来源缺口。

## 其他运行时与构建依赖

直接版本见[Gradle版本目录](gradle/libs.versions.toml)及各模块`build.gradle.kts`；Go模块、替换规则与依赖见：

- [核心go.mod](core/src/foss/golang/clash/go.mod)及同目录`go.sum`。
- [Android桥go.mod](core/src/main/golang/go.mod)及同目录`go.sum`。
- [foss整合模块go.mod](core/src/foss/golang/go.mod)及同目录`go.sum`。

Kotlin、kotlinx、AndroidX、Material Components、WorkManager、KAIDL、Quickie、Bouncy Castle、HiddenApiBypass及Go运行时、sing系列、WireGuard、gVisor等组件均有独立条款。当前直接声明包括Material1.12.0、WorkManager2.9.1、Bouncy Castle1.80；直接声明和实际解析版本可能不同，本表不是全部传递依赖的完整许可清单。

保留[kcptun的MIT许可证](core/src/foss/golang/clash/transport/kcptun/LICENSE.md)、[faketcp的既有来源说明](core/src/foss/golang/clash/transport/hysteria/conns/faketcp/LICENSE)及各文件版权头。faketcp的一行来源链接不能替代完整许可证。新增或升级依赖时应保留适用的完整许可、版权和NOTICE；构建成功、依赖清单与模块校验记录不等于完成许可审计。

## Geo数据库与规则

[构建任务](app/build.gradle.kts)从[MetaCubeX/meta-rules-dat](https://github.com/MetaCubeX/meta-rules-dat)获取`geoip.metadb`、`geosite.dat`、`ASN.mmdb`及`BundleMRS.7z`。这些是第三方数据，不是RoyTools原创，也不因应用代码采用GPLv3而自动取得同样的授权。

meta-rules-dat的[许可证](https://github.com/MetaCubeX/meta-rules-dat/blob/master/LICENSE)与其[来源和生成说明](https://github.com/MetaCubeX/meta-rules-dat)应结合阅读。下载地址包含浮动`latest`，发行时应记录实际来源、取得日期及生成版本，不能拿应用版本代替数据版本，也不能把聚合仓库的许可证概括为所有外部数据的统一条款。

`ASN.mmdb`涉及MaxMind创建的GeoLite数据。应按实际取得渠道和版本确认[GeoLite EULA](https://www.maxmind.com/en/geolite/eula)及归属、更新和再分发条件；本文件不宣称只附GPL文本便已满足这些条件。源码忽略下载文件不会消除实际APK携带数据库时的义务。

## 音乐网页、音源仓库与媒体内容

按维护者的打包要求，音乐模块、曲库索引和歌单随工程保留；音频、歌词与专辑封面从云端按需加载，不内置于安装包。音乐网页代码来自[Roylyl/roylyl.github.io/music](https://github.com/Roylyl/roylyl.github.io/tree/main/music)，音源与曲库关联资源位于另一个仓库[Roylyl/Music](https://github.com/Roylyl/Music)，曲库目录及原始封面统一从`https://raw.githubusercontent.com/Roylyl/Music/main/`加载。这几个来源需要分别理解；源码仓库公开不表示其中所有媒体已经取得开放内容许可。

网页[许可说明](https://github.com/Roylyl/roylyl.github.io/tree/main/music#许可与资源)明确：代码遵循GPLv3，歌曲、歌词和专辑封面具有各自权利，不因代码许可取得再分发授权。原资源字段中的`redistributionAllowed=false`不能被根GPL文件、开源声明或打包要求替代为允许再分发。

本项目没有据此宣称取得歌曲录音、词曲、歌词、封面、艺人照片或音乐服务内容的商用、公开传播、下载分发及其他额外授权。资源能联网访问或被缓存也不是许可证明。发布带媒体的安装包、曲库或导出文件时，仍应确认相应权利；致谢与“仅供学习”不能替代授权。

## 分发与名称

提供RoyTools修改版APK时，应同时提供匹配该产物的完整对应源码及必要构建材料，保留[LICENSE](LICENSE)、[NOTICE](NOTICE)、本说明、第三方许可证及修改声明。只给出未包含RoyTools修改的上游链接不等同于提供对应源码。

本项目不增加“禁止商业使用”“仅限个人使用”“不得修改”等与GPL授权冲突的限制。项目名称、上游链接与设计参考不代表上游、Google、Apple或任何音乐服务的官方背书，也不自动授予商标或品牌素材使用权。本文记录已核实事实和未解决范围，不保证不存在任何版权风险。
