# Veil Music 2.4 · 极米 Z6X 第四代（1G 内存）优化文档

> 分析对象：`veacon/veil.music` v2.4 tag 源码（commit `21adb23`，2026-11-20 发布）
> 目标设备：极米 Z6X 第四代投影仪 · RAM 1GB · 1080p · Android TV
> 优化目标：①减少卡顿 ②代码清洁 ③关闭的选项不占内存

---

## 一、全局排查报告（先看结论）

卡顿与内存压力的根因按影响排序，共定位到 **3 个主要根因 + 1 个发布完整性风险**：

| # | 根因 | 类型 | 影响面 |
|---|------|------|--------|
| 1 | **BeatProcessor 音频线程 DSP 是死链路**——2.4 改用系统 Visualizer 采集频谱后，旧的节拍分析链路没有拆掉，仍在音频线程逐样本跑浮点 DSP，输出却无人消费 | 纯浪费（CPU+内存） | 播放全程，与开关无关 |
| 2 | **背景渲染无 30fps 档**：所有动画档位（省电/标准/高画质）都是 60fps 全速 GL 渲染，空闲播放页也强制 `playing=true` 全速跑 | 渲染过重 | 播放页 + 空闲页全程 |
| 3 | **内存常驻预算按 2G 电视盒子定标**：Coil 内存缓存固定 64MB + largeHeap + 双播放器 + 磁盘缓存 1.4GB | 内存超配 | 全程 + 存储 |
| ⚠ | **v2.4 GitHub tag 源码不完整**：`LavaBackground`、`AudioSpectrum` 等核心新文件缺失，tag 无法编译出发布 APK | 发布风险 | 2.5 发布前必须解决 |

一句话结论：**在 1GB 设备上，App 同时养着「两套 GL 背景渲染 + 一条无人消费的音频 DSP 死链路 + 64MB 图片内存缓存 + 双 ExoPlayer + 1.4GB 磁盘缓存预算」**。背景律动开不开，死链路都在跑；质量档选哪个，fps 都是 60。先做 P0 清单（第三节），不改任何功能表现即可明显减负。

---

## 二、设备画像：极米 Z6X 第四代

- **RAM 1GB**：系统 + 投影系统服务常驻后，单 App 可用堆通常只有 130~180MB（`largeHeap` 下上限更高，但物理内存更紧张，堆大只会招来 LMK 杀后台）。
- **GPU 较弱**：Mali 级 GPU 跑 60fps 全屏 fragment shader（Kawarp 需要 highp 精度）是持续的 GPU/CPU/发热负担；投影仪散热差，降频后卡顿会被放大。
- **存储偏小**：内部存储约 8GB，缓存预算需要克制。
- **交互特性**：遥控器操作为主，滚动/换页对帧率敏感的正是列表和焦点动画。

---

## 三、P0 优化项（收益最大，建议 2.5 直接做）

### P0-1 拆除 BeatProcessor 音频线程死链路 ⭐ 最大单点收益

**现状**（证据链）：
- `BeatAwareRenderersFactory.kt:66-70`：`gapConceal → gain → beatProcessor` 无条件注入每台播放器的音频链（交叉渐变播放器同样注入，见 `PlayerViewModel.kt:819`）。
- `BeatAnalyzer.kt:201-231`：`DSP_ENABLED = true` 时，BeatProcessor 在**音频输出线程**上对每个样本做 DC 阻隔 + 2 级低通 + 三频段滤波组（约 30+ 次浮点运算/样本）。44.1kHz 双声道 ≈ 每秒 8.8 万样本，即每秒 260 万+ 次浮点运算。
- `PlayerViewModel.kt:713 / 798 / 2120`：`beatAnalyzer.activate(p)` 无条件激活（只看 offload，不看任何 UI 开关）。
- **关键事实**：`beatAnalyzer.energy / bands` 两个 StateFlow 在全 App 的唯一消费者是 `NowPlayingScreen.kt:1558` 的 `DynamicBackground`——而该函数**没有任何调用点**（V84 上游的 Canvas 色块背景，2.4 换 Lava/Mac 背景后遗留的死代码）。代码注释自己也承认（`BeatAnalyzer.kt:200`）：这段 DSP 曾被怀疑在弱 Fire TV 上饿死音频解码器导致掉帧。
- 2.4 更新说明也已写明：背景律动改用**系统 Visualizer** 真实采集（`AudioSpectrum`，在 `NowPlayingScreen.kt:204-209` 管理，暂停/离开播放页会 `setActive(false)`，链路本身是健康的）。

**结论**：这条 DSP 链路输出的能量值全 App 无人使用，是 2.2 时代「beat 驱动背景」方案的遗留。它在音频线程上与解码器抢 CPU——这正是在弱设备上「播放时整体卡顿、切歌顿一下」的典型来源，且**与所有开关无关，24 小时白跑**。

**修复方案（三选一，按侵入度排序）**：
1. **最彻底（推荐）**：整体删除 `BeatAnalyzer.kt`、`BeatAwareRenderersFactory` 中的 `beatProcessor` 参数与注入、`PlayerViewModel` 中 `mainProc/cfProc/newProcessor()/activate()` 相关逻辑（约 5 处调用点），保留 `GapConcealProcessor`（修复解码丢帧静音，有用）与 `GainProcessor`（音量均衡，有用）。同时删除死代码 `DynamicBackground`（NowPlayingScreen.kt:1558-2515 区间中该函数及配套 `NowPlayingBackground` 枚举）。
2. **保守**：`BeatAwareRenderersFactory` 加 `beatEnabled: Boolean` 参数，`PlayerViewModel.buildExoPlayer()` 按设置传入；关闭时不 newProcessor、不注入（offload 已有同样的先例写法，见 `BeatAwareRenderersFactory.kt:53-56`）。但需注意：**开启背景律动的用户实际上也不需要这条链路**（背景走 Visualizer），所以方案 1 才是治本。
3. **最小改动**：`BeatAnalyzer.kt:366` 的 `DSP_ENABLED = true` 改为 `false`（保留代码结构，DSP 完全短路）。一行改动，立即可验证收益。

**预期收益**：音频线程每秒减少约 260 万次浮点运算 + 每次发布/重置 StateFlow 的对象分配（`floatArrayOf` 每 30ms 一次 × 2 条流）。低配设备上表现为：播放更稳、切歌更顺、待机 CPU 下降。

---

### P0-2 背景渲染降帧：补 30fps 档 + 空闲页别全速跑

**现状**：
- `FluidQuality.kt:7-12`：POWER_SAVE / STANDARD / HIGH_QUALITY 三档全部 `fps = 60`，只有像素上限不同（640/960/1280）。**没有 30fps 档**——「省电模式」其实一点都不省。
- `KawarpTextureView.kt:155-157`：播放中按 `quality.fps`（60）postDelayed 自调度；暂停时降到 `min(15, fps)`（这点做得好）。
- `MacTextureView.kt:144-153`：Choreographer vsync 驱动，**每帧都渲**（由 `swapInterval=1` 锁 vsync，60Hz 屏就是 60fps）。
- `IdlePlayerBackground.kt:19`：空闲播放页强制 `playing=true` → 没有歌也在 60fps 跑流体背景。

**修复方案**：
1. `FluidQuality` 增加每档 fps 语义：建议 `POWER_SAVE = 30fps / 512px`、`STANDARD = 30fps / 768px`、`HIGH_QUALITY = 60fps / 960px`。熔岩背景的视觉是慢速流体+脉动，30fps 肉眼几乎无差别，GPU 压力减半。
2. `IdlePlayerBackground` 把 `playing=true` 改为跟随真实状态（无歌=暂停语义），复用现有「暂停降 15fps」逻辑；或空闲页直接 `STATIC` 静帧 + 封面取色，效果几乎一样。
3. MacTextureView 的 vsync 渲染循环里接入 `BackgroundFrameCadence`（该类已存在且只被 Kawarp 的 90Hz 场景使用，见 `BackgroundFrameCadence.kt`）按档位 fps 节流，而不是每 vsync 都 swap。
4. Z6X 这类 1GB 设备，可在设备能力探测（`FluidQuality.fromName` 兜底处）把默认档位从 `STANDARD` 降为 `POWER_SAVE`（低 RAM 设备 `ActivityManager.isLowRamDevice() == true` 即可判定）。

**预期收益**：GPU/CPU 占用直接减半到减 75%；投影仪发热降低、系统降频减少，间接修复「越播越卡」。

---

### P0-3 内存预算按 1GB 设备重新定标

**现状与建议值**：

| 项目 | 当前值（按 2G 盒子定标） | Z6X 建议值 | 位置 |
|------|--------------------------|------------|------|
| Coil 内存缓存 | 固定 64MB | **32MB**（或 `maxSizePercent(0.15)` 按比例） | `AppleMusicApp.kt:31` |
| ExoPlayer 网络缓冲 | 8MB 硬上限（已经优化过，保持） | 保持 8MB | `PlaybackTuning.kt:30` |
| PlaybackCache 磁盘 | 256MB | **128MB** | `PlaybackCache.kt:60` |
| AppDiskCache 总预算 | 1GB（图片 150MB） | **512MB**（图片 100MB） | `AppDiskCache.kt:16-21` |
| `largeHeap` | 开启 | **评估移除**（见下） | `AndroidManifest.xml` |

补充说明：
- Coil 注释里「15% RAM ≈ 225MB too much」的决策对 2G 盒子是对的，但落到 1GB 设备上 64MB 固定值仍然偏大——1GB 机器上 32MB 缓存对 1080p 封面列表的命中率差异很小（磁盘缓存还在兜底）。
- `largeHeap="true"`：音乐 App 的最大瞬时分配是 1280px 背景纹理上传与封面解码，普通堆（1GB 设备通常 128~192MB memoryClass）足够。largeHeap 在 1GB 设备上的实际效果是**让系统更容易整体卡顿**（堆大 → 单 App 吃掉更多物理页 → LMK 更频繁杀后台/重新冷启动其他组件）。建议实测：去掉 largeHeap 后跑一轮全功能回归（首页滚动/唱片墙/播放页/全屏歌词），无 OOM 即可移除。
- 磁盘缓存合计当前约 **1.4GB**（256MB + 1GB），Z6X 存储本来就小，且 `cacheDir` 在系统空间紧张时会被整体清理，预算砍半不影响体验。

---

### P0-4 代码清洁：删除三块确认的死代码

全部经调用点核实（grep 全仓确认无外部引用）：

| 死代码 | 位置 | 说明 |
|--------|------|------|
| `DynamicBackground` + `NowPlayingBackground` + `rememberArtworkPalette`（若仅它使用） | `NowPlayingScreen.kt:1455/1558-2515` 区间 | V84 上游 Canvas 色块背景，2.4 被取代；约 1000 行 |
| 整个 `data/fonts` 包（5 文件） | `data/fonts/`（AppFonts / FontAssets / FontDownloadSession / FontFileStore / FontTls） | 苹方内置后，Noto CJK 运行时下载体系无任何调用点；含专用 OkHttpClient + `assets/certificates/` TLS 证书资产 |
| `BeatAnalyzer` 的 PCM 诊断捕获 | `BeatAnalyzer.kt:68-88`（`captureOut/startCapture/capture`）+ `PlayerViewModel.kt:1383` | 注释自述「DIAGNOSTIC，gap-repair 建好后移除」；随 P0-1 一起删 |
| `MOTION_ENABLED` 诊断常量 | `NowPlayingScreen.kt:156` | 「DIAGNOSTIC: off = CPU test」遗留，收敛为正式开关或删除 |

另外两处清洁项：
- `PlayerViewModel.kt`（3338 行）、`NowPlayingScreen.kt`（2515 行）建议在 2.5 后的维护周期里拆分（播放器构建/看门狗/设置读取各成文件；播放页拆背景层/歌词层/控制层）。只影响维护性，不影响运行时。
- `DevMenuScreen.kt`（80KB）与 `LibraryScreen.kt`（76KB）同理，P2。

---

## 四、「关闭的选项」资源释放核查表（专项）

逐个开关核查「关闭后是否还占资源」：

| 开关/功能 | 关闭后状态 | 结论 |
|-----------|------------|------|
| **背景律动**（spectrum） | UI 侧链路干净：`NowPlayingScreen.kt:204-206` 暂停/离开播放页 `setActive(false)`；权限拒绝不反复弹窗 ✅。但 `BeatProcessor` DSP 与开关无关、永远在跑 ❌（P0-1） | 部分泄漏 |
| **背景质量 = OFF/STATIC** | `KawarpBackground.kt:44-47` OFF 时不组合 TextureView、不建 EGL 线程，只画黑底 ✅；但音频 DSP 死链路仍在 ❌（P0-1） | 部分泄漏 |
| **背景质量 = POWER_SAVE** | 名为省电实则 60fps 全速 ❌（P0-2） | 语义不符 |
| **歌词缓动回弹** | 默认关闭，关闭即无动画 ✅ | 正常 |
| **逐字歌词** | 默认开启；关闭后整句点亮、无逐字动画 ✅。数据侧注意：关闭时歌词级联仍会请求 Apple 逐字源（见 2.2 说明「未登录跳过」，已登录时建议关闭后跳过 `syllable-lyrics` 请求，可省一次网络与解析） | 正常（小优化点） |
| **迷你播放胶囊** | 默认关闭；开启仅在有歌播放且非播放页展示（2.4 之前的约定）✅ | 正常 |
| **交叉渐变** | 关闭时若命中「同专辑连续曲目」场景，`prepareNextWithoutFade`（`PlayerViewModel.kt:772-794`）仍会创建第二台 ExoPlayer 常驻待命（解码器 + ≤8MB 缓冲 + 音频 sink + BeatProcessor），直到换歌 ⚠。1GB 设备上双播放器约多占 15~30MB。建议：preparedNext 延迟到曲目边界前 ~20s 才创建，或关闭渐变时完全不预 prepare（接受短暂换曲停顿） | 内存待命 |
| **Web 服务器**（手机配置/扫码登录） | `web_enabled` **默认 true**（`InAppWebServer.kt:63`）：两个 ServerSocket（8080/8081）+ 网络地址监听常驻。扫码登录（2.4 主推功能）依赖它，不能乱关；但可考虑「默认关、进设置页或登录页时自动 start」，对 1GB 设备更友好（产品决策，标 P1） | 可选收紧 |
| **动态封面**（motion artwork） | 有诊断开关 `MOTION_ENABLED`（`NowPlayingScreen.kt:156`）；设置关闭时不启视频解码器。视频解码器是内存大户，关闭路径已确认存在；建议随 P0-4 把诊断常量收敛后补一条「关闭后 release 解码器」的回归用例 | 基本正常 |
| **唱片墙** | 导航进/退销毁组合（`TabNavigation.kt:34-43` discardTransientVisits），背景 TextureView `onRelease → close()` 释放 EGL + 线程 ✅（`KawarpTextureView.kt:53-59`） | 正常 |
| **空闲播放页背景** | 无歌时也全速 60fps 渲染 ❌（P0-2） | 待修 |

---

## 五、P1 / P2 优化项

### P1（下一版本）
1. **发布完整性修复（最优先）**：把构建机上的完整源码推上 GitHub——当前 v2.4 tag 缺失：
   - `ui/background/LavaBackground.kt`、`LavaPulseSpeed`（被 `NowPlayingScreen.kt:126/212/482` 引用）
   - `media/AudioSpectrum.kt` 及 `PlayerViewModel` 的 `audioSpectrum/spectrumEnabled/spectrumPrefs/backgroundStyle/pulseSpeedIndex` 等属性
   - `AndroidManifest.xml` 的 `RECORD_AUDIO` 权限声明（2.4 更新说明称已补，tag 里没有）
   - `res/font/` 只有一个 `pingfang.otf`（10.8MB）+ `TvFontWeights.kt:20` 只注册 Normal 字重，与 release 说明「三字重内置、+38MB」不符
   - **风险**：tag 无法复现发布 APK；排查 2.4 线上问题时无法对照源码；单点依赖构建机。建议 2.5 发布前补齐推送，并在发布流程里加「CI 从 tag 干净编译」的校验步骤。
2. `AudioSpectrum`（Visualizer 封装）源码缺失无法核查释放逻辑——推齐源码后专项核查一次：确认关闭开关/退出播放页时调用 `Visualizer.release()`（Visualizer 是 native 资源，泄漏会拖死音频路由）。
3. 交叉渐变 preparedNext 的内存待命优化（见核查表）。
4. Web 服务器按需启动（见核查表，产品决策）。
5. 低 RAM 设备判定：`isLowRamDevice()` 为 true 时，Coil 缓存/背景默认档/Visualizer 缓冲统一降级，做成一套 `DeviceTuning` 单点。

### P2（维护周期）
1. 双套 GL 背景系统（Kawarp* 5 文件 + Mac* 5 文件，约 70KB 源码）功能重叠，可合并公共渲染层或退役一套，减包体与维护成本。
2. 巨型文件拆分（PlayerViewModel / NowPlayingScreen / DevMenuScreen / LibraryScreen）。
3. `IdleArtworkPool` 记录的是全部成功图片 URL（EventListener 里无条件 `record`），长期使用会累积；加个上限。

---

## 六、预期内存账本（Z6X，优化前后）

| 占用项 | 优化前（峰值估算） | P0 后 |
|--------|---------------------|-------|
| Coil 内存缓存 | ≤64MB | ≤32MB |
| 音频链处理器（BeatProcessor DSP + 附属） | 每播放器一套，双播放器时 ×2 | 仅剩 GapConceal/Gain/Sonic（DSP 删除） |
| 双 ExoPlayer 待命 | 15~30MB | P1 处理后可省 |
| GL 背景（纹理 + FBO + EGL 上下文 + 渲染线程） | 持续 60fps 渲染功耗 | 30fps/15fps + 空闲静帧 |
| 磁盘缓存预算 | ~1.4GB 存储 | ~640MB 存储 |

> 注：P0 全部完成不改变任何可见功能与视觉默认效果（背景律动、熔岩/流体、歌词、字体均保留），属于纯减负。

---

## 七、装机验证清单（优化后回归）

1. **性能对照**：播放一首歌，开发者选项 GPU 渲染条 / `adb shell dumpsys gfxinfo` 对比播放页帧耗时；`dumpsys cpuinfo` 对比播放时 CPU 占用（重点验证 P0-1 后音频线程负载下降）。
2. **内存对照**：`adb shell dumpsys meminfo <pkg>` 记录首页/播放页/唱片墙三场景 PSS 前后对比；重点看 Java Heap 与 Graphics 是否下降。
3. **功能回归**：背景律动开关（授权/拒绝/暂停/离开页面四种状态）、熔岩/流体切换、歌词缓动、逐字歌词、交叉渐变无缝换曲、扫码登录、全屏歌词、唱片墙进出、引导页焦点（2.3.5 老问题不复发）。
4. **存储回归**：`dumpsys diskstats` 或设置页查看缓存占用符合新预算。
5. **稳定性**：连续播放 1 小时无 watchdog 触发（播放看门狗日志无 RECOVER 记录）。

---

*分析基于 v2.4 tag 源码静态审查；源码副本在 `E:\ai\amusic\.workbuddy\veil.music-2.4\`。tag 缺失文件（LavaBackground/AudioSpectrum 等）部分依据调用方与 release 说明推断，已在文中标注。*
