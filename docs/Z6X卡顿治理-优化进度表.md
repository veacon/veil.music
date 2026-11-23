# Veil Music v2.5 · Z6X 卡顿治理优化进度表

> 生成时间：2026-11-21　状态基准：**波次0 已完成，待用户真机确认**（本表为唯一进度追踪文件，干活改状态）
> 工作区：`E:\ai\amusic\app`（git 仓库）
> 依据：三份 v2.4 审计报告合并裁决 + 用户产品边界（见下）

---

## 0. 已确认的产品边界（用户 2026-11-21 拍板，全表裁决以此为准）

| # | 边界 | 对优化的影响 |
|---|------|----------------|
| ① | 设备最低 **Android 8** | offload 门槛 API≥29 → 全机型必走自定义音频处理器链，DSP 治理优先级上调 |
| ② | **纯音乐应用**：无 MV、电台、电脑端；相关播放器与代码删除（动态封面除外） | P0-4 由"调参"改为"删链路" |
| ③ | 动态封面**固定软解播放器**（FFmpeg 视频解码器），不与主音频抢硬件解码器 | P0-3 方案锁定 |
| ④ | **电视端 TV 应用，性能优先、不卡顿**，不做省电/低电量语义 | 档位语义重写为帧预算档；preparedNext 反转保留 |
| ⑤ | **手机 Web 端不删除**，改为**按需激活**：仅设置页扫码登录弹窗、资料库扫码弹窗等场景打开，其余时间不常驻 | P0-4 拆分：Web 服务从"删除"改为"按需启停" |
| ⑥ | **本次在设置中增加 30fps / 60fps 选项**，由用户实机测试后决断默认值 | P0-6 改为双档实测开关，不做自动默认决断 |
| ⑦ | **测试环节由用户负责**，我只负责构建并**告知 APK 完整路径** | 每波次交付物固定：构建产物路径 + 建议验证点（用户自测） |

---

## 1. 总进度一览表

状态图例：⬜ 未开工 ｜ 🔵 进行中 ｜ ✅ 完成 ｜ ⏸ 待用户拍板

**整体进度：15 / 21 项完成**

| ID | 级别 | 事项 | 主要文件 | 波次 | 状态 | 验收/回归点（用户实测） |
|----|------|------|----------|------|------|------------------------|
| P0-1 | P0 阻断 | 本地源码树补齐，可编译 | 缺失：`ui/background/LavaBackground.kt`、`media/AudioSpectrum.kt`、`BackgroundStylePreferences`、PlayerViewModel spectrum/backgorundStyle 成员；`MacBackground` 签名 | 波次0 | ✅ | `assembleDebug` 通过 |
| P0-2 | P0 | BeatProcessor 死链路删除（音频线程逐样本 DSP，输出无人消费） | `media/BeatAnalyzer.kt`、`media/BeatAwareRenderersFactory.kt`、`ui/viewmodel/PlayerViewModel.kt:713/798/2120`、死代码 `DynamicBackground`（`NowPlayingScreen.kt:1558` 区间） | 波次1 | ✅ | 播放无周期性"卡一下"；WDOG 日志计数下降 |
| P0-3 | P0 | 动态封面：两处裸 ExoPlayer 合并单实现 + 小缓冲定标(4MB/5-15s) + **固定 FFmpeg 软解** | `ui/components/MotionArtwork.kt`、`ui/screens/NowPlayingScreen.kt:1315-1338`(MotionCover)、`media/PlaybackTuning.kt`(新增 videoLoopLoadControl)、`media/FfmpegSupport.kt` | 波次1 | ✅ | 专辑/歌单详情页封面动效正常；播放页滑动不掉帧；封面不再绿帧 |
| P0-4 | P0 | 删除 MV/电台链路（播放器+ViewModel+Screen+LxPocActivity+Manifest 导出） | `ui/viewmodel/MusicVideoViewModel.kt`、`ui/screens/MusicVideoScreen.kt`、`media/Mv*` 若干、`luoxue/LxPocActivity.kt`、`AndroidManifest.xml:62-67`、导航/入口/prefetchSong 分支 | 波次1 | ✅ | 全功能回归：列表/播放/歌词/扫码均正常，无 MV 残留入口 |
| P0-5 | P0 | 手机 Web 服务改**按需启停**（不常驻、非默认开） | `media/InAppWebServer.kt`、`AppleMusicApp.kt:75-92`、设置页扫码登录弹窗、资料库扫码弹窗调用点 | 波次2 | ✅ | 冷启动后无 8080/8081 监听；打开扫码弹窗可用；退出弹窗后自动停 |
| P0-6 | P0 | Compose 稳定性：`Song`/`PlayerState` 标 `@Immutable` + 播放页/胶囊/播放条状态投影细粒度订阅 | `data/model/Song.kt`、`PlayerViewModel.kt:147-245`、`NowPlayingScreen.kt:174`、`MiniPlayerCapsule.kt:99`、`NowPlayingBar.kt:25`（参照 `PlaylistPanelState.kt`/`shellUiState()` 既有范式） | 波次2 | ✅ | 播放页每秒全屏重组→0；列表滚动帧率明显提升 |
| P0-7 | P0 | 背景帧预算：**设置新增 30fps / 60fps 档**（用户实测决断），废弃省电/低电量语义接线，空闲播放页不再强制 playing=true，暂停停渲染留末帧 | `ui/background/FluidQuality.kt`、`KawarpTextureView.kt:155`、`MacTextureView.kt:144-153`、`IdlePlayerBackground.kt:19`、`PlayerViewModel.kt:188`、设置页(PreferenceScreen/SettingsScreen) | 波次2 | ✅ | 两档均可选可持久化；弱 GPU 档滑动/切歌流畅；观感由用户对比定默认 |
| P0-8 | P0 | 应用级 `onTrimMemory` 编排（Coil→QuickJS→preparedNext→背景档 依序让路，压力解除恢复） | `AppleMusicApp.kt`、`PlayerViewModel.kt`、`luoxue/LxPlayback.kt` | 波次3 | ✅ | 压后台/低内存场景不被 LMK 直接杀；恢复播放正常 |
| P0-9 | P0 | QuickJS 10 引擎+10 线程：取消启动全量预热，改按上次使用音源 1-2 个预热 + 其余懒加载 | `AppleMusicApp.kt:65`、`luoxue/LxPlayback.kt:194-228`、`luoxue/LxScriptEngine.kt:52` | 波次3 | ✅ | 未用落雪源时 `ps -T` 无 `lx-js-*` 线程；落雪源首解成功 |
| P1-1 | P1 | Coil 内存缓存分档 64MB → 1GB 机 24MB | `AppleMusicApp.kt:31` | 波次3 | ✅ | meminfo Graphics/Java Heap 下降；封面滚动无花屏 |
| P1-2 | P1 | 新增 `util/DeviceTuning.kt` 单点设备档位（isLowRamDevice/memoryClass/GPU 能力），供 P1-1/P0-7/P0-4 余量策略消费 | 新增文件，接线 `util/RenderDiagnostics.kt:108-122` | 波次3 | ✅ | 日志打印判定结果正确 |
| P1-3 | P1 | Lazy 列表补 key（Category/Genre/Library/AddToDialog，MV 项随 P0-4 消失） | `CategoryScreen.kt:128`、`GenreScreen.kt:165`、`LibraryScreen.kt:1125`、`AddToDialog.kt:62` | 波次2 | ✅ | 数据变动滚动位置/焦点不丢 |
| P1-4 | P1 | preparedNext 策略反转：默认保留预建换"切歌零停顿"，仅 `onTrimMemory` 时 `clearPreparedNext()`（压力解除恢复） | `PlayerViewModel.kt:758-794`、`canPrepareNextWithoutFade` | 波次3 | ✅ | 同专辑连续切歌无停顿；低内存压力下让路生效 |
| P1-5 | P1 | 歌词列 `CompositingStrategy.Offscreen` 与帧档/机型联动回退 `Auto` | `NowPlayingScreen.kt:1932`（参照 `:555-557` 既有兼容记录） | 波次3 | ✅ | 歌词滚动不掉帧、无马赛克 |
| P1-6 | P1 | `AudioSpectrum`/Visualizer 释放链核查（setActive(false)+release 全路径） | 依赖 P0-1 补齐后的 `media/AudioSpectrum.kt` | 波次4 | ⬜ | 反复进出播放页无音频路由异常 |
| P1-7 | P1 | `largeHeap` 灰度评估（前置项落地后 A/B；**默认本轮维持现状**，出可开关实验包供用户测试） | `AndroidManifest.xml:22` | 波次4 | ⬜ | 用户实测：崩溃率/GC Clamp 日志对比后拍板 |
| P2-1 | P2 | `data/fonts` 死包删除（5 文件 + TLS 证书资产） | `data/fonts/`、`assets/certificates/` | P2阶段(未排波) | ⬜ | 字体显示正常 |
| P2-2 | P2 | 播放/图片磁盘预算下调 256→128MB、1GB→512MB | `data/cache/PlaybackCache.kt:60`、`data/cache/AppDiskCache.kt` | P2阶段 | ⬜ | 存储占用符合新预算 |
| P2-3 | P2 | BeatAudio PCM 诊断捕获 gate 到 DEBUG | `BeatAnalyzer.kt:68-88`、`PlayerViewModel.kt:1383` | 波次1随 P0-2 | ✅ | release 无诊断代码路径 |

### 1.1 完成备注

#### 波次0 · P0-1（2026-11-21 完成，待用户真机确认）

- **分支 / 提交**：`opt/z6x-v2.5`（基线 `14aef62`，main@2.2.1 时代）
- **基线来源**：本机 `E:\ai\veil-latest\app` 即 2.4 发布包的构建源（`versionName 2.4 / versionCode 253`，构建产物与线上 `Veil-Music-2.4.apk`、`veilmusic-2.4-amatvlyrics-debug.apk` 尺寸一致），故 P0-1 走**方案 A（原版合入）**，未使用 `// REBUILT-PLACEHOLDER` 重建。
- **改了什么**：`E:\ai\veil-latest\app` → `E:\ai\amusic\app` 全量镜像（排除 `build/`、`.gradle/`、`.kotlin/`、`.robolectric-deps/`）。
  - 新增 31 个源文件/测试：`ui/background/{LavaBackground,LavaBackdropView,LavaCpuBackdropView,LavaCpuField,LavaPalette,LavaPulseSpeed,ArtworkPalette,BackdropMotion,BackdropRecovery}.kt`、`media/{AudioSpectrum,SongAudioSpectrum,SpectrumAnalyzer,SpectrumCaptureRetry}.kt`、`data/{BackgroundStylePreferences,SpectrumPreferences}.kt`、`data/auth/{MusicKitDeveloperToken,PhoneMusicAuthorization}.kt`、`ui/components/PhoneSignInDialog.kt`、`ui/lyrics/*`（9 个逐字歌词文件）、`ui/screens/LyricElastic.kt` 及对应 3 个单测
  - 覆盖 24 个与基线不一致的既有文件（含 `NowPlayingScreen.kt`、`PlayerViewModel.kt`、`DevMenuScreen.kt`、`MacBackground.kt`、`TouchComponents.kt`、`AppShell.kt` 等）
  - 删除 2 个：`ui/components/FocusFrame.kt`、`test/.../FocusScrollPlacementTest.kt`（TV 焦点外圈，11-20 已按用户要求移除，2.4 发布包内无此符号）
- **P0-1 五项缺失复核**：LavaBackground / AudioSpectrum / BackgroundStylePreferences / PlayerViewModel 的 `audioSpectrum`·`spectrumEnabled()`·`setSpectrumEnabled()`·`backgroundStyle`·`setBackgroundStyle()`·`pulseSpeedIndex`·`stepPulseSpeed()` / MacBackground `spectrum`+`pulseSpeedMultiplier` 参数，**全部成立**；另发现第 6 项 `ui/background/LavaPulseSpeed.kt` 也缺失（原表未列）。
- **编译门禁**：`gradlew :app:assembleDebug --offline` → **BUILD SUCCESSFUL（2m，41 tasks）**
- **产物**：`E:\ai\amusic\.apk\veilmusic-w0-baseline-2.4.253-debug.apk`（39,428,836 B，签名 SHA-256 `550d861a…7645e`，与线上 2.4 同证书 → 可覆盖安装）
- **环境修正（不改工程文件）**：① 构建需把便携 git `E:\ai\vm\tools\mingit\cmd` 加进 PATH（`app/build.gradle.kts` 的 `providers.exec("git rev-parse")` 依赖）；② Gradle 的 `user.home` 落在 `C:\Users\Public\Loomy\e7d3e769f3f5\opencode`，其 `.android/debug.keystore` 原为自动生成的新证书，已替换为历史证书（原文件备份为 `debug.keystore.auto.bak`）；③ 单测若需离线 Robolectric，设 `ROBOLECTRIC_DEPENDENCY_DIR=E:\ai\veil-latest\app\.robolectric-deps`。
- **版本号**：本轮保持 `2.4 / 253`（波次0 只做基线修复，未擅自升 2.5）。
- **待用户验证**：Z6X 安装可启动；播放页背景（流体/熔岩两风格）与背景律动观感与 2.4 发布版对比是否一致。

#### 波次1 · P0-2 / P0-3 / P0-4 / P2-3（2026-11-21 完成，待用户真机确认）

**P0-2 节拍 DSP 整链删除**
- 删除 `media/BeatAnalyzer.kt`（`BeatAnalyzer` + 音频线程逐样本 `BeatProcessor` + PCM 诊断捕获，P2-3 一并消失）
- `media/BeatAwareRenderersFactory.kt` → 改名 `media/AudioRenderersFactory.kt`：保留 gap 修补 / 响度均衡 / 采样率统一 / float 输出回退，只摘掉 beat 处理器
- `PlayerViewModel`：去掉 `beatAnalyzer` 注入、`mainProc`/`cfProc`、`PreparedNext.processor`、`promoteCrossfadeBeat()` 及 3 处调用、`updateOutputLatency` 里的 beat 延迟、`stopPlayback`/切歌处 `resetBeat`、`CAPTURE_PCM` 诊断块
- `NowPlayingScreen`：删除死代码 `DynamicBackground`（约 210 行，唯一消费者是节拍能量）
- `TopNavBar`：`EqBars` 退回纯摆动（不再订阅 energy）；`DevMenuViewModel` 去掉 `beatLatencyMs`/`setBeatLatency`；`InAppWebServer` 去掉 `/set-beat-latency` 路由、`applyBeatLatency`、手机页「节拍延迟」卡片与 4 条英文本地化
- ⚠ 与审计报告的差异：审计称「节拍输出无人消费」，实际顶栏「正在播放」旁三根小条是一处消费者。经用户拍板，接受降级为纯摆动。

**P0-3 动态封面合一 + 小缓冲 + 固定软解**
- `ui/components/MotionArtwork.kt` 成为唯一实现：PlayerView/SurfaceView 独立硬件层 + **禁音轨** + 绿帧/黑帧快门 + 800ms 淡入
- 删除 `NowPlayingScreen.MotionCover`；`AlbumDetailScreen` / `PlaylistDetailScreen` 改调 `MotionArtwork(..., gateOnPreference = false)`（详情页在 2.4 里不受「动态封面」开关控制，保持原观感）
- `media/PlaybackTuning.kt` 新增 `videoLoopLoadControl()`：5–15s 窗口 / 4MB 上限 / 500ms 起播（原来走的是音频那套 20–60s / 8MB）
- ⚠ **解码方式变更（已拍板）**：项目内 FFmpeg 只有音频解码器（`media3-ffmpeg.jar` 仅 `FfmpegAudioRenderer`；`libffmpegJNI.so` 无 `ffmpegCreateVideoDecoder` 符号），做不到「FFmpeg 视频软解」。改为 **MediaCodec 软件解码白名单**（只放行 `softwareOnly` 的 AOSP 解码器，硬解全部排除），达成「不抢主音频硬解」的目标；若 Z6X 无 AOSP 软解实现，封面会停在静态图（待实测）。

**P0-4 MV / 电台链路删除**
- 删文件：`ui/viewmodel/MusicVideoViewModel.kt`、`ui/screens/MusicVideoScreen.kt`、`media/MvCdnFallback.kt`、`media/MvDelayRenderers.kt`、`media/MvHttpRecovery.kt`、`media/VideoQueue.kt`、`luoxue/LxPocActivity.kt`、`data/datasource/RadioSource.kt`、`ui/viewmodel/RadioViewModel.kt`，以及 3 个 MV 单测（`MvCdnFallbackTest`/`MvHttpRecoveryTest`/`VideoEntryGuardTest`）与 `ScopedFixUiTest`（全是 MV 播放器用例）
- `AppShell`：去掉 `mvVm`、`videoActive/videoPlaying/videoReq`、视频宿主 SurfaceView 与 `MusicVideoScreen`、3 个视频相关 effect、退出弹窗里的 `clearVideoRequest/close`
- `MainActivity`：去掉 `mvVm` 与全部视频媒体键路由；`NavigationViewModel.isOnMusicVideo` 删除；`Screen.MusicVideo` 路由删除
- `ArtistDetailScreen` 音乐视频分区与 `ArtistVideoCard`、`ArtistDetailViewModel.musicVideos`、`CategoryScreen` 视频货架一并删除
- `AndroidManifest.xml` 移除 `LxPocActivity` 导出；`PlayerViewModel.playQueueItem` 遇到 MV 条目直接跳过（防止旧书签/旧队列把 MV 送进播放器）

**门禁**
- `gradlew :app:assembleDebug --offline` → BUILD SUCCESSFUL
- `gradlew :app:testDebugUnitTest --offline` → 646 项全绿（同步改写 `PageLayoutUiTest` 分类页视频用例、`DynamicPlayingIndicatorTest`、`MainActivityPlaybackLifecycleTest`、3 个 Web 单测构造参数、`PhoneLoginTest` 文案断言）
- 包内校验：`BeatAnalyzer/BeatProcessor/MusicVideoViewModel/MusicVideoScreen/LxPocActivity/VideoQueue/RadioSource` = 0 处；`LavaBackground`/`SongAudioSpectrum`/`MotionArtwork`/`videoLoopLoadControl` 仍在

**产物**：`E:\ai\amusic\.apk\veilmusic-w1-2.4.253-debug.apk`（39,364,941 B，签名 `550d861a…7645e`，与线上 2.4 同证书；版本仍为 2.4 / 253）

**已知行为变化（验收时请留意）**
1. 顶栏「正在播放」三根小条不再随鼓点冲击，只保留自走摆动
2. 手机配置页与开发者页的「节拍延迟」项已移除
3. MV/电台入口与播放器全部消失；旧队列里的 MV 条目会被跳过
4. 详情页动态封面仍不受「动态封面」开关控制（保持 2.4 行为）；播放页与卡片仍受控

#### 表外用户修复 · 设置页「账户与同步」（2026-11-21，用户单点提出）

- 去掉「账户与同步」折叠开关与「Apple Music 展示」总开关：打开设置页该区块即直接显示登录行
- 「手机扫码登录」改名 **「手机扫码登录 Apple Music」**（英文 `Sign in to Apple Music on your phone`）
- 副标题重写：未登录 →「用手机扫码授权，同步账号、商店区域与云端歌单；播放仍由本地音源提供」；已登录 →「已登录 · 扫码可换号；账号与云端歌单已同步」
- 状态行精简为：已登录（附账号/区域）/ 登录已失效 / 暂时连不上 / 未登录
- 展示方案此前默认关闭（关闭时不发任何网络请求），改为**用户主动扫码时才启用**（`startPhoneSignIn()` 内 `setEnabled(true)`），保持"不点不发请求"的隐私前提
- 改动文件：`ui/screens/DevMenuScreen.kt`（区块重排 + `SignInRow` 增加 `modifier` 参数承接 Tab 栏回焦）
- 门禁：`assembleDebug` 成功；单测全绿
- 产物：`E:\ai\amusic\.apk\veilmusic-w1-settings-debug.apk`（39,264,753 B，同一签名）

#### 波次2 · P0-5 / P0-6 / P0-7 / P1-3（2026-11-21 完成，待用户真机确认）

**P0-5 手机 Web 服务按需启停**
- `InAppWebServer`：`web_enabled` 默认改为 **false**（不再常驻）；新增引用计数 `acquire(scope, reason)` / `release(reason)`，最后一个引用释放即 `stop()`
- 持有方：设置页（`DevMenuViewModel` init/onCleared）、引导页（`OnboardingViewModel` init/onCleared）、资料库导入弹窗（`LibraryViewModel.acquirePhoneServer/releasePhoneServer` + `LibraryScreen.DisposableEffect`）
- `boot()` 只记 scope，不再自动监听；`setEnabled(on)` 关闭时仅在无引用时才真停
- 资料库导入弹窗的二维码地址改为弹窗期间轮询 `serverUrl()`（服务异步就绪）
- 单测同步：`PhoneServerIntegrationTest`/`PhoneEnglishCoverageTest` 改显式 `start(scope)`；引导页用例改为断言"创建 VM 即启服"

**P0-6 Compose 稳定性**
- `data/model/Song.kt`、`PlayerState` 标 `@Immutable`
- `NowPlayingScreen` 状态订阅改为投影：`state.map { it.copy(progressMs = 0L) }.distinctUntilChanged()`，进度单独走 `Flow<Long>`
- `rememberSmoothProgressMs` 由 `Long` 入参改为 `Flow<Long>`，父级不再读进度 → 播放页每秒不再整页重组
- 备注：`MiniPlayerCapsule` 仍需读 `state.progressMs` 做歌词行定位，本轮未拆（它体量小，且拆分需要改歌词渲染链路），列为后续项

**P0-7 背景帧预算双档**
- 新增 `BackgroundFrameRate`（`bg_fps`，30/60，默认 60），`FluidQuality.fps` 改为跟随它
- 设置页「外观与动效」在「背景风格」下新增 **「背景帧率 30 / 60 fps」** 手动双档
- 废弃低电量语义：`unifiedFluidQuality(quality, reduce)` 去掉 lowPower 分支；`KawarpBackground`/`MacBackground` 删除 `lowPower → POWER_SAVE` 自动降档
- 暂停停渲染留末帧：`KawarpTextureView` 暂停时画完最后一帧就停（不再 15fps 空转）；`MacTextureView` 的 vsync 回调在非播放时不再续帧；`IdlePlayerBackground` 由 `playing=true` 改为 `playing=false`
- 单测同步：`BackgroundOutputTest` / `V9PreferencesTest` 断言改为新语义，并覆盖 30/60 两档

**P1-3 Lazy 列表补 key**
- `LibraryScreen` 的添加歌单列表、`AddToDialog` 的歌单列表补 `key = { it.id }`（Category/Genre 原本已有 key）
- 顺带删除 `GenreScreen` 的 MV 货架（`VideoRow` + `videos` 分支），与 P0-4 保持一致

**门禁**
- `assembleDebug` 成功；`testDebugUnitTest` 646 项全绿

**产物**：`E:\ai\amusic\.apk\veilmusic-w2-2.4.253-debug.apk`（39,264,752 B，签名 `550d861a…7645e`）

**已知取舍**
1. 迷你胶囊仍按秒重组（未拆分歌词定位链路）
2. `lowPowerMode` 字段仍在 `PlayerState` 里（恒为 false），未删字段以免波及设置/壳层投影

#### 波次3 · P0-8 / P0-9 / P1-1 / P1-2 / P1-4 / P1-5（2026-11-21 完成，待用户真机确认）

**P1-2 设备档位单点判定**
- 新增 `util/DeviceTuning.kt`：`lowRam / memoryClass / totalRam` → `tier(0/1/2)`，并给出 `coilCacheMb`、`lyricOffscreen` 两项派生结论
- `RenderDiagnostics` 面板新增一行 `DeviceTuning: tier=… lowRam=… memoryClass=… coil=…MB`；`Application.onCreate` 同步打一份日志

**P1-1 Coil 内存缓存分档**
- 原来写死 64MB，改为 `DeviceTuning.coilCacheMb`：tier0(≈1GB 机) **24MB**、tier1 48MB、tier2 64MB

**P0-9 QuickJS 懒加载**
- `Application` 不再在启动时预热全部脚本（未用落雪源时**不会**出现 `lx-js-*` 线程）
- `LxPlayback.ensureReady` 只同步启动「上次用过的脚本」1 个（`lx_prefs.last_script`，缺失时取目录第一个），其余脚本放到 `lx-js-lazy` 后台线程按批加载
- `AudioResolver` 的引擎列表改为**提供者 lambda**，后台加载完成的引擎也能被解析器看到

**P0-8 应用级内存压力编排**
- 新增 `util/MemoryPressure.kt`（Sink 注册 + 广播）
- `Application.onTrimMemory`：先清 Coil 内存缓存，再广播给注册方
- `PlayerViewModel` sink → `clearPreparedNext()`；`LxPlayback` sink → 清解析缓存；`Application` 自带 sink → 背景帧率降到 30fps
- `MainActivity.onResume` → `MemoryPressure.relaxed()`，背景帧率恢复用户档位

**P1-4 preparedNext 策略反转**
- `canPrepareNextWithoutFade` 不再要求「同专辑连续 + 关交叉淡入」，默认为真正的下一首预建播放器（换切歌零停顿）；单曲循环 / MV 仍不预建
- 内存压力由 P0-8 的 sink 统一 `clearPreparedNext()`

**P1-5 歌词离屏合成联动**
- `KaraokeLyricLine` 的 `CompositingStrategy.Offscreen` 改为按 `DeviceTuning.lyricOffscreen` 决定：低端机（tier0）回退 `Auto`

**门禁**
- `assembleDebug` 成功；`testDebugUnitTest` 646 项全绿

**产物**：`E:\ai\amusic\.apk\veilmusic-w3-2.4.253-debug.apk`（39,264,781 B，签名 `550d861a…7645e`）

**已知取舍 / 待实测**
1. P0-9 只按「上次用过的脚本」单点预热，首次解析若命中其它脚本，会等后台加载完成（日志 `音源懒加载完成`）
2. P1-4 默认预建下一首会让同时在跑的播放器数量 +1，1GB 机上若仍吃紧，可在 onTrimMemory 之后再观察
3. P1-5 只作用于逐字歌词行；播放页其它离屏层（封面/待机层）未改

#### 表外用户修复 · 动态封面 / 逐字歌词 / 设置页两列（2026-11-21 第二轮）

**1. 动态封面回退上游实现（P0-3 的解码方式作废）**
- 实测：Z6X 上没有 AOSP 软件解码器，`SOFTWARE_ONLY_VIDEO_CODECS` 让解码器列表为空 → 封面彻底失效
- 已按用户要求退回 `lavande16/Apple-Music-For-Android-TV` 的做法：裸 ExoPlayer + **TextureView** + 默认渲染器（系统硬解），只关音轨、限 480p/1.5Mbps
- 同时去掉自定的 `videoLoopLoadControl`（上游不用），`PlaybackTuning` 里那套常量随之弃用

**2. 逐字歌词**
- 离屏合成改回**恒开** `CompositingStrategy.Offscreen`：波次3 的 tier0 回退 `Auto` 会让擦除遮罩失效（"效果容易丢失"就是这个）
- 删除「单个字放大/位移」：字符位置固定，只保留颜色擦除
- 当前行锚点加硬边界：活动行掉到视口 55% 以下时直接 `scrollBy` 落位，不再走 750ms 回弹（回弹在快歌下追不上 → 越跑越靠下、贴最下一行）

**3. 设置页改两列**
- 左列分区导航（账户与同步 / 外观与动效 / 播放与音频 / 通用 / 诊断），右列只渲染当前分区
- 每行高度从 84dp 收到 64dp（内边距 16→9），辅助文字 12→11sp，分组间距 12→8dp，保证每组一页放得下
- 各组原有的折叠开关默认展开（show_ui_settings / show_playback / show_general / show_dev = true）

**门禁**：`assembleDebug` 成功；单测 646 全绿
**产物**：`E:\ai\amusic\.apk\veilmusic-w4-settings-debug.apk`（39,281,160 B）

#### 表外用户修复 · 设置页九组两列（2026-11-21 第三轮）

- 左列样式与 `LibraryScreen.SectionSidebar` 对齐：208dp 宽、12dp 圆角、选中 `0x33FA233B`、焦点缩放 1.03、键盘模式焦点即切换、首项按上回 Tab
- 左列标签统一 **4 个字**：账户同步 / 外观主题 / 背景动效 / 封面动效 / 迷你胶囊 / 播放音质 / 歌词设置 / 通用设置 / 诊断工具
- 右侧**取消一切折叠组**：分组即页面，进入即完整列表；原「外观与动效」19 项拆成 4 组、原「播放与音频」11 项拆成 2 组
- 每组 ≤ 8 项（账户 1 / 外观 4 / 背景 5 / 封面 4 / 胶囊 5 / 音质 7 / 歌词 5 / 通用 6 / 诊断 5）
- 行高 64dp、分组间距 8dp，单组一屏可放完

**门禁**：`assembleDebug` 成功；单测 646 全绿
**产物**：`E:\ai\amusic\.apk\veilmusic-w6-settings9-debug.apk`（39,264,762 B）

#### 表外修复 · 设置左列对齐资料库（2026-11-21 第四轮）

上一版左列是"看着像"，没有对齐真正的 `LibraryScreen.SectionSidebar`。这次逐行照抄：
- 宽度：`208.dp.coerceAtMost((maxWidth - inset * 2) * 0.32f)`，外层 `Row` 统一 `padding(horizontal = inset)`
- 选中底色 **`0x33FA233B`（浅红）**，焦点态 `0x4DFA233B`，未选中透明
- 左侧 **3dp × 18dp 圆角红条**（`0xFFFA233B`），仅选中项显示
- 文字 **16sp**，选中 SemiBold + 主色，未选中 Medium + 灰；`maxLines = 1`、`softWrap = false`（四个字必定一行）
- 行内 `padding(horizontal = 14.dp, vertical = 13.dp)`、条与字间距 12dp，圆角 12dp、焦点缩放 1.03

**构建环境**：本轮出现 Kotlin 守护进程被系统杀掉（多个守护共占内存），改用 `--max-workers=2` + `-Dkotlin.compiler.execution.strategy=in-process` + `-Dorg.gradle.jvmargs=-Xmx2560m` 后稳定通过。

**门禁**：`assembleDebug` 成功
**产物**：`E:\ai\amusic\.apk\veilmusic-w7-sidebar-debug.apk`（39,281,138 B）

#### 表外修复 · 设置页收敛与默认值（2026-11-21 第五轮）

**账号区精简**
- 只显示三行：`Apple Music` 标题 / 登录情况（已登录·账号·区域 或 未登录/失效）/ `已同步 N 个歌单`
- 去掉歌单名标签云、提示长文案、加载提示；点行仍是扫码登录
- 新增一行 **「刷新同步歌单及推荐页」**：`AppleDisplayAuth.refresh()` + 通过 `data/AppRefresh` 广播给 `HomeViewModel` 重取首页推荐

**设置页收敛**
- 删除右侧顶部「设置 / Playback, display and device connections」标题与副标题，内容上提（top 96→28dp）
- 分组并为 8 组：**账号外观**（扫码 + 界面模式 + 定时切换 + 深色起止 + 顶栏显示歌名 + 迷你播放胶囊）/ 背景动效 / 封面动效 / 胶囊细节 / 播放音质 / 歌词设置 / 通用设置 / 诊断工具
- 「显示与设备连接」相关文案随副标题一起删除

**默认值与持久化**
- 动态封面默认 **开**（`PlayerState.motionArtworkEnabled = true`，prefs `motion_art` 默认 true）
- 封面圆角默认 **开**（原本即 true）
- 封面马赛克修复默认 **关**：`RenderCompat.Mode.AUTO` 不再自动命中，只有手动打开才生效
- 解码方式（硬解/软解）新增 `data/DecoderPreference`：prefs + `Download/VeilMusic/decoder.txt` 外部镜像，卸载重装后仍能恢复

**删除**
- 自动音画同步开关及其代码（`avSyncAuto` 状态、`setAvSyncAuto`、蓝牙自动补偿逻辑）
- 新手引导页：`ui/guide/GuideScreen.kt`、`GuideState.kt`、`GuideDemoData.kt` 整页删除；AppShell 的引导浮层接线、设置页「自动弹出新手引导 / 重新观看新手引导」两行一并移除

**门禁**：`assembleDebug` 成功；单测 646 全绿
**产物**：`E:\ai\amusic\.apk\veilmusic-w8-account-debug.apk`（39,231,996 B）

#### 表外修复 · 设置交互与背景收敛（2026-11-21 第六轮）

- **-+ 换成弹框选择**：新增 `ChoiceRow`（点开小卡片选值），已覆盖 背景帧率/脉动速度/深色开始-结束/胶囊高度-宽度/音质/解码方式/交叉淡入/歌词字号/歌词偏移/歌词字形。`缓存容量`、`界面语言` 两行仍保留步进器（与既有 UI 测试强耦合，下轮再改）。
- **胶囊细节并入账号外观**：删掉独立的「胶囊细节」组，高度/宽度/取色/进度线跟随「迷你播放胶囊」开关条件显示。
- **右侧首行对齐左列「账号外观」**：右列 top 回到 96dp，并用与左列标题同高的透明占位撑出对齐。
- **账号行**：标题恢复「点击扫码登录 Apple Music」，去掉行末的 `?` 占位符。
- **刷新反馈**：点「刷新同步歌单及推荐页」后 Toast 提示「已刷新」。
- **界面模式改开关**：「深色模式」用 Toggle，和「定时切换深浅色」一起移到胶囊之后；深色起止改为弹框选择。
- **流体背景删除**：状态里强制 `LAVA`，画质固定 `HIGH_QUALITY`；设置页移除「背景质量」「背景风格」两行。
- **背景律动 → 背景节拍律动**：文案改名，副标题保留麦克风授权说明（未授权时提示申请权限）。

**门禁**：`assembleDebug` 成功；单测 646 全绿
**产物**：`E:\ai\amusic\.apk\veilmusic-w10-picker-debug.apk`（39,231,992 B）

#### 表外修复 · 选择弹层改为贴行气泡（2026-11-21 第七轮）

- **撤回上一轮的错误**：流体背景**没有被删除**。恢复 `backgroundStyle` 从 prefs 读取与 setter 写回；
  设置页「背景风格（流体 / 熔岩）」也恢复。真正删除的只是 **「背景质量」这一项**，画质固定为高。
- **选择弹层改成播放页同款小气泡**：`ChoiceRow` 改用 `PlayerBubbleMenu`（230dp 白卡 + 底部小三角，
  锚定在被点的行上方），不再是居中的 Dialog。选项列表内可滚动，选中项红字+浅红底，遥控器焦点可上下移动。

**门禁**：`assembleDebug` 成功；单测 646 全绿
**产物**：`E:\ai\amusic\.apk\veilmusic-w11-bubble-debug.apk`（39,232,001 B）

#### 表外修复 · 回到步进器 + 顶栏律动（2026-11-21 第八轮）

- **选择气泡撤回**：弹层观感不如预期，全部设置项回退为 `- +` 步进器（覆盖 背景风格/帧率/脉动/深色起止/胶囊高宽/音质/解码/交叉淡入/歌词字号-偏移-字形）
- **步进按钮从圆形改成圆角矩形**：`46×30dp` + 9dp 圆角（原来是 36dp 圆）
- **顶栏「正在播放」旁三条律动幅度加大**：动画区间从 0.30–0.72 拉到 0.16–1.00 / 1.00–0.22 / 0.28–1.00，直接取值为高度比例

**门禁**：`assembleDebug` 成功；单测 646 全绿
**产物**：`E:\ai\amusic\.apk\veilmusic-w12-stepper-debug.apk`（39,231,996 B）

#### 表外修复 · 找回「背景节拍律动」（2026-11-21 第九轮）

- **问题**：第八轮把设置改成 7 组时，「背景节拍律动」整块（开关 + 麦克风授权 + 副标题状态机）在重排中被丢掉，
  只剩一行注释，所以熔岩背景拿不到麦克风频谱、不再随节拍呼吸。
- **修复**：从 `f709f56` 取回原始实现整块插回「背景动效」组（`Toggle(背景节拍律动)` + `rememberLauncherForActivityResult`
  + `microphonePermissionState` 状态文案），确认链路：开关 + RECORD_AUDIO 授权 + 播放中 → `audioSpectrum.setActive(true)`
  → `spectrumProvider` 传给 `LavaBackground`。

**门禁**：`assembleDebug` 成功；单测 646 全绿
**产物**：`E:\ai\amusic\.apk\veilmusic-w13-beat-debug.apk`（39,248,386 B）

---

## 2. 波次计划与交付节奏

> 每个波次完成后：本地构建 → **提供 APK 完整路径给用户真机测试** → 反馈后进入下一波。（每波次单独出包）

| 波次 | 内容 | 性质 | 风险 |
|------|------|------|------|
| **波次0** | P0-1 源码补齐 + `assembleDebug` 编译门禁跑通 | 阻断前置 | 决策点见 §3 |
| **波次1** | P0-2 Beat 死链路删除、P0-3 封面软解+小缓冲、P0-4 MV/电台/LxPoc 删除、P2-3 顺带 | 纯删码/换参，不改 UI 行为 | 低 |
| **波次2** | P0-5 Web 按需启停、P0-6 Compose 稳定性、P0-7 fps 设置档、P1-3 补 key | 生命周期 + 渲染 + UI 开关 | 中 |
| **波次3** | P0-8 onTrimMemory、P0-9 QuickJS 懒加载、P1-1 Coil 分档、P1-2 DeviceTuning、P1-4 preparedNext、P1-5 离屏层 | 内存编排 | 中 |
| **波次4** | P1-6 Visualizer 核查、P1-7 largeHeap 实验包 | 灰度评估 | 需用户拍板 |
| （P2 阶段） | P2-1/2-2 清洁度，P0/P1 全部稳定后再排 | 维护性 | 低 |

---

## 3. 开工前唯一决策点：P0-1 本地源码树也不完整

> **决议（2026-11-21，用户已确认）：走方案 A —— 原版合入，不做重建。**
> 来源为本机 `E:\ai\veil-latest\app`（已核实为 2.4 发布包的构建源），已全量镜像到 `E:\ai\amusic\app` 并提交至 `opt/z6x-v2.5`。方案 B（`REBUILT-PLACEHOLDER` 重建）**弃用**。

实测核查：工作区 `E:\ai\amusic\app` 与 GitHub tag 同样**缺关键文件但存在引用**：

- `NowPlayingScreen.kt:126` import `ui.background.LavaBackground` → **文件不存在**
- `NowPlayingScreen.kt:208-209` 引用 `media.AudioSpectrum` → **文件不存在**
- `PlayerViewModel.kt` 中无 `audioSpectrum / spectrumEnabled / backgroundStyle` 定义（被上述代码引用）；`BackgroundStylePreferences.LAVA` 被引用，定义文件未见
- `MacBackground` 调用参数（spectrum、pulseSpeedMultiplier）与定义签名不符

**两个方案（需用户选择）**：

- **方案 A（推荐）**：用户提供构建机上的完整源码目录（或 `LavaBackground.kt`、`AudioSpectrum.kt`、pref/成员定义文件），我来合入本地树恢复可编译。行为与 2.4 发布 APK 一致，后续优化结论全部可靠。
- **方案 B**：我按调用方契约**重建**这两个组件（LavaBackground = GL 流体+脉动背景新风格；AudioSpectrum = 系统 Visualizer 封装）。能立即恢复可编译并支撑全部 P0 优化（Beat 链路、背景帧预算、封面、Web、Compose 均不依赖其内部实现），但重建实现与用户构建机原版可能存在视觉/行为差异，最终版仍须以原版为准替换合入。

---

## 4. 本轮范围变更记录（相对上次分析输出）

| 变更 | 内容 |
|------|------|
| Web 服务（原 P0-4 删除项） | ✅ 改为"按需启停"（P0-5）：仅设置扫码登录弹窗/资料库扫码弹窗等场景激活，取消 `web_enabled` 默认开常驻；ServerSocket 复用 `media/PhoneServerSocket.kt` |
| 30/60fps（原 P0-6 自动档位） | ✅ 改为设置页显式双档（并入 P0-7）：先支持两个 fps 档手动切换实测，不预设默认结论；"省电档(POWER_SAVE)"语义废弃/改名 |
| MV/电台 | 维持删除（P0-4） |
| 动态封面 | 维持小缓冲+单实现，解码方式**锁定软解**（P0-3，含 FFmpeg 视频解码工厂注入与低端限帧） |
| preparedNext | 维持性能优先反转（P1-4） |
| 测试分工 | 固定：我构建出包给出 APK 路径，真机验证与决断由用户执行 |

---

## 5. 验收总指标（用户真机执行，Z6X 第四代）

```bash
adb shell dumpsys meminfo com.veacon.veilmusic | Select-String "TOTAL PSS|Native Heap|Dalvik Heap|EGL|Graphics"
adb shell dumpsys gfxinfo com.veacon.veilmusic framestats      # 95 分位帧时 <16.6ms
adb shell ps -T -p (pidof com.veacon.veilmusic)                # 无闲置 lx-js-* 线程
adb logcat | Select-String "WDOG|Clamp target GC heap"         # 看门狗触发≈0，GC 停顿下降
```

核心 KPI（按边界④）：滚动/切焦点无连续 jank、切歌无停顿、无 LMK 杀进程重载；PSS 为观察指标非首要指标。

---

*本文件由 Loomy 维护，开工后每次波次完成即更新状态列与完成备注。*
