# 音源实现细节（与 lx-music-mobile 的对应关系）

本文件说明「哪些规则是照搬 lx 的」，以便脚本作者与后续维护者对得上号。

## 1. 脚本协议（lx-music v4）

宿主提供 `assets/script/user-api-preload.js`，脚本是 IIFE，向 `lx.send('inited', { sources })`
上报能力：

```js
/* @name 示例音源
   @description 支持 kw / kg
   @version 1.0.0
   @author someone
   @homepage https://example.com */
lx.on('request', async ({ source, action, info }, { status, message, result }) => { ... })
```

- `action` 三件套：`musicUrl`（`info.type` 为音质、`info.musicInfo` 为老格式曲目）、
  `lyric`、`pic`。
- 网络请求走 `lx.request(url, { method, headers, body, timeout, binary })`，
  由 `ScriptHttpClient` 执行（默认 UA 与 lx 一致、响应自动 JSON 解析、支持取消）。
- 加密工具（`md5` / `aesEncrypt` / `rsaEncrypt` / base64）由 `CryptoBridge` 提供，
  底层是 `crypto/AES.java`、`crypto/RSA.java`。
- 初始化超时 10s、单次请求超时 15s（`JsScriptRuntime`）。

## 2. 音质级联

`Quality.orderedHighToLow` = `master > atmos_plus > atmos > hires > flac24bit > flac > 320k > 128k`。

`SourceManager.pickPlayQuality` / `qualityCascade` 的规则（对应 lx 的 `getPlayQuality` + 逐级降级）：

- 普通档（128k/320k/flac/flac24bit）：**脚本声明支持** 且 **搜索结果里列了该档** 才可用；
- 扩展档（hires/atmos/atmos_plus/master）：只要求脚本声明，因为目录元数据基本不上报这几档；
- 128k 永远是兜底；
- 某档失败就试下一档；如果失败原因是**脚本超时**，不再试更低档（超时的脚本不会因为音质低就回答）。

## 3. 换源（toggle source）

脚本与级联都失败时，`OtherSourceFinder.findMatches` 在**其他三个平台**并发搜
「歌名 + 歌手」，再按 lx `findMusic` 的判定排序：

1. 时长差 ≤ 5 秒（任一方为 0 时视为通过）；
2. 歌名归一化后互为子串；
3. 歌手归一化后互为子串（多歌手按 `、&;；/,，|` 拆分后排序比较）；
4. 专辑名相等作为加分项；
5. 版本后缀字符（live / remix / acoustic 等）必须一致，避免把现场版当录音室版。

命中后取前 5 个候选依次尝试，成功即返回，并在 UI 上提示「已换到 XX 源」。

## 4. 内置直连兜底

`BuiltInResolver` 只实现两家：

- 酷我：`antiserver.kuwo.cn/anti.s` 的 `convert_url3` / `convert_url`，按音质映射 `br`，
  遇到 DRM 标记继续降级；
- 网易云：`music.163.com/song/media/outer/url?id=...`（外链，128k）。

其余源的播放地址必须由脚本提供 —— 这是脚本生态存在的意义，也是合规边界。

## 5. 目录数据（首页 / 歌单 / 榜单）

| 数据 | 接口来源 | 文件 |
|---|---|---|
| 推荐歌单 / 分类歌单 | 各平台 `playlist` 类公开接口 | `source/catalog/SonglistCatalog.kt` |
| 榜单列表 / 榜单曲目 | 各平台 `rank` / `toplist` 接口 | `source/catalog/BoardCatalog.kt` |
| 搜索 | 各平台 `search` 接口（含签名） | `source/catalog/SearchCatalog.kt` |
| 热词 | 各平台热搜接口 | `source/catalog/HotSearch.kt` |

酷我的签名逻辑在 `source/catalog/KuwoSecret.kt`（用 QuickJS 跑平台自带的签名脚本）。

## 6. 歌词

优先级：脚本 `lyric` → 同平台直连（`BuiltInLyricResolver`）→ 跨平台按「歌名 + 歌手」搜同名。
解析支持 LRC / QRC / KRC / TTML；行级 LRC 会按标点权重（中文 1.0、逗号顿号 1.6、
句号问号 2.2、拉丁 0.55）估算逐字时间，供播放页做逐字高亮。

## 7. 已知脆弱点

- 平台接口随时会变（尤其 QQ / 酷狗的签名参数）。目录接口失效的典型表现是「歌单页空列表」，
  而不是崩溃；播放失效则表现为「无法获取播放地址」。
- 脚本超时是体验杀手：一次 15s 的等待比换源失败更糟，所以超时后不再试更低音质。
- 内置直连的酷我 `convert_url` 在部分曲目上返回 DRM 流，已被识别并继续降级。

