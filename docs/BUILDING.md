# 构建RoyTools

工程当前仅生成arm64-v8a安装包，运行环境为Android12及以上。构建需要JDK21和Android SDK35；Gradle会下载项目依赖。普通APK构建直接使用仓库中附带的`libclash.so`与`libbridge.so`，无需重新编译Go核心。

## Windows本机构建

安装JDK21与Android SDK35，将`JAVA_HOME`指向JDK目录、`ANDROID_HOME`指向SDK目录。也可向构建脚本传入`-JavaHome`和`-AndroidSdk`。脚本可在Android Studio和Gradle常见安装目录中查找JDK21，不依赖项目原作者的用户名或桌面路径。

```powershell
.\构建.ps1 -Version 2.0.0 -BuildType Debug
```

输出为工程目录下的`RoyTools-2.0.0.apk`。传入`-OutputDirectory`可改变导出目录。构建脚本不会自动安装或操作手机。

`app:downloadGeoFiles`在首次构建时下载Geo数据库至`app/build/generated/geoAssets`；后续构建复用已有文件。`clean`只清理生成文件，不会删除`app/src/main/assets/music`中的音乐页面和资源。

离线构建或GitHub下载不稳定时，可将`geoip.metadb`、`geosite.dat`、`ASN.mmdb`、`BundleMRS.7z`放在仓库外的本机目录，设置`ROYTOOLS_GEO_DIR`后构建。也可给Gradle传入`-ProytoolsGeoDir=目录`，该参数优先于环境变量。配置后四个文件都必须存在且非空，缺失时明确报错，不会悄悄重试网络。

```powershell
$env:ROYTOOLS_GEO_DIR = 'D:\BuildInputs\RoyToolsGeo'
.\构建.ps1 -Version 2.0.0 -BuildType Release
```

这个目录是只读构建输入，Gradle会将文件复制到生成目录。迁移工程后，不要只把缓存放进`build/generated`：Gradle可能在首次执行任务前清除旧输出。不要将`ROYTOOLS_GEO_DIR`设为当前工程的`app/build/generated/geoAssets`；仓库外输入不会被本工程`clean`删除。未配置本地输入时仍使用默认网络下载。

## Release签名

发行安装包必须使用持续保管的同一把签名密钥。更换密钥后，Android通常不能直接覆盖安装旧包。保留现有安装的升级能力时，应显式指定与现有版本一致的密钥，不要重新生成后覆盖。

密钥放在仓库外，通过下列环境变量配置，本地值不要提交或写入公开CI日志：

|环境变量|含义|
|---|---|
|`ROYTOOLS_KEYSTORE_PATH`|密钥文件的绝对路径|
|`ROYTOOLS_KEYSTORE_PASSWORD`|密钥库密码|
|`ROYTOOLS_KEY_ALIAS`|签名alias|
|`ROYTOOLS_KEY_PASSWORD`|alias密码|

也可复制根目录`signing.properties.example`为本机`signing.properties`，填写`keystore.file`、`keystore.password`、`key.alias`、`key.password`。环境变量优先于同名配置文件；`keystore.file`支持仓库外的绝对路径。配置文件和密钥均不得提交。

```powershell
.\构建.ps1 -Version 2.0.0 -BuildType Release
```

脚本要求配置签名才导出Release。直接运行`gradlew :app:assembleMetaRelease`且未配置签名时会生成unsigned包，不会悄悄回退为Debug签名。Debug构建使用本机Android调试密钥；CI手动验证构建也仅使用临时Debug签名，不能用于替换另一个签名的现有安装。

## 手动重建代理核心

修改后的Go核心、JNI桥和当日代理流量计量源码均包含在仓库中。Windows重建需要Android NDK29.0.14206865及MetaCubeX Go1.26工具链，本工程原生库曾使用Go1.26.10。Go与NDK必须匹配源码依赖要求，普通APK构建无需安装它们。

在专用Go工具链的`src/os/pidfd_linux.go`中，确认`pidfdWorks`开头有以下Android兼容分支。仓库保留了上游补丁`.github/patch/disable_pidfd_on_android.patch`；不同工具链行号可能变化，不要重复应用已经存在的分支。

```go
if runtime.GOOS == "android" {
    return false
}
```

```powershell
.\scripts\build-native.ps1 -GoRoot 'D:\Toolchains\go' -AndroidSdk $env:ANDROID_HOME -Api 24 -Version 2.0.0 -BuildDate 20261104
.\构建.ps1 -Version 2.0.0 -BuildType Release
```

脚本从`core/src/foss/golang`模块编译`cfa/native`，通过本地replace引用仓库中的Mihomo修改源码，再构建JNI桥。`-BuildDate`固定版本标记中的日期；生成物先写入`core/build/native/meta/arm64-v8a`，两库均成功才更新`core/src/main/jniLibs/arm64-v8a`，替换失败会回滚。脚本使用`-trimpath`、关闭VCS自动嵌入、移除调试符号并映射本机源码路径，避免将个人绝对路径写入二进制；不要求文件哈希一致。

如果本机访问默认Go模块代理困难，可自行设置`GOPROXY`后重试；脚本不覆盖已有代理配置。构建不会下载、导入或输出任何代理订阅、账号或签名私钥。

## 手动CI

`.github/workflows/build.yaml`仅支持手动触发，构建Debug验证包与对应源码归档，不创建GitHub Release、不推送标签、不使用发行签名secret。公开发行版由维护者在本机选择版本号、使用保管的密钥构建并上传。
