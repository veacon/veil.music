package com.veacon.veilmusic.data.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.veacon.veilmusic.BuildConfig
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

data class UpdateInfo(val version: String, val code: Int, val url: String, val size: Long, val sha256: String, val minSdk: Int, val notes: String)

/** 发布源约定：GitHub Releases。清单固定挂在 latest release 的 `download` 别名下，APK 与清单同源。 */
private const val LATEST_MANIFEST_PATH = "/releases/latest/download/update.json"
private const val APK_PATH_SEGMENT = "/releases/download/"

class AppUpdates(private val context: Context, private val manifestUrl: String = BuildConfig.UPDATE_BASE_URL + LATEST_MANIFEST_PATH) {
    private val client = updateHttpClient(manifestUrl)
    /**
     * 拉更新清单。GitHub 的 `latest/download` 在国内常直连超时，所以按
     * 「用户选的线路 → 其余加速线路 → 官方直连」依次尝试，第一个成功即用。
     *
     * 注意 [parse] 仍以官方 [manifestUrl] 校验 apkUrl 的主机/路径，且最终 APK 有
     * SHA-256 + 签名校验 —— 经由镜像取清单不会放宽任何信任边界。
     */
    suspend fun check(preferred: DownloadSource = DownloadSource.DIRECT): UpdateInfo = withContext(Dispatchers.IO) {
        val candidates = buildList {
            val first = preferred.takeIf { it != DownloadSource.DIRECT }
            if (first != null) add(first)
            DownloadSource.entries.filter { it != DownloadSource.DIRECT && it != first }.forEach { add(it) }
            add(DownloadSource.DIRECT)
        }
        var last: Exception? = null
        for (source in candidates) {
            try {
                return@withContext parse(fetchManifest(source.url(manifestUrl), source))
            } catch (e: Exception) {
                last = e
                android.util.Log.w("AppUpdates", "manifest via ${source.key} failed: ${e.message}")
            }
        }
        throw last ?: IOException("Update check failed")
    }

    private fun fetchManifest(url: String, source: DownloadSource): String {
        val http = if (source == DownloadSource.DIRECT) client else mirrorManifestClient()
        http.newCall(Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("Empty update response")
            if (body.contentLength() > 32768) throw IOException("Update response too large")
            val bytes = java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    if (bytes.size() + n > 32768) throw IOException("Update response too large")
                    bytes.write(buffer, 0, n)
                }
            }
            return bytes.toString("UTF-8")
        }
    }

    /** 镜像的重定向目标不固定，这里只用于取 32KB 以内的清单，短超时快速失败换下一条线路。 */
    private fun mirrorManifestClient(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true).followSslRedirects(true)
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    internal fun parse(raw: String): UpdateInfo {
        val j = JSONObject(raw)
        require(j.getString("channel") == "user")
        require(j.getString("packageName") == context.packageName)
        val url = j.getString("apkUrl").toHttpUrlOrNull() ?: error("Invalid APK URL")
        val manifest = manifestUrl.toHttpUrlOrNull() ?: error("Invalid update source")
        // 清单托管在 <repo>/releases/latest/download/update.json，APK 也必须落在同一个仓库的
        // releases/download/ 下 —— 既排除了别的主机，也排除了同主机上的任意路径。
        val repoPath = manifest.encodedPath.substringBefore(LATEST_MANIFEST_PATH)
        require(url.isHttps && url.host == manifest.host && url.port == 443 &&
            url.encodedPath.startsWith(repoPath + APK_PATH_SEGMENT) &&
            url.encodedPath.endsWith(".apk") &&
            url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && url.query == null)
        val result = UpdateInfo(j.getString("versionName"), j.getInt("versionCode"), url.toString(), j.getLong("sizeBytes"), j.getString("sha256").lowercase(), j.getInt("minSdk"), j.optString("notes", ""))
        require(result.version.length in 1..64 && result.code > 0 && result.size in 1..150_000_000 && result.sha256.matches(Regex("[0-9a-f]{64}")) && result.minSdk in 1..100 && result.notes.length <= 3000)
        return result
    }
    /**
     * 下载更新包，默认 [THREADS] 个并发分片。
     *
     * - 直连仍走 [client] 的严格重定向白名单；加速线路用独立客户端（镜像可能跳到自己的 CDN）。
     *   最终以 SHA-256 + APK 签名/版本校验兜底，换主机不降低安全性。
     * - 任一分片拿不到 206（服务端或镜像不支持 Range）就整体回退单线程。
     * - 加速线路整体失败再自动回退官方直连重试一次。
     */
    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit,
        source: DownloadSource = DownloadSource.DIRECT): File = withContext(Dispatchers.IO) {
        try {
            downloadOnce(info, source.url(info.url), onProgress, clientFor(source))
        } catch (e: Exception) {
            if (source == DownloadSource.DIRECT) throw e
            android.util.Log.w("AppUpdates", "mirror ${source.key} failed (${e.message}); falling back to direct")
            onProgress(0)
            downloadOnce(info, info.url, onProgress, clientFor(DownloadSource.DIRECT))
        }
    }

    private fun clientFor(source: DownloadSource): OkHttpClient {
        // OkHttp 默认每主机 5 个并发，8 个分片会被排队，等于没并发。
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = THREADS * 2
            maxRequestsPerHost = THREADS
        }
        if (source == DownloadSource.DIRECT) {
            // 保留 verifiedRedirects：直连只允许跳到 GitHub 自己的 CDN。
            return client.newBuilder().dispatcher(dispatcher)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
        }
        // 镜像的重定向目标不固定；安全由下载后的 SHA-256 + 签名校验保证。
        return OkHttpClient.Builder()
            .followRedirects(true).followSslRedirects(true)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .dispatcher(dispatcher)
            .build()
    }

    private suspend fun downloadOnce(info: UpdateInfo, url: String, onProgress: (Int) -> Unit,
        http: OkHttpClient, ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val part = File(dir, "download.part"); val dest = File(dir, "update.apk")
        part.delete(); dest.delete()
        try {
            // 预分配：每个分片用自己的 RandomAccessFile 独立 seek，写互不重叠的区间。
            java.io.RandomAccessFile(part, "rw").use { it.setLength(info.size) }
            if (!downloadChunked(info, url, onProgress, http, part)) {
                part.delete()
                downloadSingle(info, url, onProgress, http, part)
            }
            verifyChecksum(part, info)
            validateApk(part, info)
            if (!part.renameTo(dest)) throw IOException("Could not save APK")
            dest
        } catch (e: Exception) { part.delete(); throw e }
    }

    /** @return false 表示服务端不支持 Range，调用方应回退单线程。 */
    private suspend fun downloadChunked(info: UpdateInfo, url: String, onProgress: (Int) -> Unit,
        http: OkHttpClient, part: File): Boolean = coroutineScope {
        val ranges = chunkRanges(info.size, THREADS)
        val done = java.util.concurrent.atomic.AtomicLong(0)
        val jobs = ranges.map { range -> this.async(Dispatchers.IO) {
            val req = Request.Builder().url(url)
                .header("Range", "bytes=${range.first}-${range.last}").build()
            http.newCall(req).execute().use { resp ->
                if (resp.code != 206) throw RangeUnsupported()
                val body = resp.body ?: throw IOException("Empty chunk")
                java.io.RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(range.first)
                    val buf = ByteArray(32768)
                    var written = 0L
                    val expected = range.last - range.first + 1
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            if (written + n > expected) throw IOException("Chunk overflow")
                            raf.write(buf, 0, n); written += n
                            val total = done.addAndGet(n.toLong())
                            onProgress((100 * total / info.size).toInt().coerceIn(0, 100))
                        }
                    }
                    if (written != expected) throw IOException("Incomplete chunk ${range.first}")
                }
            }
        } }
        try {
            jobs.forEach { it.await() }
            true
        } catch (_: RangeUnsupported) { false }
    }

    private fun downloadSingle(info: UpdateInfo, url: String, onProgress: (Int) -> Unit,
        http: OkHttpClient, part: File) {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty download")
            var total = 0L
            body.byteStream().use { input -> part.outputStream().use { output ->
                val buf = ByteArray(32768)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    total += n; if (total > info.size) throw IOException("Download size mismatch")
                    output.write(buf, 0, n); onProgress((100 * total / info.size).toInt().coerceIn(0, 100))
                }
                output.fd.sync()
            } }
        }
    }

    private fun verifyChecksum(part: File, info: UpdateInfo) {
        if (part.length() != info.size) throw IOException("Download size mismatch")
        val md = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { input ->
            val buf = ByteArray(65536)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        val hash = md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (hash != info.sha256) throw IOException("Download checksum mismatch")
    }
    @Suppress("DEPRECATION")
    private fun validateApk(file: File, info: UpdateInfo) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = pm.getPackageInfo(context.packageName, flags)
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: throw IOException("Invalid APK")
        val code = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        if (candidate.packageName != context.packageName || candidate.versionName != info.version || code != info.code.toLong() || code <= BuildConfig.VERSION_CODE) throw IOException("APK version or package mismatch")
        @Suppress("DEPRECATION") fun certs(p: android.content.pm.PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else p.signatures
            return signatures.orEmpty().map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } }.toSet()
        }
        if (certs(installed).isEmpty() || certs(candidate) != certs(installed)) throw IOException("APK signing certificate mismatch")
    }
    fun install(file: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return true
    }
}

/** 并发分片数。GitHub release CDN 支持 Range；镜像不支持时会自动回退单线程。 */
private const val THREADS = 8

private class RangeUnsupported : IOException("Server does not support byte ranges")

/** 把 [size] 个字节切成最多 [parts] 段，尽量均匀；不足 parts 段时自动缩减。 */
internal fun chunkRanges(size: Long, parts: Int): List<LongRange> {
    require(size > 0 && parts > 0)
    val count = minOf(parts.toLong(), size).toInt()
    val base = size / count
    val extra = (size % count).toInt()
    var cursor = 0L
    return (0 until count).map { i ->
        val len = base + if (i < extra) 1 else 0
        val start = cursor
        cursor += len
        start..(start + len - 1)
    }
}
