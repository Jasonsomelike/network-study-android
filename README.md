# 知行网络学堂 Android

这是 `https://www.jasonsome.cn` 的 Capacitor Android 客户端。

当前版本：`1.11.0`（versionCode `12`）。App 内“我的 → App 设置”可以选择文件保存文件夹和界面配色，
文档预览与下载使用不同链路，预览不会自动触发下载。

## QQ 登录

- 移动应用 APP ID：`1904508499`
- Android 包名：`cn.bestijason.networkstudy`
- QQ OpenSDK：3.5.19 Lite
- SDK SHA-256：`9D57FE61FF9026D34AC84BC63DC719F61DA6AA40533A299CC6F73D4CE9DF7AF8`

APP Key 属于服务端密钥，不写入 APK，也不会提交到公开仓库。原生客户端只把 QQ
授权返回的 `access_token` 和 `openid` 交给 `www.jasonsome.cn` 服务端校验。

## 1.11.0 更新

- 修复原生返回栈：会话详情返回到“对话”页，一级页连续返回才退出 App。
- 增加 App 前后台 lifecycle 事件，便于网页端恢复后台生成中的会话状态。
- WebView User-Agent 和原生桥版本同步为 `NetworkStudyAndroid/1.11.0`。

## 1.9 更新

- 原生 QQ 登录继续使用用户提供的 QQ OpenSDK 3.5.19 Lite，并保持移动应用 APP ID `1904508499`。
- WebView User-Agent 和原生桥版本同步为 `NetworkStudyAndroid/1.9`。
- 输入框、对话发送、账户绑定与“我的”页设置跟随网页端安全策略同步优化。
- 修正 App 内版本说明、配色设置、文件下载命名与页面过渡说明。

## 1.8 更新

- QQ 登录回调增加 `checkLogin` 主动校验兜底，并在前端消费结果前同步尝试恢复 SDK 会话，降低授权后回到 App 无响应的概率。

## 1.7 更新

- QQ 登录使用用户提供的 `open_sdk_3.5.19_r9483ffc7_lite.jar`，授权结果由回调、SDK 会话恢复和持久化结果三重链路接收。
- 账号密码用户可在“我的”绑定 QQ；QQ 独立用户自动使用 QQ 昵称与头像。
- 进入具体会话后隐藏原生顶部栏和底部导航，上传按钮移入输入区域左侧，输入区改为紧凑布局。
- 引用详情使用真正的全屏层，PDF 预览返回、翻页与安全区域体验同步优化。
- 支持头像上传和账号密码修改。
- App 设置新增森林青、海洋蓝、星云紫、日落橙、深夜等多套配色，并与网页账号同步。
- 页面切换动画与“关于”页信息进一步完善。
- 继续使用永久发布证书签名，保持覆盖安装和后续版本升级兼容。

## 环境

- Node.js 20+
- JDK 21
- Android SDK 36

本机推荐：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\openjdk\jdk-21.0.8'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
```

## 开发与构建

推荐直接运行一键脚本：

```powershell
.\build-apk.ps1
```

脚本会自动设置本机 JDK/Android SDK、同步 Capacitor、构建并复制 APK 到：

`E:\vibecoding\network-study-android\知行网络学堂-debug.apk`

也可以手动构建：

```powershell
npm install
npx cap sync android
npm run build:debug
```

调试 APK：

`android/app/build/outputs/apk/debug/app-debug.apk`

连接开启 USB 调试的 Android 手机后，可安装：

```powershell
adb install -r ".\知行网络学堂-debug.apk"
```

使用 Android Studio：

```powershell
npm run open
```

## 发布签名

创建仅自己保存的签名文件：

```powershell
keytool -genkeypair -v -keystore network-study-release.jks -alias network-study -keyalg RSA -keysize 2048 -validity 10000
```

不要把 `.jks` 和密码提交到 Git。

## 开源许可

项目自身源码使用 MIT License。QQ OpenSDK 的许可仍以腾讯条款为准，详见
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。
