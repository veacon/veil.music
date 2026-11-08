# Moshi reflective models persisted in existing queues and content caches.
# Generated DTO adapters and Retrofit use the libraries' bundled consumer rules.
-keep class com.veacon.veilmusic.data.model.** { *; }

# 反射解析的 DTO 与本地资料库模型必须保名保字段。
# 不加这些 keep，R8 会把 `List<RecentPlay>` 的泛型元素退化成 LinkedHashTreeMap：
# 2.0 在真机上的启动闪退就是 `LinkedHashTreeMap cannot be cast to LocalLibraryStore$RecentPlay`
# —— recents 为空时不触发，一旦听过歌、启动读回非空最近播放就必崩。
-keep class com.veacon.veilmusic.data.network.** { *; }
-keep class com.veacon.veilmusic.data.local.LocalLibraryStore$* { *; }

-keep class com.veacon.veilmusic.ui.viewmodel.HomeSection { *; }
-keep class com.veacon.veilmusic.ui.screens.BrowseShelf { *; }

# Local FFmpeg jar has no bundled consumer rules; its JNI library resolves these names.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
