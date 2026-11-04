package com.veil.music.util

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.veil.music.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException

/** A newer GitHub release than the running build, with its APK asset. */
data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

/** 一次检查更新的结论。比 `UpdateInfo?` 多区分了「仓库还没发版」这种情况。 */
sealed interface UpdateResult {
    /** 有比当前更新的版本。 */
    data class Newer(val info: UpdateInfo) : UpdateResult

    /** 已经是最新。 */
    data object UpToDate : UpdateResult

    /** 仓库存在，但一个 Release 都没发过（`releases/latest` 返回 404）。 */
    data object NoRelease : UpdateResult
}

/**
 * Self-updater that polls this repo's GitHub Releases. `check()` compares the latest
 * (non-prerelease) release's tag against the running versionName; if newer, `download()`
 * fetches the APK and `install()` hands it to the system package installer.
 */
object UpdateChecker {
    /**
     * 发布仓库，格式 `owner/repo`。
     *
     * 检查更新走 `api.github.com`；电视在局域网里通常**连不上 GitHub**，所以下面
     * [PROXIES] 里的加速前缀优先、直连兜底（实测 gh-proxy 能正常代理 API 请求）。
     */
    const val REPO = "veacon/veil.music"

    /** 是否已经填了仓库。UI 用它区分「没有更新」和「还没配置更新源」。 */
    val isConfigured: Boolean get() = REPO.isNotBlank()

    /**
     * 本次要依次尝试的前缀：**用户选中的那条排最前**，其余线路跟着兜底（含直连）。
     *
     * 不写死顺序的原因很实际 —— 这类免费加速站会时不时抽风，某一天 gh-proxy 挂了
     * 而 ghfast 好使。让用户能在设置里换一条，比发新版本来改常量快得多。
     * 选了「直连」也照样排最前，只是它前面不再有加速站，直接打 GitHub。
     */
    private fun candidates(preferred: GithubProxy?): List<String> =
        (listOfNotNull(preferred) + GithubProxy.entries.filter { it != preferred })
            .map { it.prefix }
            .distinct()

    private val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private val ALL = "https://api.github.com/repos/$REPO/releases?per_page=15"

    /**
     * 检查更新。
     * @param includeBeta when true, prereleases count too (the "beta" channel); otherwise
     *   only the stable `releases/latest` (which GitHub excludes prereleases from) is used.
     * @param preferred 用户选定的加速线路；null = 只用内置默认顺序。
     */
    suspend fun check(
        includeBeta: Boolean = false,
        preferred: GithubProxy? = null,
    ): Result<UpdateResult> = withContext(Dispatchers.IO) {
        if (REPO.isBlank()) return@withContext Result.success(UpdateResult.UpToDate)
        runCatching {
            val release = (if (includeBeta) newestRelease(preferred) else latestRelease(preferred))
                ?: return@runCatching UpdateResult.NoRelease
            parseRelease(release)?.let { UpdateResult.Newer(it) } ?: UpdateResult.UpToDate
        }.rethrowCancellation()
    }

    /** `releases/latest`；404（还没有任何 release）返回 null，不当成错误。 */
    private fun latestRelease(preferred: GithubProxy?): JSONObject? =
        runCatching { JSONObject(httpGet(LATEST, preferred)) }
            .onFailure { if (it !is HttpStatusException) throw it }
            .getOrNull()

    /** Newest non-draft release (GitHub returns them newest-first), or null if none. */
    private fun newestRelease(preferred: GithubProxy?): JSONObject? {
        val arr = runCatching { JSONArray(httpGet(ALL, preferred)) }.getOrNull() ?: return null
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (!r.optBoolean("draft")) return r
        }
        return null
    }

    private fun parseRelease(obj: JSONObject): UpdateInfo? {
        val tag = obj.optString("tag_name").ifEmpty { obj.optString("name") }
        val notes = obj.optString("body").trim()
        var apkUrl = ""
        var size = 0L
        // 发布包按 ABI 拆成两个（app-arm64-v8a… / app-armeabi-v7a…）。装错架构系统会直接拒装，
        // 所以先挑名字里带本机 ABI 的那个；没有（例如只发了合并包）才退回第一个 .apk。
        val abis = Build.SUPPORTED_ABIS.map { it.lowercase() }
        val apks = buildList {
            obj.optJSONArray("assets")?.let { assets ->
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) add(a)
                }
            }
        }
        val apk = apks.firstOrNull { a ->
            val name = a.optString("name").lowercase()
            abis.any { name.contains(it) }
        } ?: apks.firstOrNull()
        if (apk != null) {
            apkUrl = apk.optString("browser_download_url")
            size = apk.optLong("size")
        }
        // Defence-in-depth: only ever install over TLS.
        if (!apkUrl.startsWith("https://")) return null

        // The rolling "dev" prerelease (published by CI on every push to main) carries a non-numeric tag,
        // so the version compare can't rank it. Offer it whenever its commit differs from this build's —
        // that's what makes Beta updates track each new CI build instead of never firing.
        val isRolling = tag.equals("dev", ignoreCase = true) || parts(tag).isEmpty()
        if (isRolling) {
            val localSha = BuildConfig.GIT_SHA
            val remoteSha = Regex("commit\\s+([0-9a-fA-F]{7,40})").find(notes)?.groupValues?.get(1)
            // Can't tell them apart (source build, or no commit in the notes) → don't nag.
            if (localSha == "unknown" || remoteSha == null) return null
            val same = remoteSha.startsWith(localSha, true) || localSha.startsWith(remoteSha, true)
            if (same) return null
            return UpdateInfo("dev · ${remoteSha.take(7)}", notes, apkUrl, size)
        }

        if (!isNewer(tag, BuildConfig.VERSION_NAME)) return null
        return UpdateInfo(tag.trimStart('v', 'V'), notes, apkUrl, size)
    }

    /** Stream the APK into cache, reporting 0f..1f progress. */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        preferred: GithubProxy? = null,
        onProgress: (Float) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "update.apk")
            val conn = openFirstOk(info.apkUrl, preferred)
            // contentLengthLong 是 API 24；走响应头自己解析，老系统也能拿到总长度。
            val total = if (info.sizeBytes > 0) {
                info.sizeBytes
            } else {
                conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            }
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var n: Int
                    var lastPct = -1
                    while (input.read(buf).also { n = it } != -1) {
                        ensureActive() // stay cancellable through a long download
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = ((read * 100) / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                withContext(Dispatchers.Main) { onProgress(pct / 100f) }
                            }
                        }
                    }
                }
            }
            out
        }.rethrowCancellation()
    }

    /** Launch the system installer for a downloaded APK. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Keep structured concurrency intact: never bury a cancellation inside a Result. */
    private fun <T> Result<T>.rethrowCancellation(): Result<T> =
        onFailure { if (it is CancellationException) throw it }

    /** 明确的 HTTP 状态错误（4xx 是「确定结论」，不该再到别的镜像重试）。 */
    private class HttpStatusException(val code: Int) : Exception("HTTP $code")

    /**
     * 拉一个 GitHub API 地址：**用户选中的线路优先**，其余加速跟进，直连最后兜底。
     *
     * 电视连不上 GitHub，直连会一直超时，所以默认把镜像排前面；用户也可以主动选「直连」
     * 让它排到第一位。
     *
     * 404 只认「真·GitHub 的 404」（响应体是 `{"message":"Not Found"}`）—— 加速站自己
     * 的 404 往往只是「这条线路不支持这个接口」，把它当结论会误报成「仓库没发版」。
     */
    private fun httpGet(url: String, preferred: GithubProxy?): String {
        var last: Exception? = null
        var githubSaid404 = false
        for (target in candidates(preferred).map { it + url }) {
            try {
                val conn = open(target, "application/vnd.github+json")
                val code = conn.responseCode
                if (code in 200..299) {
                    return conn.inputStream.bufferedReader().use { it.readText() }
                }
                if (code == 404) {
                    val body = runCatching {
                        conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    }.getOrDefault("")
                    if (body.contains("\"message\"") || body.contains("Not Found")) githubSaid404 = true
                    last = HttpStatusException(404)
                    continue
                }
                last = HttpStatusException(code)
            } catch (e: Exception) {
                last = e
            }
        }
        if (githubSaid404) throw HttpStatusException(404)
        throw last ?: java.io.IOException("网络不可用")
    }

    /**
     * 打开一个「直连几乎必然失败」的 GitHub 地址（Release 里的 APK）。
     *
     * APK 有几十 MB，不能像 API 那样整包读进内存再判断，所以这里只把连接建好并确认
     * 状态码是 2xx，真正的流式读取仍然发生在 [download] 里。
     */
    private fun openFirstOk(url: String, preferred: GithubProxy?): HttpURLConnection {
        var last: Exception? = null
        for (target in candidates(preferred).map { it + url }) {
            try {
                val conn = open(target)
                if (conn.responseCode in 200..299) return conn
                last = HttpStatusException(conn.responseCode)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: java.io.IOException("所有线路都不可用")
    }

    private fun open(url: String, accept: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "veil-music")
            if (accept != null) setRequestProperty("Accept", accept)
        }

    /** Dotted-version integer parts after dropping a leading "v"; empty for a non-numeric tag like "dev". */
    private fun parts(s: String) = s.trim().trimStart('v', 'V')
        .split('.', '-', '+', '_').mapNotNull { it.toIntOrNull() }

    /** Numeric dotted-version compare after dropping a leading "v"; "1.1" > "1.0.0". */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }
}
