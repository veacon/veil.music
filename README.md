# 薄音 · Veil Music

**Android TV / 电视盒子上的音乐播放器**：遥控器操作、LX 音源脚本、逐句歌词、本地歌单，外加一个手机网页管理端。

> 应用不提供、不存储、不分发任何音频内容。所有播放地址都由用户自行导入的音源脚本、
> 或各平台公开接口即时解析。仅供个人学习与技术研究。

---

## 一、下载与安装

发布页：[Releases](https://github.com/veacon/veil.music/releases)

每个版本只有一个包 `app-debug.apk`，**仅支持 Android 8.0（API 26）及以上的 64 位系统**。
32 位盒子、Android 6/7 设备（含 Fire OS 6 的老款 Fire TV）无法安装。

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
- **LX 音源**：兼容 lx-music v4 脚本协议（`musicUrl` / `lyric` / `pic`），脚本跑在 QuickJS 里；随包内置 7 个可用音源，也可自己导入。
- **四个首页数据源**：酷我 / 网易云 / 酷狗 / QQ 音乐，设置里单选，切换后歌单与榜单整页重载。
- **三种全屏歌词**：全屏歌词 / 墨卷 / 声轨，各自一套字体与排版，互不影响。
- **纯本地**：没有账号体系。「我喜欢」「播放历史」「自建歌单」都存在本机。
- **手机管理端**：电视上打开一个局域网网页，用手机浏览器就能搜索、加歌单、传音源脚本、下载调试文件。

## 三、系统要求

| 项目 | 值 | 说明 |
|---|---|---|
| minSdk | 26（Android 8.0） | 声明的最低安装版本 |
| targetSdk / compileSdk | 35 | |
| ABI | arm64-v8a | 单一 APK，只打一份 64 位 QuickJS 原生库 |

只保留 64 位 / Android 8.0+ 之后，低版本兼容代码全部移除：PiP 直接调用平台 API、
动态广播注册统一走 `ContextCompat`、时间格式化改用 `java.time`（线程安全、可复用）、
缓存清理用 `Collection.removeIf`、下载长度用 `HttpURLConnection.contentLengthLong`。
`./gradlew :app:lintDebug` 的 `NewApi` 检查基线同步提到 26。

## 四、快速开始

1. 装上 APK，首次进入「发现」页就能看到当前数据源的推荐歌单与榜单（默认网易云）。
2. 想听别的平台，去 **设置 → 首页数据源** 切换，返回「发现」即刷新。
3. 某个歌搜不到 / 放不出，去 **设置 → 音源脚本** 看内置脚本是否启用，或在手机管理端导入新的 `.js`。
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

## 六、随附音源

随包的 7 个脚本参考自 [veacon/upstream](https://github.com/veacon/upstream)，身份以脚本头部的 `@name` / `@author` 为准（清单见
`android/app/src/main/assets/builtin_scripts/MANIFEST.json`）：

| 脚本 | 名称 | 作者 |
|---|---|---|
| `lx_source_01.js` | 独家音源 | w |
| `lx_source_02.js` | fish_music | 大鱼吃小鱼 |
| `lx_source_03.js` | Hello World 音源 | hello world |
| `lx_source_04.js` | HYWmusic_公益版 | Ryn |
| `lx_source_05.js` | 杰翔聚合音源 | 杰翔 |
| `lx_source_06.js` | lx-玉宁熙-Pro | ynx |
| `lx_source_07.js` | 星海音乐源 | 万去了了 / 科学大魔王 |

取流顺序：**脚本（多脚本 + 音质级联）→ 同名换源 → 内置直连兜底**。判定规则照搬 lx-music，
详见 `docs/SOURCES.md`。

## 七、播放页与外观

- **正在播放胶囊**：顶栏第一枚。空闲时显示「未在播放」，播放时显示当前歌名。
- **音质标签**：播放页右上角时间旁边显示当前实际音质（可能低于设置里的期望值），随界面缩放一起淡出。
- **动态背景**：封面取色的色块缓慢漂移并跟随节拍起伏；「设置 → 播放与外观 → 播放页背景」里的
  **背景摆动速度** 可在 0.1×–2.0× 之间以 0.1 步进调节，一个滑杆同时控制漂移快慢与跟拍力度。
- **全屏歌词**：字号 20–80sp、行距 40–300%、字间距 −1~1em；全屏歌词 / 墨卷 / 声轨各存一套，设置页顶部有实时预览。

## 八、检查更新

「设置 → 关于 → 检查更新」读本仓库的 GitHub Releases。电视一般连不上 GitHub，所以提供
**4 条加速线路 + 直连**，选中的那条排最前，失败会自动依次换下一条：

| 线路 | 说明 |
|---|---|
| gh-proxy.com | 默认，API 与 Release 文件都能加速 |
| ghproxy.net | 老牌线路，Release 稳、API 偏慢 |
| ghfast.top | 下载较快 |
| gh-proxy.net | 备用 |
| 直连 | 网络本身能通 GitHub 时最快 |

发布页只有一个 `app-debug.apk`（arm64-v8a）。更新器优先挑名字带 `arm64` 的包，
并跳过残留的 32 位 `armeabi` 包，避免误装旧架构构建。

## 九、构建

需要 JDK 17、Android SDK 35：

```bash
cd android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
```

产物只有一个（构建脚本里 `defaultConfig.ndk.abiFilters` 只保留 `arm64-v8a`，同时把依赖
AAR 自带的 32 位 / x86 QuickJS 库过滤掉）：

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
│   ├── builtin/       内置直连取流与歌词兜底
│   ├── SourceManager  解析顺序：脚本 → 换源 → 内置兜底
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
- 音源层与内置脚本来自 [veacon/upstream](https://github.com/veacon/upstream)（Apache-2.0）。
- LX 脚本协议规范来自 [lyswhut/lx-music-mobile](https://github.com/lyswhut/lx-music-mobile)（Apache-2.0）。
- 第三方组件清单见 `THIRD_PARTY_NOTICES.md`。
