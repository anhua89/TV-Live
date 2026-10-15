# 简视 TV 1.4

为 TCL Q10M / Android TV 编写的直播工程。当前版本为 **1.4 / versionCode 5**，由本次全新 1.0 工程升级，应用标识为 `cn.anhuatv.live.v1`。本次新工程没有读取、复制或依赖更早旧工程的代码、GitHub 账号或签名密钥。

**已经安装本次新工程的 1.0～1.3：双击 `先读我.html`，按 [升级到 1.4](docs/升级到1.4.md) 操作，沿用仓库与签名。首次从零配置可阅读 [完整操作指南](docs/操作指南.md)。**

若已上传且遇到 `setup-android@v3` 失败，参见 [构建环境修复](docs/构建环境修复.md)，只需替换两份工作流。

本交付包只含完整源代码、构建工作流、签名工具和文档，没有 APK，也没有私人签名密钥。你可以在自己的新 GitHub 仓库运行构建。

## 已落实的功能

| 功能 | 实现 |
|---|---|
| 原生播放 | 央视、四川卫视及其他非成都频道继续使用 Media3 / ExoPlayer 和原生 SurfaceView |
| 成都网页 | 独立 WebView；看度首页直播区、桌面适配、遥控器光标、新窗口链接、播放/全屏与网页诊断 |
| 咪咕与优酷 | “全部”末尾各有一个网页频道；复用成都网页操作，各自保存网址和布局设置，也可收藏 |
| 遥控器 | 上下换台；列表上下首尾循环，长按连续选择，确定才切台；左右分组；数字编号选台；长按确定收藏 |
| 频道 | 按央视、卫视、成都、其他和收藏分组；记住上一次成功播放的频道 |
| 订阅 | 五份历史约定的网络订阅；默认只开 best-fan；支持添加、修改、启停、删除、刷新、M3U/TXT 导入 |
| 多源 | 同频道多线路合并去重，优先上次成功线路；出错、缓冲超时、无首帧时尝试下一条；可以手动选线 |
| 本机测速 | 网络媒体抽样与真实首帧测试分别提供；逐路实播时静音，结束后恢复原频道 |
| 更新 | 新仓库构建时自动绑定；GitHub Releases 下载，大小、SHA-256、包名、版本、签名核验；进入系统确认安装 |
| 目录兜底 | 首次启动可使用内置 best-fan；重启先用缓存，后台失败保留目录；构建时刷新内置目录并随 Release 发布 |

## 当前边界

- 成都频道打开用户截图中的看度新闻网首页 `https://cditv.cn/`，尝试定位电视直播区；默认桌面布局、适配屏幕，并提供播放、全屏、新窗口跳转与网页诊断。未虚构逐频道直达地址。菜单可修改网址。
- 用户已反馈 1.3 成都网页可用；1.4 保留其首页、设置和操作方式。新增咪咕 `https://www.miguvideo.com/`、优酷 `https://www.youku.com/ku/webhome` 官方网页入口；节目、登录和会员权益按网站提示处理。新增两个站点尚未在 TCL Q10M 上实播验证，仍受电视网页组件、网站和网络条件影响。
- 回看播放与 EPG 尚未实现。解析器会保留订阅中的 `catchup` / `catchup-source` 元数据，便于后续按有效来源开发。
- 网络订阅默认仅 best-fan 开启，依据你在四川移动宽带的实际测试。成都、咪咕、优酷网页入口不依赖节目流订阅成功。服务器或开发电脑能访问，不能证明 TCL Q10M 本地一定能出画。
- 原生播放器支持的常见协议为 HTTP/HTTPS HLS、DASH、渐进式流和受设备能力限制的 RTSP。没有 DRM 解密、绕过登录、组播代理或内置第三方账号。
- 已安装本次 1.0 的用户沿用同一应用标识与签名覆盖升级，不必重新建仓库或生成密钥。更早的另一套旧工程仍不属于本次迁移范围。

## 构建配置

| 项目 | 固定版本 |
|---|---|
| JDK | 17 |
| Gradle | 8.11.1，附官方 Wrapper 和发行包 SHA-256 |
| Android Gradle Plugin | 8.9.2 |
| compileSdk / targetSdk | 35 / 35 |
| minSdk | 21（Android 5.0） |
| Media3 | 1.7.1 |
| AndroidX Core | 1.15.0 |

操作指南采用 **GitHub Desktop 上传 + GitHub Actions 构建**，无需 Android Studio、Android SDK 或个人访问令牌。你的 Windows 电脑只需 JDK 的 `keytool` 生成一次新密钥；Android 编译环境由 GitHub 工作流配置。

本地开发时可使用 Android Studio，或在 JDK 17 / Android SDK 35 环境运行：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
```

正式构建要求 `TV_KEYSTORE_PATH`、`TV_STORE_PASSWORD`、`TV_KEY_ALIAS`、`TV_KEY_PASSWORD` 四个环境变量。GitHub 工作流会根据 Repository Secrets 自动提供，参见操作指南。

## 文件入口

- [操作指南](docs/操作指南.md)：新账号、工程路径、上传、签名、Secrets、构建、安装、更新、故障处理。
- [升级到 1.4](docs/升级到1.4.md)：已有用户更新、咪咕/优酷网页操作、循环选择与实机验收。
- [技术说明](docs/技术说明.md)：类职责、故障切换、测速含义、生命周期与更新校验。
- [验证记录](docs/验证记录.md)：已做和未做的检查。
- [签名脚本](tools/New-SigningKey.ps1)：只在你的电脑生成新密钥，不包含预制密钥。
- [正式构建工作流](.github/workflows/release.yml)：在 Actions 中显示为 `Build signed TV APK`。
- [代码检查工作流](.github/workflows/check.yml)：显示为 `Code checks`。
- [版本配置](version.properties)：每次发布同时递增 `versionCode` 和 `versionName`。

本地运行完整快速检查需 Python 3 与 Node.js 18+：`python3 tools/check_project.py`。网页适配逻辑测试已包含在现有 GitHub 工作流调用的快速检查中。

## 官方资料

- [Media3 版本说明](https://developer.android.com/jetpack/androidx/releases/media3)
- [AGP 8.9 兼容配置](https://developer.android.com/build/releases/agp-8-9-0-release-notes)
- [GitHub Repository Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)
- [GITHUB_TOKEN 权限](https://docs.github.com/en/actions/tutorials/authenticate-with-github_token)
- [Android 文件分享与 FileProvider](https://developer.android.com/training/secure-file-sharing/share-file)
