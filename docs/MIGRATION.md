# 改造说明：从 Apple Music 到 LX 音源

本文件回答三个问题：**改了什么、为什么这么改、关键文件在哪。**

---

## 1. 一句话概括

上游是「Apple Music 账号 + 代理服务器 + 本地 Widevine 解密」的播放器；本分叉把这三层全部拆掉，
换成 lx-music-mobile 的「平台直链 + 自定义源脚本 + 音质级联/换源」模型，
界面与播放体验（队列、歌词、节拍背景、睡眠定时、PiP）保持原样。

---

## 2. 数据流对比

**上游（Apple）**

```
Compose 屏幕 → MusicRepository → ProxyApi(Retrofit) 或 DirectAppleApi
                                   ↓
                            Apple amp-api（需 MUT / Bearer）
播放：ExoPlayer → /api/stream/:id（Bun 代理 → gamdl 解密 → mp4）或 on-device CENC 解密
```

**本分叉（LX）**

```
Compose 屏幕 → LxRepository → Songlists / Boards / Catalogs（四平台公开接口）
                             └→ SourceManager（脚本 → 换源 → 内置直连）
播放：ExoPlayer → PlaybackResolver（解析出的直链，直接播）
```

播放路径从「下载 + 解密 + 转封装」变成「拿 URL 直接播」，首播延迟从十几秒降到一两秒，
也不再需要 PC 端代理或 Widevine 凭据。

---

## 3. 新增模块（关键文件）

| 文件 | 职责 |
|---|---|
| `source/js/JsScriptRuntime.kt` | 单个 lx v4 脚本的宿主：QuickJS 上下文、`lx.request` 回调、`init` 能力上报 |
| `source/js/ScriptHttpClient.kt` | 脚本发起的 HTTP（默认 UA、JSON 自动解析、请求取消、超时） |
| `source/js/CryptoBridge.kt` | 脚本需要的 md5 / base64 / AES / RSA，交给 `crypto/AES.java`、`crypto/RSA.java` |
| `assets/script/user-api-preload.js` | 协议胶水层（与 lx 的 preload 对应），定义 `lx.on/ lx.send` |
| `assets/builtin_scripts/*.js` | 音源脚本（可在设置里停用 / 删除） |
| `source/catalog/SonglistCatalog.kt` | 歌单广场：四个平台的「推荐 / 分类 / 详情」 |
| `source/catalog/BoardCatalog.kt` | 排行榜：四个平台的榜单列表与分页曲目 |
| `source/catalog/SearchCatalog.kt` | 搜索：四平台（`Catalogs: MusicSearcher`，供换源复用） |
| `source/catalog/HotSearch.kt` | 搜索页热词 |
| `source/builtin/BuiltInResolver.kt` | 内置直连取流（酷我 / 网易云），脚本全挂时的兜底 |
| `source/builtin/BuiltInLyricResolver.kt` | 直连歌词 + 跨平台按名字兜底 |
| `source/SourceManager.kt` | **解析主流程**：多脚本、音质级联、换源、兜底、错误文案 |
| `source/OtherSourceFinder.kt` | 同名跨源匹配（歌名 / 歌手 / 时长 / 版本后缀），对应 lx 的 `findMusic` |
| `data/playback/PlaybackResolver.kt` | URL 解析的缓存层：单飞（single-flight）、10 分钟 TTL、失败失效 |
| `data/lx/LxIds.kt` | 字符串 id 编解码：`kw:123`（曲目）/ `wy:pl:456`（歌单）/ `tx:bd:26`（榜单） |
| `data/lx/LxMapper.kt` | 音源模型 ↔ UI 模型；`Song` 与 `Track` 的双向兜底重建 |
| `data/lx/TrackRegistry.kt` | `Song.id → Track` 注册表（脚本需要完整 musicInfo），落盘供重启后继续播 |
| `data/store/ScriptStore.kt` | 脚本清单：导入、启用/停用、删除、随包内置脚本补齐与清理 |
| `data/store/LibraryStore.kt` | 本地「我喜欢 / 播放历史 / 自建歌单」 |
| `data/VeilPreferences.kt` | 首页数据源（单选）、默认音质、自动换源、内置兜底等 |
| `ui/discover/HomeStore.kt` | 首页聚合：英雄位 + 推荐歌单 + 榜单；切源取消旧请求、按源缓存 |
| `ui/discover/DiscoverScreen.kt` | 首页 UI（结构参考通用推荐页） |
| `ui/collection/CollectionScreen.kt` | 歌单 / 榜单共用的详情页 |
| `ui/screens/SettingsScreen.kt` | **四个首页数据源切换**、音质、换源开关、脚本管理、播放外观 |

## 4. 删除模块（Apple 专属）

| 目录 / 文件 | 原因 |
|---|---|
| `media/widevine/*`、`media/AppleMusicDrmCallback.kt`、`media/GapConcealProcessor.kt` | 本地 CENC 解密链路，直链播放不需要 |
| `media/AppleDirectClient.kt`、`data/network/DirectAppleApi.kt`、`data/network/ProxyApi.kt` | Apple amp-api / 代理接口 |
| `data/datasource/*`（DirectMusicDataSource / DirectBrowseSource / DirectLyricsSource / RadioSource） | 均为 Apple 目录树 |
| `data/MutPreferences.kt`、`data/ServerPreferences.kt`、`data/StandalonePreferences.kt`、`data/OnboardingPreferences.kt` | MUT / 代理服务器 / standalone 开关 / 首启流程 |
| `data/repository/MusicRepository.kt` | 被 `LxRepository` 取代 |
| `ui/screens/{Browse,AlbumDetail,ArtistDetail,Category,Genre,Radio,MusicVideo,Onboarding,DevMenu}Screen.kt` | Apple 专属页面；对应能力改为发现页 / 详情页 / 设置页 |
| 仓库根 `server/`、`token-server/`、`get_bearer.py` | Bun 代理与 MUT 抓取脚本 |
| `media3-ffmpeg` 的 `GapConcealProcessor` 接入 | 丢帧补偿只服务于本地解密链路 |

## 5. 改造过的关键文件

| 文件 | 改了什么 |
|---|---|
| `ui/viewmodel/PlayerViewModel.kt` | 重写：去掉 DRM / standalone / 代理分支；取流改为 `PlaybackResolver.resolve(track)`；保留队列推进、crossfade 预设、节拍、睡眠定时、状态持久化；播放失败先重解析一次再跳曲；顺带写「播放历史」 |
| `ui/AppShell.kt` | 重写导航：发现 / 我的 / 搜索 / 正在播放 / 设置 + 歌单详情；去掉 onboarding、MV、电台、分类路由；保留退出确认、更新红点、toast |
| `media/InAppWebServer.kt` | 从「MUT 配对页」改成「音源管理台」：导入 / 启停 / 删除脚本、切首页源与音质、看解析日志 |
| `ui/components/NowPlayingBar.kt`、`ui/screens/NowPlayingScreen.kt` | 仅修歌词模型的 import；播放页的歌词引擎、动态背景、节拍、队列面板整套保留 |
| `lyrics/LyricParser.kt` + `LyricWordParser.kt` | LRC/QRC/KRC/TTML 统一解析；行级 LRC 按标点权重估算逐字时间 |
| `lyrics/LyricsMapper.kt` | 解析结果 → 播放页毫秒级 `LyricLine`（补 `endMs`、逐字切片） |
| `media/BeatAwareRenderersFactory.kt` | 移除 `GapConcealProcessor`，保留节拍 + 音量均衡 |
| `util/UpdateChecker.kt` | 断开车祸点：`REPO` 置空，避免把上游 Apple TV 的包当成本应用更新推下来 |

## 6. 四个首页数据源的切换逻辑

1. `SettingsScreen` 里点选平台 → `SettingsViewModel.setHomeSource()` → `VeilPreferences`（SharedPreferences + `StateFlow`）。
2. `HomeStore` 通过 `DiscoverViewModel` 订阅同一个 `StateFlow`（`drop(1)` 跳过初次重放）。
3. 收到变化 → `HomeStore.onSourceChanged()`：**先取消上一个源的请求**，清空当前内容，再按新源拉「推荐歌单（第一页）」与「榜单」。
4. 结果只在 `lastLoaded == 当前源` 时写回状态 —— 慢请求不会覆盖新选择（这是多源首页最经典的串数据 bug）。
5. 每个源的结果缓存在内存里，切回来是瞬时的；列表为空时给出「该数据源暂时没有返回内容」而不是空白页。

## 7. 已知差异与限制

- **酷狗 / QQ 的取流依赖脚本**：内置直连只实现了酷我 / 网易云的地址解析。
  音源脚本覆盖了四平台；脚本失效时需要更新脚本，不需要发新版本。
- **首页没有「专辑」板块**：lx 侧没有统一的新碟接口，按确认结论只做歌单 + 榜单。
- **crossfade 简化为淡入**：上游的双播放器交叉淡出与 Apple 的解密预取强耦合，直链模型下收益小、风险高；
  队列衔接保持「提前解析下一首 URL」，切歌不再等待。
- **歌词是行级 + 估算逐字**：lx 音源只提供 LRC。QRC/KRC/TTML 若脚本返回，则使用真实逐字时间。
- **无更新检查**：仓库还没有自己的 Release，`UpdateChecker.REPO` 留空。
