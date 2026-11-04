package com.veil.music.media

import android.content.Context
import com.veil.music.data.VeilPreferences
import com.veil.music.data.model.Quality
import com.veil.music.data.model.SourceID
import com.veil.music.data.repository.LxRepository
import com.veil.music.data.store.ScriptStore
import com.veil.music.source.catalog.HotSearchColumn
import com.veil.music.source.js.GithubMirror
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 手机端管理页（默认 **8666**），完整复刻上游项目（upstream）的「手机助手」：手机通过局域网
 * 打开这个网页，把遥控器上做不了的输入推给电视。
 *
 * 页面结构（与上游项目逐页对齐）：
 *   GET  /                首页导航卡片
 *   GET  /search          搜索歌曲（含 4 平台热搜词，点词直接推给电视）
 *   POST /api/search      提交关键词 → [searchRequests]
 *   GET  /lyric           搜索歌词（实时查询，结果直接渲染在手机上）
 *   GET  /playlist        添加歌单（手机输入法起名，避免 TV 端输入）
 *   POST /api/playlist    提交歌单名 → [playlistRequests]
 *   GET  /songlist        推送歌单链接（粘分享链接或纯 ID）
 *   POST /api/songlist    提交链接 → [songlistRequests]
 *   GET  /import          提交音源（URL + 文本 + .js 文件上传）
 *   POST /api/script-url  脚本 URL → 抓取后导入（GitHub 链接走加速前缀）
 *   POST /api/script-text 脚本文本 → 导入
 *   POST /api/script-file 脚本 .js 文件（multipart）→ 导入
 *   GET  /scripts         已装音源管理（启用 / 停用 / 删除）
 *   POST /api/toggle      启用 / 停用脚本
 *   POST /api/delete      删除脚本
 *   GET  /settings        数据源 / 音质设置
 *   POST /api/prefs       提交偏好
 *   GET  /debug           下载调试文件（设备信息 + 设置 + 脚本 + 日志 + 崩溃）
 *   GET  /logs            解析日志
 *   GET  /api/hotsearch   热搜 JSON
 *   GET  /api/status      状态 JSON
 *
 * 端口说明：固定 8080 会被电视上其它调试工具（如 Android Debug Database，默认也占 8080）
 * 抢走 —— 那时手机打开的是别人的页面，标题显示「Android Debug Database」。
 * 所以默认用 8666，并在被占用时依次尝试 [PORTS] 里的备用端口。
 *
 * 只监听局域网，没有任何鉴权 —— 与上游一致（LAN-only, 不带敏感凭据）。
 */
@Singleton
class InAppWebServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scripts: ScriptStore,
    private val prefs: VeilPreferences,
    private val repo: LxRepository,
    private val http: OkHttpClient,
) {

    private val wsPrefs = context.getSharedPreferences("webserver_prefs", Context.MODE_PRIVATE)
    private val logs = ArrayDeque<String>(300)

    private val _searchRequests = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** 手机端推来的搜索关键词。搜索页订阅它：收到即跳搜索并开始搜。 */
    val searchRequests: SharedFlow<String> = _searchRequests.asSharedFlow()

    private val _playlistRequests = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** 手机端推来的歌单名。由 NavigationViewModel 消费：直接建一个本地歌单。 */
    val playlistRequests: SharedFlow<String> = _playlistRequests.asSharedFlow()

    private val _songlistRequests = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** 手机端推来的歌单链接 / ID。由 NavigationViewModel 消费：解析后导入本地歌单。 */
    val songlistRequests: SharedFlow<String> = _songlistRequests.asSharedFlow()

    @Volatile private var scope: CoroutineScope? = null

    /** 真正监听的服务实例。不继承 NanoHTTPD，因为端口只能在构造时给，而我们需要逐个试。 */
    private var server: NanoHTTPD? = null

    /** 实际绑定的端口（可能是备用端口）。 */
    @Volatile var boundPort: Int = PORTS.first()
        private set

    var isEnabled: Boolean
        get() = wsPrefs.getBoolean("enabled", true)
        private set(value) = wsPrefs.edit().putBoolean("enabled", value).apply()

    fun boot(scope: CoroutineScope) {
        this.scope = scope
        if (!isEnabled) return
        start(scope)
    }

    // 注意：Kotlin 给 `var isEnabled` 生成的 setter 就叫 setEnabled()，所以这里的方法不能
    // 再叫 setEnabled —— 会撞 JVM 签名。
    fun applyEnabled(value: Boolean) {
        isEnabled = value
        if (value) scope?.let { start(it) } else stop()
    }

    fun start(scope: CoroutineScope) {
        this.scope = scope
        if (server?.wasStarted() == true) return
        // 逐个端口试：8080 常被 Android Debug Database 之类的调试工具占用。
        for (port in PORTS) {
            val candidate = object : NanoHTTPD(port) {
                override fun serve(session: IHTTPSession): Response = serveInternal(session)
            }
            val ok = runCatching { candidate.start(SOCKET_READ_TIMEOUT, false) }.isSuccess
            if (ok) {
                server = candidate
                boundPort = port
                addLog("INFO", "管理页已启动：${serverUrl()}")
                return
            }
            runCatching { candidate.stop() }
            addLog("WARN", "端口 $port 被占用，尝试下一个")
        }
        addLog("ERROR", "管理页启动失败：${PORTS.joinToString("/")} 全部被占用")
    }

    fun stop() {
        runCatching { server?.stop() }
        server = null
    }

    fun addLog(level: String, message: String) {
        val line = "[${java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date())}] $level $message"
        synchronized(logs) {
            logs.addLast(line)
            while (logs.size > 300) logs.removeFirst()
        }
    }

    fun getLogs(): List<String> = synchronized(logs) { logs.toList() }

    fun serverUrl(): String = "http://${lanIp()}:$boundPort"

    // ── HTTP 路由 ──────────────────────────────────────────────────────────

    private fun serveInternal(session: IHTTPSession): Response {
        return try {
            when (session.method) {
                Method.GET -> handleGet(session)
                Method.POST -> handlePost(session)
                else -> notFound(session.uri)
            }
        } catch (e: Throwable) {
            val trace = e.stackTraceToString().lineSequence().take(6).joinToString("\n")
            addLog("ERROR", "请求处理异常 ${session.uri}：${e.message}")
            internalError("500 ${e.javaClass.simpleName}: ${e.message}\n$trace")
        }
    }

    /** 统一的路由键：去掉 query、去掉尾部斜杠。根路径归一为 ""。 */
    private fun routeKey(session: IHTTPSession): String =
        session.uri.substringBefore('?').trimEnd('/')

    private fun handleGet(session: IHTTPSession): Response = when (routeKey(session)) {
        "" -> html(pageIndex())
        "/search" -> html(pageSearch())
        "/lyric" -> {
            val q = session.parameters["q"]?.firstOrNull().orEmpty().trim()
            html(pageLyric(q))
        }
        "/playlist" -> html(pagePlaylist())
        "/songlist" -> html(pageSonglist())
        "/scripts" -> html(pageScripts())
        "/import" -> html(pageImport())
        "/settings" -> html(pageSettings())
        "/debug" -> debugDownload()
        "/logs" -> html(pageLogs())
        "/api/hotsearch" -> json(hotSearchJson())
        "/api/status" -> json(statusJson())
        else -> notFound(session.uri)
    }

    private fun handlePost(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        runCatching { session.parseBody(files) }
        val params = session.parameters
        return when (routeKey(session)) {
            "/api/search" -> {
                val q = params["q"]?.firstOrNull().orEmpty().trim()
                if (q.isNotEmpty()) {
                    scope?.launch { _searchRequests.emit(q) }
                    addLog("INFO", "手机推送搜索：$q")
                }
                html(donePage("已发送到电视：$q"))
            }
            "/api/playlist" -> {
                val name = params["name"]?.firstOrNull().orEmpty().trim()
                if (name.isNotEmpty()) {
                    scope?.launch { _playlistRequests.emit(name) }
                    addLog("INFO", "手机推送建歌单：$name")
                    html(donePage("已让电视创建歌单：$name"))
                } else html(donePage("未收到歌单名"))
            }
            "/api/songlist" -> {
                val url = params["url"]?.firstOrNull().orEmpty().trim()
                if (url.isNotEmpty()) {
                    scope?.launch { _songlistRequests.emit(url) }
                    addLog("INFO", "手机推送歌单链接：$url")
                    html(donePage("已发送歌单链接，电视正在导入"))
                } else html(donePage("未收到歌单链接"))
            }
            "/api/script-url" -> {
                val url = params["url"]?.firstOrNull().orEmpty().trim()
                if (url.isNotEmpty()) {
                    scope?.launch(Dispatchers.IO) { fetchAndImport(url) }
                    html(donePage("正在抓取并导入：$url"))
                } else html(donePage("未收到 URL"))
            }
            "/api/script-text" -> {
                val raw = params["text"]?.firstOrNull().orEmpty()
                if (raw.isNotBlank()) {
                    importRaw(raw)
                    html(donePage("已导入脚本"))
                } else html(donePage("未收到脚本内容"))
            }
            "/api/script-file" -> {
                val tmp = files["file"]
                val raw = tmp?.let { runCatching { File(it).readText() }.getOrNull() }
                if (!raw.isNullOrBlank()) {
                    importRaw(raw)
                    html(donePage("已上传 ${raw.length} 字符的脚本"))
                } else html(donePage("未收到文件"))
            }
            "/api/toggle" -> {
                val id = params["id"]?.firstOrNull().orEmpty()
                val enabled = (params["enabled"]?.firstOrNull() ?: "1") == "1"
                scope?.launch(Dispatchers.IO) { scripts.setEnabled(id, enabled) }
                html(donePage(if (enabled) "已启用" else "已停用"))
            }
            "/api/delete" -> {
                val id = params["id"]?.firstOrNull().orEmpty()
                scope?.launch(Dispatchers.IO) { scripts.remove(id) }
                html(donePage("已删除"))
            }
            "/api/prefs" -> {
                params["home"]?.firstOrNull()?.let { key -> SourceID.fromKey(key)?.let(prefs::setHomeSource) }
                params["quality"]?.firstOrNull()?.let { key -> Quality.fromKey(key)?.let(prefs::setPlayQuality) }
                params["toggleSource"]?.firstOrNull()?.let { prefs.setToggleSource(it == "1") }
                html(donePage("设置已保存"))
            }
            else -> notFound(session.uri)
        }
    }

    // ── 脚本导入 ───────────────────────────────────────────────────────────

    private fun importRaw(raw: String) {
        if (raw.isBlank()) return
        val scope = scope ?: return
        scope.launch(Dispatchers.IO) {
            scripts.import(raw).fold(
                onSuccess = { addLog("INFO", it.message) },
                onFailure = { addLog("ERROR", "脚本导入失败：${it.message}") },
            )
        }
    }

    /** 抓取脚本 URL 再导入。GitHub 链接走加速前缀（电视连不上 GitHub）。 */
    private suspend fun fetchAndImport(url: String) {
        val candidates = GithubMirror.candidates(url)
        var lastErr: Exception? = null
        for (target in candidates) {
            val req = Request.Builder()
                .url(target)
                .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)")
                .build()
            try {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        lastErr = Exception("HTTP ${resp.code}")
                        return@use
                    }
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) {
                        lastErr = Exception("空响应")
                        return@use
                    }
                    importRaw(body)
                    addLog("INFO", "已从 $target 抓取脚本")
                    return
                }
            } catch (e: Exception) {
                lastErr = e
            }
        }
        addLog("ERROR", "脚本 URL 抓取失败：${lastErr?.message ?: "未知错误"}")
    }

    // ── 页面渲染 ───────────────────────────────────────────────────────────

    private fun pageIndex(): String = shell(
        "薄音 · 手机助手",
        """
        <h2>薄音</h2>
        <p>用手机浏览器打开此页，遥控器上做不了的事都能在这里完成。</p>
        <div class="card"><a href="/search"><h3>🔍 搜索歌曲</h3></a><p>搜歌、点热搜词，电视自动跳去搜索页。</p></div>
        <div class="card"><a href="/lyric"><h3>🎤 搜索歌词</h3></a><p>按歌名 / 歌手查歌词，结果直接显示在手机上。</p></div>
        <div class="card"><a href="/playlist"><h3>✏️ 添加歌单</h3></a><p>用手机输入法起名，电视上直接建好。</p></div>
        <div class="card"><a href="/songlist"><h3>🔗 推送歌单链接</h3></a><p>粘平台分享链接，电视自动导入成歌单。</p></div>
        <div class="card"><a href="/import"><h3>📜 提交音源</h3></a><p>粘贴 URL / 文本，或上传 .js 文件。</p></div>
        <div class="card"><a href="/scripts"><h3>🧩 管理已装音源</h3></a><p>启用 / 停用 / 删除已装脚本。</p></div>
        <div class="card"><a href="/settings"><h3>⚙️ 数据源与音质</h3></a><p>切换首页数据源、默认音质。</p></div>
        <div class="card"><a href="/debug"><h3>🐞 下载调试文件</h3></a><p>设备信息 + 设置 + 脚本 + 日志 + 崩溃记录。</p></div>
        <div class="card"><a href="/logs"><h3>📋 解析日志</h3></a><p>看最近音源解析发生了什么。</p></div>
        """,
    )

    private fun pageSearch(): String = shell(
        "搜索 · 薄音",
        """
        <h2>搜索歌曲</h2>
        <p>输入关键词点发送，电视会自动跳到搜索页开始搜。也可点下方热搜词。</p>
        <div class="card">
          <input id="q" placeholder="歌曲名 / 歌手 / 歌单" autofocus>
          <button onclick="send()">发送到电视</button>
        </div>
        <div id="hot"></div>
        <script>
          var TINT={kw:'#F26D4D',kg:'#1AB0F0',tx:'#33CC8C',wy:'#F24552'};
          function toast(msg){
            var t=document.getElementById('toast');
            t.textContent=msg; t.className='toast show';
            setTimeout(function(){t.className='toast';},1600);
          }
          function send(){
            var q=document.getElementById('q').value.trim();
            if(!q) return;
            var body='q='+encodeURIComponent(q);
            fetch('/api/search',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body})
              .then(function(){toast('已发送到电视：'+q);})
              .catch(function(){toast('发送失败，请重试');});
          }
          function pick(w){
            document.getElementById('q').value=w; send();
          }
          document.getElementById('q').addEventListener('keydown',function(e){ if(e.key==='Enter') send(); });
          fetch('/api/hotsearch').then(function(r){return r.json();}).then(function(cols){
            var wrap=document.getElementById('hot');
            if(!cols||!cols.length) return;
            var h='<div class="card"><h3 style="margin-bottom:6px">热门搜索</h3><div class="hotgrid">';
            cols.forEach(function(c){
              if(!c.words||!c.words.length) return;
              var tint=TINT[c.source]||'#4ADE80';
              h+='<div class="hotgrp"><div class="hottitle" style="color:'+tint+'">'+c.name+'</div>';
              c.words.forEach(function(w,i){
                var rankStyle=i<3?'color:'+tint:'';
                h+='<div class="hotword" onclick="pick(this.dataset.w)" data-w="'+w.replace(/"/g,'&quot;')+'">'
                  +'<span class="rank'+(i<3?' top':'')+'" style="'+rankStyle+'">'+(i+1)+'</span>'
                  +'<span class="w">'+w+'</span></div>';
              });
              h+='</div>';
            });
            h+='</div></div>';
            wrap.innerHTML=h;
          });
        </script>
        """,
    )

    // ── 搜索歌词 ───────────────────────────────────────────────────────────

    /**
     * 歌词查询页。上游项目是「把关键词推给电视去搜」，veil 这边做成**手机端直接出结果**：
     * 手机屏比电视更适合阅读长歌词，也不打断电视上正在播的歌。
     *
     * 链路复用播放页：搜歌 → 取第一首 → 走 [LxRepository.lyrics]（脚本 → 内置 → 跨平台兜底）。
     */
    private fun pageLyric(keyword: String): String {
        val result = if (keyword.isBlank()) {
            "<p class=muted>输入歌名或歌手后点查询。</p>"
        } else {
            runBlockingText(timeoutSec = 25) { lyricHtml(keyword) }
        }
        return shell(
            "搜索歌词 · 薄音",
            """
            <h2>搜索歌词</h2>
            <p>输入歌名（或「歌名 歌手」），直接在这里看歌词。</p>
            <form class="card" method="GET" action="/lyric">
              <input name="q" value="${escape(keyword)}" placeholder="例如：晴天 周杰伦" autofocus>
              <button>查询歌词</button>
            </form>
            <div class="card">$result</div>
            """,
        )
    }

    private suspend fun lyricHtml(keyword: String): String {
        val tracks = runCatching { repo.searchTracks(keyword, null, 1) }.getOrDefault(emptyList())
        val track = tracks.firstOrNull()
            ?: return "<p class=muted>没搜到《${escape(keyword)}》，换个关键词或先在设置里换个数据源。</p>"
        val payload = runCatching { repo.lyrics(track) }.getOrNull()
        if (payload == null || payload.lines.isEmpty()) {
            return "<p class=muted>找到「${escape(track.name)} - ${escape(track.singer)}」，但没拿到歌词。</p>"
        }
        val body = payload.lines.joinToString("") { line ->
            if (line.text.isBlank()) "<div class=lrc>&nbsp;</div>"
            else "<div class=lrc>${escape(line.text)}</div>"
        }
        return """
        <div class="meta" style="margin-bottom:10px;color:#4ADE80;font-weight:700">
          ${escape(track.name)} - ${escape(track.singer)}
        </div>
        <div class=hint>来源：${escape(payload.source ?: "未知")} · ${payload.lines.size} 行</div>
        <div class=lrcbox>$body</div>
        """.trimIndent()
    }

    // ── 添加歌单 / 推送歌单链接 ────────────────────────────────────────────

    private fun pagePlaylist(): String = shell(
        "添加歌单 · 薄音",
        """
        <h2>给歌单起个名字</h2>
        <p>用手机输入法（中英文都行），提交后电视上会直接建好这个歌单。</p>
        <form class="card" method="POST" action="/api/playlist">
          <input name="name" placeholder="例如：开车听的歌" maxlength="24" autofocus>
          <button>在电视上创建</button>
        </form>
        """,
    )

    private fun pageSonglist(): String = shell(
        "推送歌单 · 薄音",
        """
        <h2>推送歌单链接</h2>
        <p>从酷我 / 酷狗 / QQ 音乐 / 网易云分享一个歌单链接（或纯数字 ID），粘进来发送，
           电视会自动识别平台并导入成一个本地歌单。</p>
        <form class="card" method="POST" action="/api/songlist">
          <textarea name="url" placeholder="例如 https://music.163.com/playlist?id=2034742057" rows="3" autofocus></textarea>
          <button>发送到电视</button>
        </form>
        """,
    )

    // ── 调试文件下载 ───────────────────────────────────────────────────────

    /**
     * 生成并下载一份纯文本调试报告（对应上游项目的 `/debug`）。
     *
     * 内容：构建信息 → 设备 → 偏好 → 已装脚本（含 loadError）→ 最近日志 → 最近一次崩溃。
     * 手机上点开链接即可保存后回传，比在电视上截图靠谱得多。
     */
    private fun debugDownload(): Response {
        val text = runCatching { buildDebugReport() }.getOrElse {
            return internalError("调试信息生成失败：${it.message}")
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val name = "veil-debug-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(java.util.Date())}.txt"
        val res = NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK,
            "text/plain; charset=utf-8",
            bytes.inputStream(),
            bytes.size.toLong(),
        )
        res.addHeader("Content-Disposition", "attachment; filename=\"$name\"")
        res.addHeader("Cache-Control", "no-store")
        return res
    }

    private fun buildDebugReport(): String {
        val device = runCatching { com.veil.music.util.DeviceInfo.collect(context) }.getOrNull()
        val scriptsNow = scripts.scripts.value
        return buildString {
            appendLine("薄音 · 调试信息")
            appendLine("生成时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(java.util.Date())}")
            appendLine("版本：${com.veil.music.BuildConfig.VERSION_NAME} (${com.veil.music.BuildConfig.VERSION_CODE})")
            appendLine("提交：${com.veil.music.BuildConfig.GIT_SHA}")
            appendLine("管理页：${serverUrl()}")
            appendLine()
            appendLine("── 设备 ──")
            if (device == null) {
                appendLine("(采集失败)")
            } else {
                appendLine("型号：${device.model}")
                appendLine("Android：${device.android}")
                appendLine("ABI：${device.abi}")
                appendLine("屏幕：${device.screen}")
                appendLine("内存：${device.ram}")
                appendLine("音频输出：${device.output}")
                appendLine("直通：${device.passthrough}")
                appendLine("解码器：${device.decoders}")
            }
            appendLine()
            appendLine("── 设置 ──")
            appendLine("首页数据源：${prefs.homeSource.value.displayName} (${prefs.homeSource.value.key})")
            appendLine("默认音质：${prefs.playQuality.value.displayName}")
            appendLine("自动换源：${prefs.toggleSource.value}")
            appendLine("内置直连兜底：${prefs.builtinFallback.value}")
            appendLine()
            appendLine("── 已装音源（${scriptsNow.size}）──")
            if (scriptsNow.isEmpty()) appendLine("(无)")
            scriptsNow.forEachIndexed { i, s ->
                appendLine("${i + 1}. ${s.name} ${s.version.ifBlank { "-" }} / ${s.author.ifBlank { "未知" }}")
                appendLine("   内置=${s.builtin} 启用=${s.enabled} 加载=${s.loadError ?: "正常"}")
            }
            appendLine()
            appendLine("── 最近日志（${getLogs().size} 条）──")
            getLogs().takeLast(200).forEach { appendLine(it) }
            appendLine()
            appendLine("── 最近一次崩溃 ──")
            appendLine(runCatching { com.veil.music.util.CrashReporter.lastCrash(context) }.getOrNull() ?: "(无)")
        }
    }

    private fun pageScripts(): String {
        val list = scripts.scripts.value
        val rows = if (list.isEmpty()) {
            "<p class=muted>还没有导入任何音源脚本。</p>"
        } else {
            list.joinToString("") { s ->
                val badge = if (s.builtin) "<span class=tag>内置</span>" else "<span class=tag alt>导入</span>"
                val state = if (s.enabled) "已启用" else "已停用"
                val err = s.loadError?.let { "<span class=err>加载失败：${escape(it)}</span>" } ?: ""
                val act = if (s.enabled) "0" else "1"
                """
                <div class=row>
                  <div class=meta><b>${escape(s.name)}</b> $badge
                    <span class=muted>v${escape(s.version.ifBlank { "-" })} · ${escape(s.author.ifBlank { "未知作者" })} · $state</span>$err
                  </div>
                  <form method=post action=/api/toggle><input type=hidden name=id value="${s.id}"><input type=hidden name=enabled value=$act><button>${if (s.enabled) "停用" else "启用"}</button></form>
                  <form method=post action=/api/delete><input type=hidden name=id value="${s.id}"><button class=danger>删除</button></form>
                </div>
                """.trimIndent()
            }
        }
        return shell(
            "音源管理 · 薄音",
            """
            <h2>已装音源（${list.size}）</h2>
            <p>启用 / 停用 / 删除已装脚本，<a href="/import">导入新音源 →</a></p>
            <div class="card">$rows</div>
            """,
        )
    }

    private fun pageImport(): String = shell(
        "导入音源 · 薄音",
        """
        <h2>导入自定义音源</h2>
        <p>三种方式任选：粘贴脚本 URL、直接粘贴脚本文本、上传 .js 文件。提交后电视自动加载。</p>
        <div class="card">
          <h3>方式一：脚本 URL</h3>
          <form method="POST" action="/api/script-url">
            <input name="url" placeholder="脚本 URL（例如 https://.../source.js）">
            <button>发送 URL</button>
          </form>
        </div>
        <div class="card">
          <h3>方式二：粘贴脚本文本</h3>
          <form method="POST" action="/api/script-text">
            <textarea name="text" placeholder="把 lx-music v4 自定义源脚本整段粘贴到这里"></textarea>
            <button>导入文本</button>
          </form>
        </div>
        <div class="card">
          <h3>方式三：上传 .js 文件</h3>
          <form method="POST" action="/api/script-file" enctype="multipart/form-data">
            <input type="file" name="file" accept=".js,text/javascript,application/javascript">
            <button>上传脚本文件</button>
          </form>
        </div>
        """,
    )

    private fun pageSettings(): String {
        val home = SourceID.homePageOrder.joinToString("") { src ->
            val on = if (prefs.homeSource.value == src) " checked" else ""
            """<label class=chip><input type=radio name=home value=${src.key}$on onchange="save()">${src.displayName}</label>"""
        }
        val quality = Quality.entries.joinToString("") { q ->
            val on = if (prefs.playQuality.value == q) " selected" else ""
            """<option value=${q.key}$on>${q.displayName}</option>"""
        }
        val toggleOn = if (prefs.toggleSource.value) " checked" else ""
        return shell(
            "设置 · 薄音",
            """
            <h2>首页数据源</h2>
            <p>选一个平台，发现页的歌单与榜单都来自它。</p>
            <div class="card">
              $home
              <div class="hint">切换后立即生效。</div>
            </div>
            <h2>默认音质</h2>
            <div class="card">
              <select onchange="fetch('/api/prefs?quality='+encodeURIComponent(this.value),{method:'POST'}).then(function(){toast('已保存');})">$quality</select>
              <div class="hint">解析时会优先取这个音质，取不到再逐级降级。</div>
            </div>
            <h2>自动换源</h2>
            <div class="card">
              <label class=chip><input type=checkbox name=toggleSource value=1$toggleOn onchange="fetch('/api/prefs?toggleSource='+(this.checked?1:0),{method:'POST'}).then(function(){toast('已保存');})">解析失败时按歌名+歌手自动换源</label>
            </div>
            <script>
              function toast(msg){var t=document.getElementById('toast');if(!t){t=document.createElement('div');t.id='toast';t.className='toast';document.body.appendChild(t);}t.textContent=msg;t.className='toast show';setTimeout(function(){t.className='toast';},1600);}
              function save(){var s=document.querySelector('input[name=home]:checked');if(s) fetch('/api/prefs?home='+s.value,{method:'POST'}).then(function(){toast('已保存');});}
            </script>
            """,
        )
    }

    private fun pageLogs(): String = shell(
        "日志 · 薄音",
        """
        <h2>解析日志（最近 ${getLogs().size} 条）</h2>
        <div class="card">
          <pre>${escape(getLogs().takeLast(80).joinToString("\n"))}</pre>
        </div>
        <p><a href="/logs" onclick="setTimeout(function(){location.reload()},500)">刷新</a></p>
        """,
    )

    private fun hotSearchJson(): String {
        // 热搜拉取是阻塞网络调用，在 NanoHTTPD 工作线程上跑即可；失败给空列表。
        val columns = runBlockingFetch()
        val arr = JSONArray()
        columns.forEach { c ->
            val obj = JSONObject()
            obj.put("source", c.source.key)
            obj.put("name", c.source.displayName)
            obj.put("words", JSONArray(c.words))
            arr.put(obj)
        }
        return arr.toString()
    }

    /** 在非协程上下文中同步取热搜。NanoHTTPD 的工作线程不在协程里，这里用一个
     *  一次性的阻塞桥接（跑在后台线程，不卡 UI）。 */
    private fun runBlockingFetch(): List<HotSearchColumn> {
        var result: List<HotSearchColumn> = emptyList()
        val latch = java.util.concurrent.CountDownLatch(1)
        val scope = scope
        if (scope == null) return emptyList()
        scope.launch(Dispatchers.IO) {
            result = runCatching { repo.hotSearch() }.getOrDefault(emptyList())
            latch.countDown()
        }
        runCatching { latch.await(15, java.util.concurrent.TimeUnit.SECONDS) }
        return result
    }

    /**
     * 通用阻塞桥接：NanoHTTPD 的 worker 线程不是协程，而歌词检索是 suspend 的。
     * 把任务丢到应用 scope 上跑，这里最多等 [timeoutSec] 秒。超时返回友好提示，
     * 不让手机那一端一直转圈。
     */
    private fun runBlockingText(timeoutSec: Long = 20, block: suspend () -> String): String {
        val scope = scope ?: return "<p class=muted>服务尚未就绪，请稍后重试。</p>"
        val latch = java.util.concurrent.CountDownLatch(1)
        var out = "<p class=muted>查询超时，请重试。</p>"
        scope.launch(Dispatchers.IO) {
            out = runCatching { block() }.getOrElse { "<p class=muted>查询失败：${escape(it.message ?: "未知错误")}</p>" }
            latch.countDown()
        }
        runCatching { latch.await(timeoutSec, java.util.concurrent.TimeUnit.SECONDS) }
        return out
    }

    private fun statusJson(): String {
        val list = scripts.scripts.value
        return """
        {"home":"${prefs.homeSource.value.key}","quality":"${prefs.playQuality.value.key}",
         "scripts":[${list.joinToString(",") { """{"name":${q(it.name)},"enabled":${it.enabled},"builtin":${it.builtin}}""" }}]}
        """.trimIndent()
    }

    // ── 响应工具 ───────────────────────────────────────────────────────────

    private fun shell(title: String, body: String): String = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>$title</title>
        <style>
          body{margin:0;padding:24px;font-family:-apple-system,system-ui,sans-serif;
               background:#0A0D14;color:#fff;}
          h2{margin:0 0 8px;font-weight:700;color:#4ADE80;}
          h3{margin:0 0 10px;font-weight:700;color:#e5e5ea;font-size:15px;}
          p{color:#a0a0b0;margin:0 0 18px;line-height:1.5;font-size:14px;}
          .card{background:#14171F;border-radius:12px;padding:18px;margin-bottom:14px;}
          .card a{color:#fff;text-decoration:none;}
          input,textarea,select{width:100%;padding:13px;font-size:16px;border-radius:8px;
                 border:1px solid #2A2D38;background:#0A0D14;color:#fff;
                 box-sizing:border-box;margin-bottom:10px;font-family:inherit;}
          textarea{height:190px;font:13px/1.5 ui-monospace,monospace;resize:vertical;}
          input[type=file]{padding:8px;}
          button{width:100%;padding:14px;background:#4ADE80;color:#0A0D14;border:0;
                 border-radius:8px;font-size:16px;font-weight:700;cursor:pointer;}
          button.danger{background:#3a1f22;color:#ff8f8f;}
          .row{display:flex;align-items:center;gap:10px;padding:9px 0;border-bottom:1px solid #222228;}
          .row:last-child{border-bottom:0;} .meta{flex:1;min-width:0;word-break:break-all;}
          .row form{display:inline;margin:0;} .row button{width:auto;padding:8px 14px;font-size:13px;}
          .tag{background:#2c2c33;color:#aeaeb2;border-radius:6px;padding:1px 6px;font-size:11px;margin-left:6px;}
          .tag.alt{background:#1d3a2a;color:#7ad39a;}
          .muted{color:#8e8e93;font-size:13px;}
          .err{color:#ff8f8f;font-size:12px;display:block;margin-top:2px;}
          .hint{font-size:12px;color:#8e8e93;margin-top:6px;}
          .chip{display:inline-flex;align-items:center;gap:6px;background:#1c1c22;border:1px solid #2a2a31;
                border-radius:999px;padding:7px 13px;margin:0 8px 8px 0;font-size:13px;cursor:pointer;}
          pre{background:#0f0f12;border:1px solid #222228;border-radius:10px;padding:12px;
              font:12px/1.5 ui-monospace,monospace;max-height:320px;overflow:auto;white-space:pre-wrap;}
          a{color:#4ADE80;}
          .ok{color:#4ADE80;font-size:18px;font-weight:700;}
          .toast{position:fixed;left:50%;top:24px;transform:translateX(-50%);
                 background:#4ADE80;color:#0A0D14;font-weight:700;font-size:15px;
                 padding:12px 22px;border-radius:24px;opacity:0;transition:opacity .25s;
                 pointer-events:none;z-index:9;box-shadow:0 6px 20px rgba(0,0,0,.4);}
          .toast.show{opacity:1;}
          .hotgrid{display:grid;grid-template-columns:1fr 1fr;gap:6px 16px;}
          .hotgrp{min-width:0;margin-top:10px;}
          .hottitle{font-weight:700;font-size:14px;margin:0 0 6px;}
          .hotword{display:flex;align-items:center;gap:8px;padding:8px 4px;
                   cursor:pointer;font-size:14px;color:#fff;border-radius:8px;}
          .hotword:active{background:#1c2029;}
          .hotword .w{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}
          .rank{width:16px;flex:none;text-align:center;font-weight:700;font-size:12px;color:#666;}
          .rank.top{color:inherit;}
          .lrcbox{max-height:60vh;overflow:auto;margin-top:10px;padding-right:4px;}
          .lrc{font-size:15px;line-height:1.9;color:#e5e5ea;text-align:center;}
        </style></head>
        <body>
          <div id="toast" class="toast"></div>
          $body
          <p style="margin-top:20px"><a href="/">← 返回首页</a></p>
        </body></html>
    """.trimIndent()

    private fun donePage(message: String): String = shell(
        "已发送 · 薄音",
        """
        <div class="card" style="text-align:center;padding:32px 18px;">
          <div class="ok">✓ ${escape(message)}</div>
          <p style="margin-top:18px;">可以回到电视查看结果。</p>
        </div>
        """,
    )

    private fun html(body: String): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)

    private fun json(body: String): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body)

    // 注意：这里一律用 "text/plain" 字面量，不要用 NanoHTTPD.MIME_PLAINTEXT。
    // 该字段在 Kotlin 子类里可能取到 null，newFixedLengthResponse 内部 new ContentType(null)
    // 会抛异常 —— 表现就是「任何未命中分支的请求都返回 500」。
    private fun notFound(uri: String = ""): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "Not Found: $uri")

    private fun internalError(msg: String): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8", msg)

    // ── 工具 ───────────────────────────────────────────────────────────────

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    private fun q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun lanIp(): String {
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull()
                ?.hostAddress
                ?.let { return it }
        }
        return "127.0.0.1"
    }

    private companion object {
        /**
         * 监听端口候选表，按顺序尝试。
         *
         * 首选 8666 而不是 8080：Android Debug Database（很多调试版应用会带）固定占 8080，
         * 抢不到端口的后果是用户打开手机页看到的是**别人的界面**（标题写着
         * 「Android Debug Database」），而本应用的服务其实根本没起来。
         */
        val PORTS = listOf(8666, 8667, 8668, 8669)

        // NanoHTTPD 的 start(timeout, daemon) 里 timeout 单位是秒。
        const val SOCKET_READ_TIMEOUT = 30
    }
}
