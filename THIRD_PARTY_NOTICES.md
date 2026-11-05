# Third-Party Notices

Veil Music (薄音) is licensed under **GPL-3.0** (see [LICENSE](LICENSE)), inherited
from its upstream [JObersi10/apple-music-tv](https://github.com/JObersi10/apple-music-tv).
It uses the third-party projects listed below. Each retains its own license; those
licenses apply to those components, not to the rest of this project.

## Adapted code

- **lx-music-mobile** — Apache-2.0 — https://github.com/lyswhut/lx-music-mobile
  Behavioural reference for the source-resolution flow: the quality cascade
  (`master → atmos_plus → atmos → hires → flac24bit → flac → 320k → 128k`),
  cross-source fallback matching (`findMusic`: normalized title/artist containment,
  duration delta ≤ 5 s, version-suffix equality), and the v4 user-script protocol
  (`lx.on('request')`, `lx.send('inited', {sources})`, `musicUrl` / `lyric` / `pic`
  actions). No source code is copied verbatim; the Kotlin implementation in
  `source/SourceManager.kt`, `source/OtherSourceFinder.kt`, and
  `assets/script/user-api-preload.js` reimplements the same contract.
- **apple-music-tv** — GPL-3.0 — https://github.com/JObersi10/apple-music-tv
  The Compose for TV shell, playback engine, PiP handling, remote-key routing,
  beat-aware audio processor chain, and update checker originate here. All Apple
  Music-specific code (Widevine DRM, `AppleDirectClient`, the Bun proxy server,
  Apple catalog clients) has been removed from this fork.

## Runtime dependencies

- **AndroidX / Jetpack Compose for TV** — Apache-2.0 — https://developer.android.com/jetpack
  UI toolkit (`androidx.tv:tv-material:1.0.0`) for the Android / Fire TV app.
- **Media3 (ExoPlayer) 1.4.1** — Apache-2.0 — https://developer.android.com/media/media3
  Playback stack (`media3-exoplayer`, `media3-exoplayer-hls`, `media3-session`,
  `media3-ui`).
- **Hilt / Dagger 2.56** — Apache-2.0 — https://dagger.dev/hilt
  Dependency injection.
- **OkHttp** — Apache-2.0 — https://square.github.io/okhttp
  HTTP client for catalog and script-bridge requests.
- **kotlinx.serialization 1.7.3** — Apache-2.0 — https://github.com/Kotlin/kotlinx.serialization
  JSON persistence for scripts, libraries, and track registry.
- **Coil 2.7.0** — Apache-2.0 — https://coil-kt.github.io/coil
  Artwork loading.
- **QuickJS-Android (`wang.harlon.quickjs:wrapper-android:3.2.3`)** — MIT — https://github.com/HarlonWang/quickjs-android
  JavaScript engine hosting the lx-music v4 user scripts. Bundles **QuickJS** by
  Fabrice Bellard and Charlie Gordon — MIT — https://bellard.org/quickjs
- **kotlinx.coroutines** — Apache-2.0 — https://github.com/Kotlin/kotlinx.coroutines
  Structured concurrency.
QR codes for the on-device management page (`util/QrCode.kt`) are produced by a
hand-rolled byte-mode encoder in this repository — no third-party QR library is
linked in.

## Data sources

Veil Music ships **no** music catalog, no API keys, and no authorization tokens. All
catalog metadata and audio URLs come from whatever user scripts the user imports, or
from the built-in direct resolvers, which talk to the platforms' public web endpoints:

- **酷我音乐 (Kuwo)** — https://www.kuwo.cn
- **网易云音乐 (NetEase Cloud Music)** — https://music.163.com
- **酷狗音乐 (Kugou)** — https://www.kugou.com
- **QQ 音乐 (Tencent QQ Music)** — https://y.qq.com
- **咪咕音乐 (Migu)** — https://music.migu.cn

This project is not affiliated with, endorsed by, or sponsored by any of these
platforms. Users are responsible for complying with the terms of service of the
platforms they access and with the copyright laws of their jurisdiction. Audio URLs are resolved through user-imported scripts or the platform public
endpoints above. User scripts can be disabled or removed at any time from Settings
or the on-device management page.

- **LRCLIB** — public domain / CC0 — https://lrclib.net
  Optional line-synced lyrics fallback.
