package com.veacon.veilmusic.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 上游 Apple Music「令牌获取 + 登录」方案的**展示专用**实现。
 *
 * 上游方案（见 `key/auth.ts`、`key/docs_apple-music-api.md`）分两步：
 *  1. Bearer JWT：抓 `music.apple.com` 首页 → 找 `/assets/index*.js` → 从脚本里正则取 `eyJ…`，
 *     缓存 12 小时；
 *  2. 用户登录：从已登录的 Apple Music 网页会话取得 `media-user-token`（MUT），带
 *     `Authorization: Bearer …` + `Music-User-Token` 调 `/v1/me/storefront` 校验，再读取个性化展示数据。
 *
 * ── 数据边界（硬性）────────────────────────────────────────────────────────
 * 本模块**只读取个性化展示数据**：storefront、账号名、资料库歌单名称（纯文本，界面展示用）。
 *  - **播放数据一律不向 Apple Music 请求、也不回传**：不访问 webPlayback / HLS / stream /
 *    DRM / license / MZPlay 等端点；[isDisplayOnlyUrl] 是所有网络出口的强制白名单守卫，
 *    [displayRequest] 只构造 GET，绝不 POST 任何播放信息；
 *  - [State] 里没有任何 URL、直链、DRM 或曲目播放标识；展示条目只保留名称字符串；
 *  - token 写入独立的 `apple_display_prefs`；**登录成功后同步一份到 `MutPreferences`**
 *    (`apple_music_tv`)，因为用户明确选择「首页 + 资料库个性化」——「立即聆听」需要
 *    MUT 才能拿推荐，资料库需要它才能打开云端歌单。这些请求只走 Apple 的目录/推荐端点；
 *  - 不调用 `MusicRepository.syncMUTToServer / streamUrl`，不复用播放用的 OkHttp 客户端，
 *    不改动 `luoxue/` 与 `PlayerViewModel` 的播放分支；**播放直链仍完全走落雪音源**；
 *  - 开关默认关闭。关闭时不发起任何网络请求、不暴露任何账号状态。
 *
 * 进程单例：web 配置页、TV 设置页与测试共用同一份状态；[get] 在 Application 更换时重建，
 * 避免 Robolectric 测试间串数据。
 */
/** 资料库云端歌单，仅含目录标识与名称。 */
data class AppleLibraryPlaylist(val id: String, val name: String)

/** 容错解析持久化的云端歌单；脏数据直接丢弃，不影响其它展示字段。 */
internal fun parsePlaylists(raw: String): List<AppleLibraryPlaylist> = runCatching {
    val arr = JSONArray(raw)
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("id")
        val name = o.optString("name")
        AppleLibraryPlaylist(id, name).takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
    }
}.getOrDefault(emptyList())

class AppleDisplayAuth private constructor(private val appContext: Context) {

    data class State(
        val enabled: Boolean = false,
        val hasBearer: Boolean = false,
        val signedIn: Boolean = false,
        val storefront: String = "",
        val accountName: String = "",
        /** 个性化展示条目（资料库歌单名），纯文本，绝不含播放地址。 */
        val displayItems: List<String> = emptyList(),
        /** 资料库云端歌单（id + 名称），供「资料库」页打开歌单详情；id 不是播放地址。 */
        val playlists: List<AppleLibraryPlaylist> = emptyList(),
        val checking: Boolean = false,
        /** disabled | idle | ready | invalid | unavailable | signed_out */
        val status: String = "disabled",
    )

    private val prefs = appContext.getSharedPreferences("apple_display_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** 展示专用的独立客户端：不带任何播放拦截器，也不共享连接池。 */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var bearerCache: String = ""
    @Volatile private var bearerCacheAt: Long = 0L

    private val _state = MutableStateFlow(cachedState())
    val state: StateFlow<State> = _state

    fun isEnabled(): Boolean = prefs.getBoolean("enabled", false)
    fun hasToken(): Boolean = mut().isNotEmpty()

    private fun mut(): String = prefs.getString("mut", "") ?: ""
    private fun bearer(): String = prefs.getString("bearer", "") ?: ""

    private fun displayItems(): List<String> = runCatching {
        val arr = JSONArray(prefs.getString("display_items", "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }.getOrDefault(emptyList())

    private fun playlists(): List<AppleLibraryPlaylist> =
        parsePlaylists(prefs.getString("playlists", "[]") ?: "[]")

    private fun cachedState(): State {
        if (!isEnabled()) return State()
        val lists = playlists()
        return State(
            enabled = true,
            hasBearer = bearer().isNotEmpty(),
            signedIn = mut().isNotEmpty(),
            storefront = prefs.getString("storefront", "") ?: "",
            accountName = prefs.getString("account_name", "") ?: "",
            displayItems = lists.map { it.name }.ifEmpty { displayItems() },
            playlists = lists,
            status = if (mut().isNotEmpty()) "ready" else "idle",
        )
    }

    /** 开关：关闭即清空 UI 状态并取消在途请求；开启后才会抓取 Bearer / 校验已有 token。 */
    fun setEnabled(on: Boolean) {
        prefs.edit { putBoolean("enabled", on) }
        if (!on) {
            job?.cancel()
            job = null
            _state.value = State()
            return
        }
        _state.value = cachedState().copy(enabled = true)
        refresh()
    }

    /** 只在开关开启时刷新；关闭时是纯 no-op，不发一个字节的网络请求。 */
    fun refresh() {
        if (!isEnabled()) return
        job?.cancel()
        _state.update { it.copy(enabled = true, checking = true) }
        job = scope.launch {
            val token = mut()
            val dev = ensureBearer()
            _state.update { it.copy(hasBearer = dev.isNotEmpty()) }
            if (dev.isEmpty()) {
                _state.update { it.copy(checking = false, status = "unavailable") }
                return@launch
            }
            if (token.isEmpty()) {
                _state.update { it.copy(checking = false, signedIn = false, status = "idle") }
                return@launch
            }
            verifyAndLoad(token, dev)
        }
    }

    /**
     * 登录：校验 MUT 并把展示信息落盘。返回 disabled / invalid / unavailable / ok。
     * 同步返回是为了让 `:8080` 的 POST 处理沿用现有 runBlocking 风格。
     */
    fun signIn(raw: String): String {
        if (!isEnabled()) return "disabled"
        val token = raw.trim()
        if (token.length !in 20..8192 || token.any { it.isWhitespace() || it.code < 33 || it.code > 126 }) {
            _state.update { it.copy(status = "invalid", checking = false) }
            return "invalid"
        }
        return runBlocking {
            _state.update { it.copy(checking = true) }
            val dev = ensureBearer()
            _state.update { it.copy(hasBearer = dev.isNotEmpty()) }
            if (dev.isEmpty()) {
                _state.update { it.copy(checking = false, status = "unavailable") }
                "unavailable"
            } else {
                val outcome = verifyAndLoad(token, dev)
                if (outcome == "ok") prefs.edit { putString("mut", token) }
                outcome
            }
        }
    }

    /** 退出登录：清展示数据，同时清主账号态，首页/资料库随即回到未登录内容。 */
    fun signOut() {
        job?.cancel()
        prefs.edit { remove("mut"); remove("storefront"); remove("account_name"); remove("display_items"); remove("playlists") }
        MutPreferences(appContext).setMUT("")
        _state.value = State(
            enabled = isEnabled(),
            hasBearer = _state.value.hasBearer,
            signedIn = false,
            checking = false,
            status = "signed_out",
        )
    }

    /** 上游第 1 步：Bearer JWT 抓取 + 12h 缓存。任何失败都只影响展示状态。 */
    private suspend fun ensureBearer(): String = withContext(Dispatchers.IO) {
        val cached = bearerCache.ifBlank { bearer() }
        val at = if (bearerCache.isNotBlank()) bearerCacheAt else prefs.getLong("bearer_at", 0L)
        if (cached.isNotBlank() && System.currentTimeMillis() - at < 12 * 60 * 60 * 1000L) return@withContext cached
        val scraped = scrapeBearer()
        if (scraped.isNullOrBlank()) cached else {
            bearerCache = scraped
            bearerCacheAt = System.currentTimeMillis()
            prefs.edit { putString("bearer", scraped); putLong("bearer_at", bearerCacheAt) }
            scraped
        }
    }

    private fun scrapeBearer(): String? {
        val home = displayRequest("https://music.apple.com/") ?: return null
        val html = runCatching {
            http.newCall(home).execute().use { it.body?.string().orEmpty() }
        }.getOrNull() ?: return null
        val script = Regex("""crossorigin src="(/assets/index[^"]+\.js)"""")
            .find(html)?.groupValues?.get(1) ?: return null
        val asset = displayRequest("https://music.apple.com$script") ?: return null
        val js = runCatching {
            http.newCall(asset).execute().use { it.body?.string().orEmpty() }
        }.getOrNull() ?: return null
        return Regex("""(eyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]*)""").find(js)?.value
    }

    /** 上游第 2 步：`/v1/me/storefront` 校验 + 只读个性化展示字段。 */
    private suspend fun verifyAndLoad(token: String, dev: String): String = withContext(Dispatchers.IO) {
        val storefrontReq = displayRequest(
            "https://api.music.apple.com/v1/me/storefront", token, dev)
            ?: return@withContext fail("unavailable")
        val response = runCatching { http.newCall(storefrontReq).execute() }.getOrNull()
            ?: return@withContext fail("unavailable")
        val (code, body) = response.use { it.code to it.body?.string().orEmpty() }
        when {
            code == 403 -> return@withContext fail("invalid")
            code !in 200..299 -> return@withContext fail("unavailable")
        }
        val storefront = runCatching {
            JSONObject(body).optJSONArray("data")?.optJSONObject(0)?.optString("id").orEmpty()
        }.getOrDefault("")
        // 账号名与资料库歌单只是展示/目录数据；失败不影响登录成功的判定。
        val accountName = accountName(token, dev)
        val lists = loadPlaylists(token, dev)
        prefs.edit {
            putString("storefront", storefront)
            putString("account_name", accountName)
            putString("display_items", JSONArray().apply { lists.forEach { put(it.name) } }.toString())
            putString("playlists", JSONArray().apply {
                lists.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
            }.toString())
        }
        // 用户选择「首页 + 资料库个性化」：登录态同步进主账号存储，让「立即聆听」走
        // 个性化推荐、资料库能打开云端歌单。播放直链仍由落雪音源解析，不向 Apple 请求播放数据。
        MutPreferences(appContext).setMUT(token)
        _state.value = State(
            enabled = true,
            hasBearer = true,
            signedIn = true,
            storefront = storefront,
            accountName = accountName,
            displayItems = lists.map { it.name },
            playlists = lists,
            checking = false,
            status = "ready",
        )
        "ok"
    }

    private fun fail(status: String): String {
        _state.update { it.copy(checking = false, status = status) }
        return status
    }

    private fun accountName(token: String, dev: String): String {
        val req = displayRequest(
            "https://api.music.apple.com/v1/me/account?meta=subscription", token, dev) ?: return ""
        return runCatching {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) "" else JSONObject(r.body?.string().orEmpty())
                    .optJSONArray("data")?.optJSONObject(0)?.optJSONObject("attributes")
                    ?.optString("name").orEmpty()
            }
        }.getOrDefault("")
    }

    /** 资料库云端歌单：只取 id + name（目录数据），不取任何播放字段。 */
    private fun loadPlaylists(token: String, dev: String): List<AppleLibraryPlaylist> {
        val req = displayRequest(
            "https://api.music.apple.com/v1/me/library/playlists?limit=6", token, dev) ?: return emptyList()
        return runCatching {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) emptyList() else {
                    val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("data")
                        ?: return@use emptyList<AppleLibraryPlaylist>()
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val id = o.optString("id")
                        val name = o.optJSONObject("attributes")?.optString("name").orEmpty()
                        AppleLibraryPlaylist(id, name).takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
                    }.take(6)
                }
            }
        }.getOrDefault(emptyList())
    }

    /** 所有网络出口都必须经过这里：白名单 + 只读 GET，播放数据与播放端点在此被挡住。 */
    private fun displayRequest(url: String, token: String = "", dev: String = ""): Request? {
        if (!isDisplayOnlyUrl(url)) return null
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        if (dev.isNotBlank()) builder.header("Authorization", "Bearer $dev")
        if (token.isNotBlank()) {
            builder.header("Music-User-Token", token)
                .header("Media-User-Token", token)
                .header("Origin", "https://music.apple.com")
        }
        return builder.get().build()
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"

        /** 只允许「登录校验 + 个性化展示」端点；播放相关端点一概拒绝。 */
        private val DISPLAY_PATHS = listOf(
            "/v1/me/storefront", "/v1/me/account", "/v1/me/social/profile",
            "/v1/me/library/playlists", "/v1/me/library/recently-added", "/v1/me/recommendations",
        )
        private val PLAYBACK_MARKERS = listOf(
            "webplayback", "mzplay", "playbackinfo", "playback-info",
            "hls", "stream", "drm", "license", "widevine",
        )

        /** 公开给测试与守卫：任何播放数据 URL 都必须返回 false。 */
        fun isDisplayOnlyUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.US)
            if (PLAYBACK_MARKERS.any { lower.contains(it) }) return false
            val path = lower.substringBefore('?')
            return when {
                lower.startsWith("https://music.apple.com/") &&
                    (path == "https://music.apple.com/" || path.contains("/assets/index")) -> true
                lower.startsWith("https://api.music.apple.com/") &&
                    DISPLAY_PATHS.any { path.endsWith(it) } -> true
                else -> false
            }
        }

        @Volatile private var instance: AppleDisplayAuth? = null

        @Synchronized fun get(context: Context): AppleDisplayAuth {
            val app = context.applicationContext
            val current = instance
            return if (current == null || current.appContext !== app) {
                AppleDisplayAuth(app).also { instance = it }
            } else current
        }
    }
}
