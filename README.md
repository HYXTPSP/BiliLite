# BiliLite

> 学习专注型 B 站第三方客户端 —— 黑白极简,为深度学习而生

BiliLite 是一款用极简风格设计的,专注于学习的学习版 B 站,从根源上屏蔽娱乐内容。

当前版本: v0.5.0 (versionCode 18)

## 下载

- 最新 APK: [GitHub Releases](https://github.com/HYXTPSP/BiliLite/releases)

## 功能特性

- **B 站账号登录**:支持二维码、验证码、密码三种登录方式,登录后画质与功能完整
- **UP 主管理**:搜索添加 UP 主(显示粉丝数,防止选错),添加后可集中查看其全部视频
- **首页视频流**:仅展示已添加 UP 主的视频,无推荐流、无广告;竖卡/横排双布局
- **内嵌播放器**:
  - 自动记录观看进度,支持断点续播
  - 双击暂停/继续播放
  - 左右滑动调节亮度/音量(带指示器)
  - 双指缩放(1x-4x)+ 撤销
  - 三击打书签 + 书签列表 + 半屏侧栏
  - 原生风格控制条,无上一P/下一P干扰
  - 多P视频缓存与断点续传
- **收藏队列**:收藏视频集中管理,合并去重
- **观看历史**:自动记录,支持一键清除
- **BV 号搜索**:直接输入视频 BV 号精准定位
- **检查更新**:自动检测新版本
- **无干扰**:无评论区、无弹幕、无自动连播
- **插件系统**(v0.5.0 新增):
  - 统一插件框架,支持主题插件与功能插件
  - 主题插件:一键切换全局 UI 配色/背景/圆角(如初音、水墨、野性主题)
  - 功能插件:通过 Lua 脚本拓展播放器/数据/网络能力(如自动跳过片头、禁用云收藏、字幕大小调节)
  - 插件管理:应用内列表启停/卸载,安装即用

### 插件接口说明

插件系统目前处于**早期阶段**,已提供的基础接口包括:

- `ui.*`:主题切换、菜单注册、设置项注册、弹窗/Toast
- `player.*`:播放/暂停/跳转/倍速/事件订阅
- `data.*`:收藏/历史/书签数据访问
- `network.*`:带登录态的 B 站 API 请求
- `events.*`:全局事件订阅
- `system.*`:插件目录读写、私有存储、日志

**当前接口数量有限,后续会持续拓展**(更多 UI 组件注入、播放器深度控制、数据操作等)。接口规范遵循语义化版本管理,升级核心不会破坏已有插件。

## 界面预览

### 手机端效果

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/phone_1.jpg" width="220"><br><b>首页</b><br>视频列表 · 搜索筛选</td>
    <td align="center"><img src="docs/screenshots/phone_2.jpg" width="220"><br><b>我的</b><br>个人中心 · 收藏/历史</td>
    <td align="center"><img src="docs/screenshots/phone_3.jpg" width="220"><br><b>收藏页</b><br>收藏视频列表</td>
  </tr>
</table>

### 平板端效果

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/tablet_1.jpg" width="420"><br><b>视频播放页</b><br>播放器 + 分P/章节列表</td>
    <td align="center"><img src="docs/screenshots/tablet_2.jpg" width="420"><br><b>首页(横屏)</b><br>网格布局视频列表</td>
  </tr>
</table>

### 主题插件效果

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/plugin_theme_miku.jpg" width="220"><br><b>Miku主题插件</b><br>初音未来风格主题</td>
    <td align="center"><img src="docs/screenshots/plugin_theme_wild.jpg" width="220"><br><b>原野主题插件</b><br>自然原野风格主题</td>
  </tr>
</table>

## 技术栈

- Kotlin + Jetpack Compose
- Room(本地数据库)
- Media3/ExoPlayer(播放器)
- LuaJ(插件脚本引擎)
- B 站网页 API(wbi 签名)
- minSdk 24 / targetSdk 35

## 构建

```bash
export ANDROID_HOME=/opt/android-sdk
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

## 插件开发

示例插件源码位于 `plugins_examples/` 目录,包含:

- `theme_miku` / `theme_ink` / `theme_wild`:主题插件示例
- `feature_autoskip`:自动跳过片头功能插件示例
- `feature_disable_cloudfav`:禁用 B 站收藏插件示例
- `feature_subtitle_size`:字幕大小调节插件示例

打包脚本: `pack_plugins.sh`

## 许可证

本项目基于 [MIT License](LICENSE) 开源。

## 免责声明

1. **非官方项目**:本项目为个人开发者出于学习与兴趣目的开发的第三方客户端,与哔哩哔哩(Bilibili)官方无任何隶属、合作或授权关系,哔哩哔哩官方未对本项目进行任何形式的认可、支持或审查。

2. **版权声明**:本项目不包含任何哔哩哔哩官方资源、受版权保护的内容或商业素材。App 内展示的视频、图片、文字等内容均来自哔哩哔哩平台本身,其全部版权归原权利人所有。本项目仅提供客户端壳层与交互功能,不存储、不缓存、不传播任何受版权保护的内容。

3. **数据来源与合规性**:本项目通过哔哩哔哩网页公开接口获取数据,并依赖用户本人账号的 Cookie(SESSDATA)进行鉴权。用户需自行承担使用过程中的全部风险:
   - 哔哩哔哩可能随时变更接口、加密参数或风控策略,导致功能不可用
   - 频繁或异常请求可能触发哔哩哔哩的风控机制,导致账号受限、封禁或产生验证码
   - 使用本应用期间产生的任何账号异常、数据损失或服务中断,均由使用者自行承担

4. **凭据安全**:用户的登录凭据(Cookie)仅保存在本机本地存储中,不上传至任何服务器,开发者无法获取用户凭据。但仍建议用户妥善保管账号信息,避免在公共设备上使用本应用。

5. **禁止用途**:本项目不得用于任何商业用途、牟利行为、侵犯他人权益的行为,或违反法律法规、平台规则的行为。请仅将本项目用于个人学习、技术研究之目的。

6. **无担保声明**:本项目按"现状"(AS IS)提供,不提供任何明示或默示的担保,包括但不限于适销性、特定用途适用性及不侵权担保。开发者不对因使用本项目产生的任何直接或间接损失承担责任。

7. **移除要求**:如哔哩哔哩官方或任何权利人认为本项目侵犯其合法权益,可通过 GitHub Issues 联系,开发者将在核实后第一时间移除相关内容或停止维护本项目。

8. **版本说明**:本项目处于持续开发阶段,可能包含缺陷或不稳定功能,请在使用前备份重要数据。
