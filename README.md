# 薄音 · Veil Music

Android TV / Fire TV / 投影仪上的 **LX 音源** 音乐播放器。

本项目是 [JObersi10/apple-music-tv](https://github.com/JObersi10/apple-music-tv)（GPL-3.0）的分叉版本：
**界面与播放内核沿用上游，音源获取与播放链路整体换成 [lyswhut/lx-music-mobile](https://github.com/lyswhut/lx-music-mobile)
的处理方式**，首页（歌单 / 榜单）的展示与结构参考 [veacon/upstream](https://github.com/veacon/upstream) 重做。

> 应用不提供、不存储、不分发任何音频内容。所有播放地址由用户自行导入的音源脚本、
> 或各平台公开接口解析得到。仅供个人学习与技术研究。

---

## 一、它是什么

- **四个首页数据源**：酷我 / 网易云 / 酷狗 / QQ 音乐，设置里单选，切换后首页歌单与榜单整页重载。
- **LX 自定义音源**：兼容 lx-music v4 脚本协议（`musicUrl` / `lyric` / `pic`），脚本在 QuickJS 里跑。
- **脚本优先，内置兜底**：解析顺序 = 脚本（多脚本 + 音质级联）→ 同名换源 → 内置直连（酷我 / 网易云）。
- **遥控器优先**：全部为 D-pad 设计，顶部标签 + 底部播放条；手机网页负责导入脚本。
- **纯本地**：没有账号体系。「我喜欢」「播放历史」「自建歌单」都存本机。

## 二、系统要求

| 项目 | 值 | 说明 |
|---|---|---|
| **minSdk** | **23（Android 6.0）** | 声明的最低安装版本 |
| **完整支持基线** | **Android 8.0（API 26）** | PiP 小窗、画中画控制按钮从这里开始生效 |
| targetSdk / compileSdk | 35 | |

为什么是 23 而不是直接拉到 26：Fire TV Stick 4K（第一代）、Fire TV Cube（第一代）跑的是
**Fire OS 6，内核就是 Android 7.1（API 25）**。minSdk 一旦设成 26，这批设备直接装不上。
所以代码层面把 API 24/26 的调用都做了降级或版本守卫，让 6.0 也能启动、
Android 8.0 起功能完整：

- 画中画（`PictureInPictureParams`、`RemoteAction`）→ 仅 API 26+ 调用，低版本静默跳过；
- `Activity.isInPictureInPictureMode`（API 24）→ 统一走 `isInPipCompat()`，23 上恒为 false；
- `Collection.removeIf`（API 24）→ 换成 Kotlin 的 `removeAll { }`；
- `URLConnection.contentLengthLong`（API 24）→ 改为解析 `Content-Length` 响应头。

以上由 `lint` 的 `NewApi` 检查兜底，当前工程 **0 error**（`./gradlew :app:lintDebug`）。

## 三、快速开始

1. 安装 APK（`./gradlew assembleDebug`，或用 Releases 里的包）。
2. 首次进入「发现」页即可看到当前数据源（默认网易云）的推荐歌单与榜单。
3. 想播酷狗 / QQ 的曲目，需要在 **设置 → 音源脚本** 里导入对应平台的 lx v4 脚本：
   - 电视上会显示 `http://<电视IP>:8080`，手机浏览器打开即可粘贴脚本导入；
   - 随包的 7 个内置脚本已默认启用，可单独停用或删除。
4. 设置 → **首页数据源** 切换平台，回到「发现」页就是新平台的歌单与榜单。

## 四、音源是怎么工作的

和 lx-music-mobile 一致，取流分三段：

| 阶段 | 做什么 | 代码 |
|---|---|---|
| 搜索 / 歌单 / 榜单 | 打各平台公开接口，统一成 `Track` / `SonglistInfo` / `BoardInfo` | `source/catalog/*` |
| 解析播放地址 | 先问脚本（按音质从高到低级联），失败按「歌名+歌手+时长」换源，最后内置直连 | `source/SourceManager.kt`、`data/playback/PlaybackResolver.kt` |
| 播放 | ExoPlayer 直接播平台直链（无 DRM、无代理、无本地解密） | `ui/viewmodel/PlayerViewModel.kt` |

音质级联、换源匹配、空结果重试这些判定都照搬 lx 的规则（详见 `docs/SOURCES.md`）。

## 五、工程结构

```
android/app/src/main/java/com/veil/music/
├── VeilMusicApp.kt / MainActivity.kt     入口、Coil、崩溃捕获、管理页启动
├── data/
│   ├── lx/            LxIds（id 编解码）、LxMapper（音源模型 ↔ UI 模型）、TrackRegistry
│   ├── model/         Track / SonglistInfo / BoardInfo / Quality / SourceID / Song / Album / Lyrics
│   ├── playback/      PlaybackResolver（URL 解析 · 缓存 · 并发去重）
│   ├── repository/    LxRepository（音源数据唯一入口）
│   └── store/         ScriptStore（脚本清单）、LibraryStore（本地歌单）、JsonStore
├── source/
│   ├── js/            QuickJS 运行时、脚本 HTTP 桥、加密桥（lx v4 协议宿主）
│   ├── catalog/       歌单广场 / 榜单 / 搜索 / 热词 直连（kw·wy·kg·tx）
│   ├── builtin/       内置直连取流与歌词兜底
│   ├── SourceManager  解析顺序：脚本 → 换源 → 内置兜底
│   └── OtherSourceFinder  同名跨源匹配（对应 lx 的 findMusic）
├── lyrics/            LRC / QRC / KRC / TTML 解析 + 逐字估算
├── media/             ExoPlayer 渲染链（节拍、音量均衡）、手机管理页
└── ui/
    ├── discover/      发现页 + HomeStore（单数据源聚合）
    ├── collection/    歌单 / 榜单详情
    ├── screens/       我的、搜索、正在播放、设置
    └── viewmodel/     Player / Search / Library / Settings
```

## 六、构建

需要 JDK 17 与 Android SDK 35：

```bash
cd android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`。

### 配置更新源（上传 GitHub 之后）

「关于」页的「检查更新」和启动时的静默检查都读同一个常量：

```kotlin
// android/app/src/main/java/com/veil/music/util/UpdateChecker.kt
const val REPO = ""          // 改成 "你的用户名/仓库名"
```

留空时检查会直接返回「更新源未配置」，不会请求网络 —— 这样在还没有自己的 Release 之前，
不会把上游的 Apple Music TV 包误判成新版本推下来。

## 七、许可证与致谢

- 本项目沿用上游 **GPL-3.0**。
- 音源层移植自 **veacon/upstream**（Apache-2.0），协议规范来自 **lyswhut/lx-music-mobile**（Apache-2.0）。
- 上游 UI / 播放内核来自 **JObersi10/apple-music-tv**（GPL-3.0）。
- 第三方组件清单见 `THIRD_PARTY_NOTICES.md`。
