# 薄音 · Veil Music

**Android TV / 电视盒子上的音乐播放器**：遥控器操作、LX 音源脚本、逐句歌词、本地歌单，外加一个手机网页管理端。

> 应用不提供、不存储、不分发任何音频内容。所有播放地址都由用户自行导入的音源脚本、
> 或各平台公开接口即时解析。仅供个人学习与技术研究。

---

## 一、下载与安装

发布页：[Releases](https://github.com/veacon/veil.music/releases)

每个版本按 CPU 架构分两个包，**按你的设备选一个安装即可**（装错了会提示「应用未安装」）：

| 包名 | 适用设备 | 说明 |
|---|---|---|
| `app-arm64-v8a-debug.apk` | 绝大多数 64 位电视盒子 / TV Stick / 投影仪 | 推荐，优先选它 |
| `app-armeabi-v7a-debug.apk` | 老款 32 位盒子（2017 年前后的低配机型） | 64 位包装不上时再用 |

安装方式（任选其一）：

```bash
# 1. 局域网 ADB（电视需先打开「开发者选项 → USB/网络调试」）
adb connect 192.168.6.220:5555
adb install -r app-arm64-v8a-debug.apk

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
| minSdk | 23（Android 6.0） | 声明的最低安装版本 |
| 完整支持基线 | Android 8.0（API 26） | 画中画、画中画控制按钮从这里开始生效 |
| targetSdk / compileSdk | 35 | |
| ABI | arm64-v8a / armeabi-v7a | 分两个 APK 发布 |

为什么 minSdk 是 23 而不是 26：Fire TV Stick 4K（第一代）、Fire TV Cube（第一代）跑的是
Fire OS 6，内核就是 Android 7.1（API 25）。设成 26 这批设备直接装不上。代码里对 API 24/26
的调用都做了版本守卫或降级（`isInPipCompat()`、`removeAll { }`、`Content-Length` 手动解析等），
`./gradlew :app:lintDebug` 的 `NewApi` 检查当前 **0 error**。

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

发布页有两个 ABI 包，应用会按本机架构自动挑对应的那一个下载（挑不到才退回第一个 `.apk`），
不需要手动选。

## 九、构建

需要 JDK 17、Android SDK 35：

```bash
cd android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
```

产物按 ABI 拆分（`android/app/build.gradle.kts` 的 `splits.abi`）：

```
android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
android/app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk
```

需要合并包时把 `isUniversalApk` 改成 `true`，会额外生成 `app-universal-debug.apk`（体积约 40 MB）。

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
