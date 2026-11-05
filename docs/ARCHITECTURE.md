# 架构与模块职责

一屏看懂「谁负责什么」，以及为什么这么分。

> 平台基线（v1.1）：**Android 8.0（API 26）+ 64 位 `arm64-v8a`**。
> 已明确放弃 32 位架构与 Android 8.0 以下系统，架构图中不含任何低版本兼容层。

```
┌──────────────────────────────────────────────────────────────┐
│ UI        Discover / Collection / Search / Library / NP / Settings
├──────────────────────────────────────────────────────────────┤
│ 状态      HomeStore · DiscoverViewModel · PlayerViewModel · SettingsViewModel
├──────────────────────────────────────────────────────────────┤
│ 映射      LxMapper（Track↔Song / SonglistInfo↔Album）· TrackRegistry（id→Track）
├──────────────────────────────────────────────────────────────┤
│ 仓储      LxRepository（唯一数据入口）· LibraryStore（本地歌单）
├──────────────────────────────────────────────────────────────┤
│ 解析      SourceManager（脚本 → 换源 → 兜底）· PlaybackResolver（URL 缓存/单飞）
├──────────────────────────────────────────────────────────────┤
│ 出口      QuickJS 脚本        ·  平台目录直连（kw/wy/kg/tx）  ·  平台直连兜底（kw/wy）
└──────────────────────────────────────────────────────────────┘
```

## 各层约定

**UI 层** 只认 `Song` / `Album`，不知道数据来自哪个平台。卡片点击一律用 `Album.id` 打开
`CollectionScreen`，id 的前缀由 `LxIds` 编码。

**状态层**
- `HomeStore`：首页聚合 + 切源取消旧请求 + 按源内存缓存。**不放在 ViewModel 里**，因为切源时
  必须能被下一个源打断；ViewModel 会随导航销毁，中断语义不可靠。
- `PlayerViewModel`：队列、进度轮询、歌词、睡眠定时、URL 解析的调度。播放路径只有一条 ——
  `PlaybackResolver.resolve(track)` 拿 URL，然后 `setMediaItem` 播放。

**映射层**
- `LxMapper`：`Track → Song`（列表用）与 `Song → Track`（注册表缺项时兜底）。
- `TrackRegistry`：`Song.id` 到完整 `Track` 的映射并落盘。lx 脚本要求把整个 `musicInfo`
  回传（酷狗要 `hash`、QQ 要 `strMediaMid`），只留一个 id 是播不了的。

**仓储层**
- `LxRepository`：歌单广场、榜单、搜索、热词、歌词的统一入口。**不做缓存**，缓存交给上层，
  这样「切源要不要清缓存」是一个明确的决定而不是隐式行为。
- `LibraryStore`：本机曲库，JSON 落盘，无账号。

**解析层**
- `SourceManager`：`resolveMusicURL(track, quality, allowToggleSource)`。
  1. 找所有声明支持该源 + `musicUrl` 的脚本，逐个尝试；
  2. 每个脚本内按 `质量级联` 从期望音质一路降级到 128k；
  3. 脚本都失败 → `OtherSourceFinder` 搜同名曲目换源；
  4. 还失败 → 内置直连（酷我 / 网易云）；
  5. 给出可读的中文原因（「没有脚本提供酷狗源」/「这首无法获取播放地址」）。
- `PlaybackResolver`：同一首歌并发解析只打一次（single-flight）、10 分钟 TTL、
  播放出错时 `invalidate()` 强制重解析。

## 线程模型

- 所有网络与脚本调用都发生在 `Dispatchers.IO` 或脚本自己的单线程 executor 上；
- QuickJS 是单线程的，`JsScriptRuntime` 把每次上下文交互都丢进同一个 dispatcher；
- `HomeStore` 在 `Dispatchers.Main` 上收结果，但请求体在 `IO`；
- 播放进度轮询 200ms 一次，但只在每 ~900ms 推一次 state（UI 侧按帧插值）。

## 状态与恢复

- 队列 + 位置写 `player_state` SharedPreferences（切歌、暂停、`onCleared` 时）；
- `TrackRegistry` 异步合并写入，1.5s 去抖，最多 800 首；
- 冷启动 `restoreState()` 只恢复队列与进度，**不自动出声**，等用户按播放。
