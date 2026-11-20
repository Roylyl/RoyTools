# RoyTools macOS

Mac版复用iPad的音乐、设置和代理界面。代理页提供订阅和本地YAML管理、六档代理模式、节点与代理组、测速、规则更新和局域网共享。RoyTools通过内置Mihomo转发遵循macOS系统代理设置的应用流量。

## 使用本机代理

1. 在「代理→配置」导入HTTPS订阅或本地Clash/Mihomo YAML，选择要使用的配置。
2. 回到代理首页打开「系统代理」。如果macOS要求授权，在系统提示里完成确认。
3. 点「系统代理」关闭。RoyTools恢复它改过的设置。如果上次恢复中断，代理页会提供「恢复系统代理」。

支持独立代理设置的应用可手动使用`127.0.0.1:17897`的HTTP、HTTPS或SOCKS5代理。给同一网络内的其他设备使用时，在「局域网共享」开启共享，设置固定端口，并复制对应的HTTP或SOCKS5地址。[Apple的Mac代理设置指引](https://support.apple.com/guide/mac-help/change-proxy-settings-on-mac-mchlp2591/mac)

## 窗口和菜单栏

点窗口左上角红色按钮，RoyTools收起窗口并继续运行。点击Dock中的RoyTools图标可重新显示窗口。菜单栏提供启动或关闭代理、播放或暂停、打开收藏歌单、显示RoyTools和退出RoyTools。通过菜单退出时，RoyTools会先恢复它设置的系统代理。

## 构建

使用Apple Silicon Mac、Xcode26或更新版本、Go1.26.10和Python3。从仓库根目录执行：

```bash
bash macos/build-core.sh
bash macos/build-app.sh --helper macos/build/RoyToolsMacCore --dmg
```

Mac Catalyst应用和`RoyTools-版本号-macOS-arm64.dmg`输出到`macos/build/`。运行需要搭载Apple芯片且使用macOS14或更新版本的Mac。构建从iOS源码生成独立的临时Xcode项目，复用仓库中的Mihomo及规则逻辑。如果`ios/Engine/GeoData/`已准备好，构建会一并打包GeoData。

构建脚本使用本机临时签名。公开发行时，应用及嵌入组件需使用Developer ID Application签名，重新打包后将DMG交给Apple公证，并检查Gatekeeper评估结果。[Apple公证指南](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution)

## 实现参考

Clash Verge Rev将Mihomo本地代理和TUN开关分开。其macOS客户端把系统代理指向本机Mihomo端口，或使用本机提供的PAC。RoyTools使用macOS的系统配置和本机授权服务设置代理，将修改范围限定在当前主要网络服务。关闭时恢复仍属于当前会话的设置。[Clash Verge Rev系统代理实现](https://github.com/clash-verge-rev/clash-verge-rev/blob/d3d91f38552abcadd202dd6e711b4d1e76df6124/src-tauri/src/core/sysopt.rs)
