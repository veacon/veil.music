# 薄音 · Veil Music

**Android TV / 电视盒子上的音乐播放器**：遥控器操作、LX 音源脚本、逐句歌词、本地歌单，外加一个手机网页管理端。

> 应用不提供、不存储、不分发任何音频内容。所有播放地址都由用户自行导入的音源脚本、
> 或各平台公开接口即时解析。仅供个人学习与技术研究。

> **当前版本 v1.1 · 平台基线已收紧**
> **明确放弃 32 位设备与 Android 8.0 以下系统**：自 v1.1 起只支持
> **64 位 `arm64-v8a` + Android 8.0（API 26）及以上**。Android 6.x / 7.x（含 Fire OS 6 的老款
> Fire TV）、`armeabi-v7a` / `x86` 等 32 位架构均不再提供安装包，也无法安装；相关兼容代码、
> 双 ABI 拆包与 32 位原生库已全部移除。旧设备只能停留在 v1.0 及更早版本。

---

## 一、下载与安装

发布页：[Releases](https://github.com/veacon/veil.music/releases)

v1.1 起每个版本只有一个包 `app-debug.apk`（`arm64-v8a`）。
**已放弃 32 位设备与 Android 8.0 以下系统**：Android 6.x / 7.x（含 Fire OS 6 的老款 Fire TV）
与所有 32 位架构均不再支持，安装时系统会直接拒绝；**没有兼容包，也没有降级路径**。

安装方式（任选其一）：

```bash
# 1. 局域网 ADB（电视需先打开「开发者选项 → USB/网络调试」）
adb connect 192.168.6.220:5555
adb install -r app-debug.apk

# 2. U 盘拷贝到电视，用文件管理器点开安装
```

APK 为 debug 签名，可直接侧载；安装时系统可能提示「未知来源」，允许即可。

## 二、它是什么

- **遥控器优先**：整屏按 D-pad 设计 —— 顶部一排胶囊标签（正在播放 / 发现 / 我的 / 搜索 / 设置），左右切换、上下进出列表，没有需要鼠标的控件。
- **LX 音源**：兼容 lx-music v4 脚本协议（`musicUrl` / `lyric` / `pic`），脚本跑在 QuickJS 里；可自行导入音源脚本。
- **四个首页数据源**：酷我 / 网易云 / 酷狗 / QQ 音乐，设置里单选，切换后歌单与榜单整页重载。
- **三种全屏歌词**：全屏歌词 / 墨卷 / 声轨，各自一套字体与排版，互不影响。
- **纯本地**：没有账号体系。「我喜欢」「播放历史」「自建歌单」都存在本机。
- **手机管理端**：电视上打开一个局域网网页，用手机浏览器就能搜索、加歌单、传音源脚本、下载调试文件。

## 三、系统要求

| 项目 | 值 | 说明 |
|---|---|---|
| 当前版本 | 1.1（versionCode 20） | 本次起正式收紧平台基线 |
| minSdk | 26（Android 8.0） | 低于 8.0 一律无法安装 |
| targetSdk / compileSdk | 35 | |
| 支持的架构 | 仅 `arm64-v8a`（64 位） | 单一 APK，只打一份 64 位 QuickJS 原生库 |
| 已放弃支持 | 32 位（`armeabi-v7a` / `x86`）、Android 7.1 及以下 | 不再构建、不再发布、不再兼容 |

**v1.1 的取舍**：32 位与 Android 8.0 以下设备的兼容代码、双 ABI 拆包、32 位原生库全部删除，
换来更小的包体与更直接的平台 API 调用（PiP 直接用平台 API、动态广播走 `ContextCompat`、
时间格式化用 `java.time`、缓存清理用 `Collection.removeIf`、下载长度用
`HttpURLConnection.contentLengthLong`）。`./gradlew :app:lintDebug` 的 `NewApi` 基线同步为 26。

## 四、快速开始

1. 装上 APK，首次进入「发现」页就能看到当前数据源的推荐歌单与榜单（默认网易云）。
2. 想听别的平台，去 **设置 → 首页数据源** 切换，返回「发现」即刷新。
3. 某个歌搜不到 / 放不出，去 **设置 → 音源脚本** 看已启用的脚本，或在手机管理端导入新的 `.js`。
4. 播放页按 OK 展开控制条，长按左右快退快进；返回键退回发现页。

## 五、手机管理端（局域网）

电视和手机连同一个 Wi-Fi，打开 **设置 → 音源 → 手机管理页**，电视会显示地址（默认端口 **8666**）：

```
http://<电视IP>:8666
```

手机上可以完成：

| 页面 | 用途 |
|---|---|
| `/` | 概览、快捷入口 |
| `/search` | 搜索歌曲并**直接推到电视播放**；查歌词 |
| `/playlist` | 新建歌单、把歌加进已有歌单 |
| `/scripts` | 上传 / 删除音源脚本（`.js`），提交新音源 |
| `/lyric` | 输入歌名看解析出的歌词 |
| `/debug` | 一键下载调试文件（版本、脚本加载状态、日志），排障用 |

> 端口从早期的 8080 改成 8666，是因为 8080 经常被「Android Debug Database」等调试工具占用，
> 服务会绑不上而白屏。

## 六、播放页与外观

### 层级与三枚胶囊

整个应用只有两层 + 一层浮动：

| 层 | 内容 | 返回键 |
|---|---|---|
| **L1 内容层**（最低） | 发现 / 我的 / 搜索 / 设置 / 歌单详情 | 非发现页 → 回发现页；发现页 → 退出确认 |
| **L2 播放层** | 标准播放页 → 墨卷 → 声轨 → 全屏歌词（四张脸同层，按「换效」轮转） | 任何一张脸上按返回 → 直接退出 L2 回发现页 |
| **L3 浮动层**（永远最上、不入返回栈） | 顶栏胶囊 `TopNavCapsule` / 迷你胶囊 `MiniPlayerCapsule` / 调节胶囊 `TuningCapsule` | 不参与返回；调节胶囊展开时返回键先收胶囊，再按一次才退层 |

- **顶栏胶囊（TopNavCapsule）**：屏幕顶部那一条，发现 / 我的 / 搜索 / 设置。
- **迷你胶囊（MiniPlayerCapsule）**：贴在内容页底部中央的迷你播放条（随机 / 上一首 / 播放暂停 / 下一首 /
  循环 ｜ 封面 + 歌名 + 歌手·专辑 ｜ 歌词 / 队列 / 音量，底部压一条细进度线）。按「确定」落在中间那块才进
  完整播放页；尺寸在「设置 → 播放与外观 → 正在播放胶囊」里分**高度**和**宽度**两项独立缩放（60%–140% /
  60%–100%），高度会连带缩放内部的图标、封面与文字。
- **调节胶囊（TuningCapsule）**：在播放层任意一张脸上长按**下键**呼出，调字号 / 字体 / 间距 / 背景 / 换效。

- **音源与音质标签**：播放页右上角，最前面是一枚写着当前**音源**（平台 + 脚本名，或「内置直连」「本地文件」）的
  胶囊，紧跟当前实际音质（可能低于设置里的期望值）与容器格式，随界面淡出一起消失。
- **动态背景**：封面取色的色块缓慢漂移并跟随节拍起伏。切换入口在调节胶囊的第 4 枚
  **背景**：**动态封面 / 投影光晕 / 纯黑** 三选一，外加 **摆动速度**（0.1×–2.0×，左右键 0.1 步进，
  一个速度同时控制漂移快慢与跟拍力度）。全屏歌词 / 墨卷 / 声轨里同一枚胶囊的「背景」页管的是歌词底图
  （默认 / 纯黑 / 扫码上传），页面背景与歌词底图分开配置。
- **全屏歌词**：字号 **1–80sp**、行距 **-100%–300%**、竖距（墨卷）**-100%–300%**、字间距 **-1~3em**，
  全屏歌词 / 墨卷 / 声轨各存一套。负间距是允许的：行距 / 竖距的百分比经 `fsGapScale` 折成倍数
  （-100% → 0.5 倍，0% ≈ 贴住，100% = 设计稿，300% = 3 倍），不会因为负值算出负行高而崩。
  这些参数在调节胶囊里实时调节，各界面共用同一个「换效」循环：
  标准播放页 → 墨卷 → 声轨 → 全屏歌词 → 标准播放页。

## 七、检查更新

「设置 → 关于 → 检查更新」读本仓库的 GitHub Releases。电视一般连不上 GitHub，所以提供
**4 条加速线路 + 直连**，选中的那条排最前，失败会自动依次换下一条：

| 线路 | 说明 |
|---|---|
| gh-proxy.com | 默认，API 与 Release 文件都能加速 |
| ghproxy.net | 老牌线路，Release 稳、API 偏慢 |
| ghfast.top | 下载较快 |
| gh-proxy.net | 备用 |
| 直连 | 网络本身能通 GitHub 时最快 |

发布页只有一个 `app-debug.apk`（64 位 `arm64-v8a`）。更新器优先挑名字带 `arm64` 的包，
并跳过可能残留的 32 位 `armeabi` 包，避免误装旧架构构建。v1.1 起不再发布任何 32 位包。

## 八、构建

需要 JDK 17、Android SDK 35：

```bash
cd android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
```

产物只有一个（v1.1 起 `defaultConfig.ndk.abiFilters` 只保留 `arm64-v8a`，同时把依赖
AAR 自带的 32 位 / x86 QuickJS 库全部过滤掉，**不再生成任何 32 位包**）：

```
android/app/build/outputs/apk/debug/app-debug.apk
```

## 十、工程结构

```
android/app/src/main/java/com/veil/music/
├── VeilMusicApp.kt / MainActivity.kt     入口、Coil、崩溃捕获、管理页启动
├── data/
│   ├── lx/            LxIds、LxMapper（音源模型 ↔ UI 模型）、TrackRegistry
│   ├── model/         Track / SonglistInfo / BoardInfo / Quality / Song / Album / Lyrics
│   ├── playback/      PlaybackResolver（URL 解析 · 缓存 · 并发去重）
│   ├── repository/    LxRepository（音源数据唯一入口）
│   └── store/         ScriptStore、LibraryStore、JsonStore
├── source/
│   ├── js/            QuickJS 运行时、脚本 HTTP / 加密桥（lx v4 协议宿主）
│   ├── catalog/       歌单广场 / 榜单 / 搜索 / 热词 直连（kw·wy·kg·tx）
│   ├── builtin/       平台直连取流与歌词兜底
│   ├── SourceManager  解析顺序：脚本 → 换源 → 平台直连兜底
│   └── OtherSourceFinder  同名跨源匹配
├── lyrics/            LRC / QRC / KRC / TTML 解析 + 逐字估算
├── media/             ExoPlayer 渲染链（节拍、音量均衡）、手机管理页（NanoHTTPD）
└── ui/
    ├── navigation/    顶部胶囊：正在播放 / 发现 / 我的 / 搜索 / 设置
    ├── discover/      发现页 + HomeStore
    ├── collection/    歌单 / 榜单详情
    ├── player/        墨卷、声轨等全屏歌词特效
    ├── screens/       我的、搜索、正在播放、设置
    └── viewmodel/     Player / Search / Library / Settings
```

更多设计说明见 `docs/ARCHITECTURE.md`。

## 十一、数据与隐私

- 不收集任何用户数据，没有统计 SDK、没有账号、没有云端同步。
- 手机管理页只监听局域网，且只在用户手动打开时启动；关掉即停止。
- 调试文件只包含版本号、脚本加载状态与本机日志，不上传，只在你点「下载」时发到你的手机。

## 十二、许可证与致谢

- 本项目沿用 **GPL-3.0**（见 `LICENSE`）。
- 界面与播放内核源自 [JObersi10/apple-music-tv](https://github.com/JObersi10/apple-music-tv)（GPL-3.0）。
- LX 脚本协议规范来自 [lyswhut/lx-music-mobile](https://github.com/lyswhut/lx-music-mobile)（Apache-2.0）。
- 第三方组件清单见 `THIRD_PARTY_NOTICES.md`。
