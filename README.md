<p align="center">
  <img src="RoyTools-icon.png" width="120" alt="RoyTools应用图标">
</p>

<h1 align="center">RoyTools</h1>

<p align="center">音乐、代理、通知转发与Chat、自适应刷新率，一个应用集中管理。</p>

<p align="center">
  <a href="https://github.com/Roylyl/RoyTools/releases/latest"><img src="https://img.shields.io/github/v/release/Roylyl/RoyTools?style=flat-square&amp;display_name=tag&amp;label=%E7%89%88%E6%9C%AC&amp;color=007AFF" alt="最新发行版本"></a>
  <a href="#快速开始"><img src="https://img.shields.io/badge/Android-12%2B-007AFF?style=flat-square" alt="Android12及以上"></a>
  <a href="#快速开始"><img src="https://img.shields.io/badge/ABI-arm64--v8a-555555?style=flat-square" alt="arm64-v8a架构"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/Roylyl/RoyTools?style=flat-square&amp;label=%E8%AE%B8%E5%8F%AF&amp;color=555555" alt="开源许可证"></a>
  <a href="https://github.com/Roylyl/RoyTools/releases"><img src="https://img.shields.io/github/downloads/Roylyl/RoyTools/total?style=flat-square&amp;label=%E7%B4%AF%E8%AE%A1%E4%B8%8B%E8%BD%BD&amp;color=007AFF" alt="发行附件累计下载量"></a>
</p>

<p align="center">
  <a href="https://github.com/Roylyl/RoyTools/stargazers"><img src="https://img.shields.io/github/stars/Roylyl/RoyTools?style=flat-square&amp;label=Stars&amp;color=007AFF" alt="GitHubStars数量"></a>
  <a href="https://github.com/Roylyl/RoyTools/forks"><img src="https://img.shields.io/github/forks/Roylyl/RoyTools?style=flat-square&amp;label=Forks&amp;color=555555" alt="GitHubForks数量"></a>
  <a href="https://github.com/Roylyl/RoyTools/issues"><img src="https://img.shields.io/github/issues/Roylyl/RoyTools?style=flat-square&amp;label=Issues&amp;color=555555" alt="待处理Issues数量"></a>
  <a href="https://github.com/Roylyl/RoyTools/graphs/contributors"><img src="https://img.shields.io/github/contributors/Roylyl/RoyTools?style=flat-square&amp;label=%E8%B4%A1%E7%8C%AE%E8%80%85&amp;color=555555" alt="贡献者数量"></a>
  <a href="https://github.com/Roylyl/RoyTools/commits/main"><img src="https://img.shields.io/github/last-commit/Roylyl/RoyTools?style=flat-square&amp;label=%E6%9C%80%E8%BF%91%E6%9B%B4%E6%96%B0&amp;color=007AFF" alt="最近提交时间"></a>
</p>

<p align="center">
  <a href="https://github.com/Roylyl/RoyTools/releases/latest">下载APK</a> · <a href="#快速开始">快速开始</a> · <a href="#音乐">音乐</a> · <a href="#代理">代理</a> · <a href="#通知转发">通知转发</a> · <a href="#刷新率">刷新率</a> · <a href="CHANGELOG.md">更新日志</a> · <a href="#项目来源与许可">项目来源与许可</a>
</p>

## 快速开始

从[GitHub发行页](https://github.com/Roylyl/RoyTools/releases/latest)下载APK，安装到Android12及以上的arm64-v8a手机上。首次打开即可联网浏览曲库并听歌，无需填写代理配置。代理需使用自己的订阅或配置文件，通知转发和刷新率可按需开启。

|想先做什么|第一次怎么用|
|---|---|
|听一首歌|打开“音乐→全部歌曲”，点歌名开始播放。也可以从专辑或歌单选歌。|
|开启代理|打开“代理→配置”，点右上角“＋”。选择“URL”填写自己的订阅地址，或选择“文件”导入配置。保存并选中配置，返回代理首页打开卡片右上方的总开关，首次使用时允许系统VPN请求。|
|转发通知到iPhone|打开“通知转发→转发设置”，填写Telegram机器人Token、获取Chat ID并授予通知使用权，测试成功后开启转发。|
|自动调节刷新率|打开“刷新率”页右上方的总开关，点“配置辅助服务”，按[手机配对步骤](#手机配对)激活。首次开启默认使用自适应模式。|

## 音乐

从全部歌曲、专辑或歌单选曲，支持搜索、歌手筛选和多项排序。新增歌单会跟随云端曲库自动显示，无需等待应用更新。

### 播放与队列

- 迷你播放器固定悬浮在底部，进入和返回音乐子页面时保持原位。左右滑动可切换歌曲，点击封面或歌名进入全屏播放。
- 全屏页提供封面、滚动歌词、播放模式、播放队列和定时关闭。拖动进度条时预览目标时间，松手后跳转播放位置；全屏保持亮屏可在音乐设置中关闭。
- 连续点击“下一首播放”按点击顺序插播。例如播放A时，依次插播G、H，待播顺序为A→G→H→B。队列的更多菜单支持“删除待播”，只移除本次排队，不删除歌曲文件。
- 当前浏览的歌单包含正在播放的歌曲时，右下角提供“定位当前曲目”和“回到顶部”。定位时若曲目被筛选隐藏，会清除筛选并跳转。

### 下载与缓存

曲目更多菜单支持下载当前歌曲，专辑和歌单支持“下载全部”。“音乐→设置→音乐下载”可下载整个曲库，也可进入“已下载曲目”搜索、筛选和删除单曲。下载窗口显示速度、进度和百分比，支持取消及后台下载；后台进度显示在通知栏。

- 边听边存：开始播放后持续加载并保存整首歌曲。开启“预加载下一首”时，等当前曲目完整保存后再加载下一首。
- 保留已有内容：应用不会按使用时间或数量自动淘汰已保存的歌曲、原始封面和有效歌词。音频达到设置的缓存上限后停止新增，需要释放空间时手动清理。
- 复用专辑封面：同一资源优先读取本地文件，列表、播放器、播放通知和桌面小组件共享原始封面缓存；云端封面版本变化后再获取新图。
- 批量保存资源：音乐设置支持“下载所有歌词”和“下载所有封面”，也可分别清理音频、歌词和封面，或删除全部下载与缓存。

### 曲库同步

每次进入音乐模块都会检查曲库版本，也可在“音乐→设置”底部手动检查。歌曲、专辑和歌单与网页共用[Roylyl/Music](https://github.com/Roylyl/Music)的云端目录，曲库更新无需重新安装应用。断网时使用上次同步的目录，并可播放已完整保存的歌曲。

APK只附带首次浏览所需的曲库索引，音频、歌词和专辑封面均从云端加载。

## 代理

代理首页可查看连接状态及实时上传、下载速度。第一个卡片右上方的总开关用于启动或停止代理，模式滑块提供六种选择：

|模式|适合怎样使用|
|---|---|
|默认|使用当前服务商配置原本的模式和分流规则。|
|规则|使用内置Roylyl2.5.1分流规则及微信IP、大陆域名规则集，节点来自当前订阅。|
|音乐|仅RoyTools访问音乐曲库所需的roylyl.github.io和raw.githubusercontent.com时走代理，其他应用和其他流量直连。|
|AI|使用AIforHK规则中的AI服务域名与IP分流，其余直连。|
|全局|全部流量通过所选代理线路。|
|直连|全部流量直接连接。|

应用会记住上次选择的模式，停止代理或重启应用后再次启动时恢复。规则、音乐、AI只改变分流规则，沿用用户选定的节点或代理组；切回默认恢复服务商原规则，不修改订阅文件。

音乐模式同时限定RoyTools应用和音乐资源域名，不代理其他应用的GitHub访问；AI模式按AIforHK的域名及IP规则分流。这两种模式不代理Telegram。如果当前网络无法直连Telegram，请切换到规则或全局模式，并确认线路可用。

进入“代理节点”选择节点或测速。应用访问控制、网络、DNS、配置覆写和外部资源都在代理设置中。应用内置分流规则，订阅和节点由使用者自行提供。

## 通知转发

把Android的新通知转发到Telegram，在iPhone上接收；也可以进入Chat双向聊天。Android端直接使用Telegram Bot API，无需登录Telegram账号或自建服务器。手机需要能访问api.telegram.org。

1. 在iPhone的Telegram中打开@BotFather，发送`/newbot`创建独立机器人，复制Bot Token。
2. 点击BotFather创建成功回复里的`t.me/机器人用户名`链接，进入新机器人聊天，点击“开始”并发送一句话。
3. 打开“通知转发→转发设置”，填入Token，点击“自动获取Chat ID”并确认接收会话。
4. 配置通知使用权，发送测试消息，确认iPhone收到后开启实时转发。

转发会过滤重复内容，并跳过常驻通知、分组摘要、RoyTools和Telegram通知。断网任务联网后重试，超过24小时的待发通知不再发送。iPhone是否弹出横幅由Telegram会话及iOS通知设置决定。

### Chat聊天

- 左灰右蓝的聊天气泡，新消息动态追加；发送后回到最新消息，空输入时发送键禁用。
- 输入框左侧“＋”打开附件小浮窗。相册支持勾选图片和视频，文件通过系统文件选择器选择。图片最大10MB，视频和文件最大50MB。
- 图片按原始比例适配预览，加载时显示动画，失败可重试。点开图片支持放大，视频可打开播放。
- 文件只在点击右侧下载按钮后下载。窗口显示速度、进度和百分比，可取消或后台下载；后台进度显示在通知栏。
- 系统分享菜单选择“RoyTools Chat”，文本进入输入框，附件确认后发送到已配置的会话。
- 收到新消息会独立通知，点击进入Chat。记录保存在本机，Telegram Bot API无法补取完整历史；后台接收取决于Android是否允许应用继续运行，可在总设置调整后台保活。

“转发设置→数据管理”显示媒体占用空间和记录条数，支持分别清除媒体或记录，清除后更新统计。Token和Chat ID保存在应用私有配置中，并从系统备份排除。数据发送与保存说明见[隐私说明](PRIVACY_POLICY.md)。

## 刷新率

第一个卡片右上方的总开关默认关闭。关闭时恢复系统控制，下方选项置灰，不搜索无线调试，也不显示激活提醒。首次打开默认自适应，之后恢复上次选择的档位。

模式滑块从左到右为“自适应、60Hz、120Hz”。自适应模式在触控时升频，静置后降频；静置延迟默认0.5秒，每档相差0.25秒。实际可用的刷新率取决于手机屏幕和系统。

### 手机配对

1. 手机连上Wi-Fi并开启开发者选项。先打开RoyTools“刷新率”页的总开关，再点“配置辅助服务→打开无线调试”。
2. 在系统设置中开启“无线调试”，点“使用配对码配对设备”。保持系统配对窗口打开，直接在RoyTools的横幅通知里输入当前显示的6位配对码。
3. 配对成功后，应用会尝试自动连接。如果通知要求输入连接端口，返回系统“无线调试”首页，填写“IP地址和端口”显示的端口。回到RoyTools，看到“辅助服务运行中”即可选择模式。

配对可以全程在手机上完成。手机重启后若提示“待激活”，可从同一入口重新激活。

## 快捷控制与设置

底部导航按音乐、代理、通知转发、刷新率、设置排列。在模块二级页点击对应底部按钮，会返回该模块首页；已在首页时回到顶部。

|入口|可以做什么|
|---|---|
|桌面4×2小组件|查看当前节点及当天经过代理的Wi-Fi和移动数据用量，切换代理模式和刷新率，查看曲目并控制播放。|
|控制中心与桌面长按菜单|切换代理或自适应刷新率。在“设置→快捷控制”可查看控制中心按钮和桌面小组件的添加方法。|
|通知栏|刷新率切换后短暂提示。代理开启时用速度通知承担运行提示，关闭后按后台保活设置接替。下载进度归入运行通知组，Chat新消息单独提醒，无线调试使用独立高优先级通知。|
|设置→主题、后台运行设置|选择跟随系统、浅色或深色主题，调整后台保活、桌面图标和最近任务的显示方式。|
|设置→应用更新|手动检查新版本，或开启每小时检查。下载时可查看速度、取消或转入后台，完成后按系统提示安装。|
|设置→关于RoyTools|按音乐、代理、刷新率和应用查看或导出日志，也可以清空全部日志。|

如需反馈问题，请附上RoyTools版本、Android版本和复现步骤。分享日志前请隐去订阅链接、节点凭据等私人信息。[提交问题](https://github.com/Roylyl/RoyTools/issues)

## 项目来源与许可

|来源|用途|
|---|---|
|Clash-Meta-Plus、[ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid)、[mihomo](https://github.com/MetaCubeX/mihomo)|Android代理界面、VPN服务、配置管理与代理核心。|
|[Adaptive-Hz](https://github.com/mahmutaunal/Adaptive-Hz)、[Shizuku](https://github.com/RikkaApps/Shizuku)、[Shizuku-API](https://github.com/RikkaApps/Shizuku-API)|自适应刷新率、内置权限服务与无线配对。|
|[Roylyl Music网页](https://github.com/Roylyl/roylyl.github.io/tree/main/music)、[Roylyl/Music](https://github.com/Roylyl/Music)|播放器与曲库交互的来源，以及网页和应用共用的音乐目录。|
|[sms-telebot](https://github.com/teslor/sms-telebot)、[NotificationForwarder](https://github.com/ItsAzni/NotificationForwarder)|通知转发配置与监听流程参考；模块基于Android通知API和Telegram Bot API实现，未复制其源码。|
|[ASRtestII](https://github.com/Roylyl/ASRtestII)|配色、页面布局和控件设计参考。|

RoyTools整体以[GNU GPLv3](LICENSE)分发。各上游组件保留原作者的版权和许可。歌曲、歌词与专辑封面的使用和分发遵循各自的权利条件。详见[上游来源](docs/UPSTREAM.md)、[第三方许可与媒体说明](THIRD_PARTY.md)和[版权声明](NOTICE)。

开发与贡献请参阅[构建文档](docs/BUILDING.md)、[贡献指南](CONTRIBUTING.md)和[修改记录](CHANGELOG.md)。
