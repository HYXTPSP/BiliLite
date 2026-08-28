# BiliLite v0.4.4 源码构建说明

## 环境要求

| 项 | 要求 |
|---|---|
| JDK | 17（`sourceCompatibility = 17`） |
| Android SDK | compileSdk 35，build-tools 35.0.0 |
| Gradle | 8.7（已包含 wrapper，无需单独安装） |
| 网络 | 依赖仓库走腾讯 maven 镜像（见 `settings.gradle.kts`） |

## 构建步骤

```bash
# 1. 进入 Android 工程目录
cd android

# 2. 创建 local.properties，指向你本机的 Android SDK
#    （本文件已从源码包中排除，因为 SDK 路径因人而异）
echo "sdk.dir=/你的/Android/Sdk/路径" > local.properties
# Windows 示例（需转义）：sdk.dir=C\:\\Users\\你\\AppData\\Local\\Android\\Sdk

# 3. 构建 debug APK
./gradlew assembleDebug
# Windows 用：gradlew.bat assembleDebug

# 4. 产物位置
#    app/build/outputs/apk/debug/app-debug.apk
```

## 版本

- versionCode = 8
- versionName = 0.4.4
- applicationId = `com.bililite.app`

## 关键源码位置

| 模块 | 路径 |
|---|---|
| 播放器（字幕/清晰度/缓存/缓冲） | `app/src/main/java/com/bililite/ui/PlayerScreen.kt` |
| 首页/收藏/离线缓存/UP 管理 | `app/src/main/java/com/bililite/ui/Screens.kt` |
| 主 Activity / 全局导航 | `app/src/main/java/com/bililite/ui/Main.kt` |
| B 站 API 接入（播放流/登录/WBI/风控） | `app/src/main/java/com/bililite/core/BiliApi.kt` |
| 登录页 / 验证码 | `app/src/main/java/com/bililite/ui/LoginScreen.kt`、`LoginViewModel.kt` |
| 本地数据库（Room） | `app/src/main/java/com/bililite/data/Data.kt` |
| 全局图片加载（Coil 配置） | `app/src/main/java/com/bililite/app/App.kt` |
| 主题 / 图标等资源 | `app/src/main/res/` |

## 依赖仓库说明

`settings.gradle.kts` 中当前使用腾讯 maven 镜像加速：

```
https://mirrors.cloud.tencent.com/nexus/repository/maven-public/
```

如果你所在网络无法访问该镜像，可把 `pluginManagement.repositories` 和
`dependencyResolutionManagement.repositories` 两处替换为标准仓库：

```kotlin
google()
mavenCentral()
```

## 注意

- 这是 **debug 签名**构建；如需 release，需在 `app/build.gradle.kts` 配置签名。
- 安装前请先卸载旧版（签名可能不一致）。
