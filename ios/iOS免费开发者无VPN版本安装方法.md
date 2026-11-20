# iOS免费开发者无VPN版本安装方法

这份指引供Agent在Mac上为已连接的iPhone构建并安装RoyTools无VPN测试版。仓库中的iOS工程始终保留完整版。签名、裁剪、改包名和构建都在仓库外的临时副本中完成。无VPN版保留音乐、设置、应用及音乐日志和后台播放。

## 准备

- 在Xcode的Settings→Accounts登录用户自己的Apple账户，并确认能看到对应的Personal Team。
- 将iPhone连到Mac。解锁手机、信任这台Mac并按系统提示开启开发者模式。通过`xcrun devicectl list devices`确认目标机显示为可用的实体设备。设备有重名时，按型号和UDID区分。
- 如果手机已有无VPN版，沿用其包名及签名团队覆盖安装。RoyTools现有无VPN版使用`com.roylyl.tools.novpn`。首次安装遇到包名不可用时，选一个属于用户团队的唯一包名，并在以后安装时沿用。应用和构建版本沿用当前源码的`ios/Config/Version.xcconfig`。

## 创建临时工程

在仓库根目录执行以下命令。Xcode项目会从同一临时根目录读取规则、曲库和许可文件。

```bash
stage_dir=$(mktemp -d "${TMPDIR:-/tmp}/roytools-novpn.XXXXXX")
rsync -a --exclude '/Engine/' --exclude 'xcuserdata/' ios/ "$stage_dir/ios/"
cp LICENSE NOTICE routing-rules.json routing-version.json "$stage_dir/"
catalog_dir=android/app/src/main/assets/music/data
mkdir -p "$stage_dir/$catalog_dir"
cp "$catalog_dir/catalog.json" "$stage_dir/$catalog_dir/"
printf '%s\n' "$stage_dir"
```

在临时副本完成以下调整。

1. 打开`ios/RoyTools.xcodeproj`，在主目标`RoyTools`的Build Phases中移除Packet Tunnel和WidgetKit扩展的Target Dependencies及Embed App Extensions条目。确认`RoyTools`方案只构建主应用。无VPN测试版无需构建Mihomo核心或下载GeoData。
2. 在主目标的Signing & Capabilities选择用户的Personal Team并启用Automatically manage signing。移除Network Extensions和App Groups，清空主目标Debug配置中的`Code Signing Entitlements`。保留Background Modes中的Audio。将主目标的Bundle Identifier设为上述无VPN包名。
3. 在`ios/Config/App-Info.plist`将显示名改为「RoyTools无VPN」。如果已从入口移除小组件路由，也可删除用于小组件导航的`CFBundleURLTypes`。
4. 在`ios/RoyTools/RoyToolsApp.swift`只保留「音乐」和「设置」标签，移除小组件URL路由、VPN恢复及其前后台和小组件状态监听。保留音乐播放、应用日志和原有启动界面。部分视图的初始化参数仍依赖VPN控制器时，可保留该对象并跳过VPN启动流程。
5. 在`Features/Settings/SettingsView.swift`及`Features/Settings/AboutRoyToolsView.swift`去掉VPN、代理核心、规则版本和代理日志入口，保留应用及音乐日志。将`SharedTunnel/RoyToolsDiagnosticLog.swift`的持久化目录指向主应用私有的Application Support。在`Features/Music/MusicWidgetPlayback.swift`跳过小组件状态发布，保留进程内同步、系统播放控件与后台音频。

## 构建与覆盖安装

从这里开始，`stage_dir`始终指向仓库外的临时工程。用设备列表查到的实体iPhone UDID替换`DEVICE_UDID`，`app_id`应与签名配置里的包名一致。从终端生成配置文件需要已在Xcode登录的账户和有效签名。

```bash
xcodebuild -project "$stage_dir/ios/RoyTools.xcodeproj" -scheme RoyTools \
  -configuration Debug -destination 'generic/platform=iOS' \
  -derivedDataPath "$stage_dir/DerivedData" -allowProvisioningUpdates build

app="$stage_dir/DerivedData/Build/Products/Debug-iphoneos/RoyTools.app"
device_udid=DEVICE_UDID
app_id=com.roylyl.tools.novpn
xcrun devicectl device install app --device "$device_udid" "$app"
xcrun devicectl device process launch --device "$device_udid" "$app_id"
```

也可以在Xcode选中实体iPhone后直接Run。构建后先检查主包的标识和版本，以及`PlugIns`目录中没有扩展。覆盖安装沿用相同包名和团队，保留手机上的应用数据。如果系统提示需要信任开发者，按iPhone设置里的系统提示操作。手机锁定时先解锁，再尝试启动。

首次打开检查底部只显示「音乐」和「设置」。打开任一曲目，确认锁屏或控制中心可以使用系统播放控件。免费签名过期后，用同一团队重新签名和覆盖安装即可。
