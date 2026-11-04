package com.veil.music.media

import android.content.Context
import com.veil.music.data.VeilPreferences
import com.veil.music.data.model.Quality
import com.veil.music.data.model.SourceID
import com.veil.music.data.store.ScriptStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 手机端管理页（默认 8080），改造自原 Apple Music 版的 token 配对页。
 *
 * 现在只管三件事 —— 都是遥控器上做不了的：
 *  1. 粘贴 / 导入 lx v4 自定义音源脚本（大段 JS 在 TV 上没法输入）；
 *  2. 启用、停用、删除已装脚本；
 *  3. 切首页数据源与音质，顺手看一眼解析日志。
 *
 * 只监听局域网，没有任何鉴权 —— 与上游一致（LAN-only, 不带敏感凭据）。
 */
@Singleton
class InAppWebServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scripts: ScriptStore,
    private val prefs: VeilPreferences,
) {
    private val wsPrefs = context.getSharedPreferences("webserver_prefs", Context.MODE_PRIVATE)
    private val logs = ArrayDeque<String>(300)

    private var serverSocket: ServerSocket? = null
    private var loop: Thread? = null

    var isEnabled: Boolean
        get() = wsPrefs.getBoolean("enabled", true)
        private set(value) = wsPrefs.edit().putBoolean("enabled", value).apply()

    @Volatile private var scope: CoroutineScope? = null

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
        if (serverSocket != null) return
        val socket = try {
            ServerSocket(PORT).also { serverSocket = it }
        } catch (e: Exception) {
            addLog("WARN", "管理页启动失败：${e.message}")
            return
        }
        loop = Thread {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                scope.launch(Dispatchers.IO) { runCatching { handle(client) } }
            }
        }.also {
            it.isDaemon = true
            it.name = "veil-web"
            it.start()
        }
        addLog("INFO", "管理页已启动：${serverUrl()}")
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        loop = null
    }

    fun addLog(level: String, message: String) {
        val line = "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}] $level $message"
        synchronized(logs) {
            logs.addLast(line)
            while (logs.size > 300) logs.removeFirst()
        }
    }

    fun getLogs(): List<String> = synchronized(logs) { logs.toList() }

    fun serverUrl(): String = "http://${lanIp()}:$PORT"

    // ── HTTP ───────────────────────────────────────────────────────────────

    private suspend fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 15_000
            val input = socket.getInputStream()
            val out = BufferedOutputStream(socket.getOutputStream())

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return send(out, 400, "text/plain", "bad request")
            val method = parts[0]
            val path = parts[1]

            var contentLength = 0
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            val body = if (contentLength > 0) {
                val bytes = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(bytes, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                String(bytes, 0, read, Charsets.UTF_8)
            } else ""

            val pathOnly = path.substringBefore('?')
            val query = parseQuery(path.substringAfter('?', ""))

            when {
                method == "GET" && pathOnly == "/" -> send(out, 200, "text/html; charset=utf-8", page())
                method == "GET" && pathOnly == "/status" -> send(out, 200, "application/json", statusJson())
                method == "GET" && pathOnly == "/logs" -> send(out, 200, "text/plain; charset=utf-8", getLogs().joinToString("\n"))
                method == "POST" && pathOnly == "/import" -> {
                    val raw = query["script"] ?: body.substringAfter("script=", "").let { decodeForm(it) }
                    onImport(raw)
                    redirect(out)
                }
                method == "POST" && pathOnly == "/toggle" -> {
                    val id = query["id"] ?: ""
                    val enabled = (query["enabled"] ?: "1") == "1"
                    withContext(Dispatchers.IO) { scripts.setEnabled(id, enabled) }
                    redirect(out)
                }
                method == "POST" && pathOnly == "/delete" -> {
                    val id = query["id"] ?: ""
                    withContext(Dispatchers.IO) { scripts.remove(id) }
                    redirect(out)
                }
                method == "POST" && pathOnly == "/prefs" -> {
                    query["home"]?.let { key -> SourceID.fromKey(key)?.let(prefs::setHomeSource) }
                    query["quality"]?.let { key -> Quality.fromKey(key)?.let(prefs::setPlayQuality) }
                    query["toggleSource"]?.let { prefs.setToggleSource(it == "1") }
                    query["builtin"]?.let { prefs.setBuiltinFallback(it == "1") }
                    redirect(out)
                }
                else -> send(out, 404, "text/plain", "not found")
            }
        }
    }

    private fun onImport(raw: String) {
        if (raw.isBlank()) return
        val scope = scope ?: return
        scope.launch(Dispatchers.IO) {
            scripts.import(raw).fold(
                onSuccess = { addLog("INFO", it.message) },
                onFailure = { addLog("ERROR", "脚本导入失败：${it.message}") },
            )
        }
    }

    private fun page(): String {
        val list = scripts.scripts.value
        val rows = if (list.isEmpty()) {
            "<p class=muted>还没有导入任何音源脚本。</p>"
        } else {
            list.joinToString("") { s ->
                val badge = if (s.builtin) "<span class=tag>内置</span>" else "<span class=tag alt>导入</span>"
                val state = if (s.enabled) "已启用" else "已停用"
                val act = if (s.enabled) "0" else "1"
                """
                <div class=row>
                  <div class=meta><b>${escape(s.name)}</b> $badge
                    <span class=muted>v${escape(s.version.ifBlank { "-" })} · ${escape(s.author.ifBlank { "未知作者" })} · $state</span>
                  </div>
                  <form method=post action=/toggle?id=${s.id}&enabled=$act><button>${if (s.enabled) "停用" else "启用"}</button></form>
                  <form method=post action=/delete?id=${s.id}><button class=danger>删除</button></form>
                </div>
                """.trimIndent()
            }
        }
        val home = SourceID.homePageOrder.joinToString("") { src ->
            val on = if (prefs.homeSource.value == src) " checked" else ""
            """<label class=chip><input type=radio name=home value=${src.key}$on onchange="save()">${src.displayName}</label>"""
        }
        val quality = Quality.entries.joinToString("") { q ->
            val on = if (prefs.playQuality.value == q) " selected" else ""
            """<option value=${q.key}$on>${q.displayName}</option>"""
        }
        return """
<!doctype html><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>薄音 · 音源管理</title>
<style>
 body{font:15px/1.6 -apple-system,system-ui,sans-serif;margin:0;padding:20px;background:#0b0b0d;color:#f2f2f7}
 h1{font-size:19px;margin:0 0 4px} h2{font-size:15px;margin:26px 0 10px;color:#c7c7cc}
 .muted{color:#8e8e93;font-size:13px}
 .card{background:#16161a;border:1px solid #26262c;border-radius:14px;padding:16px;margin-bottom:14px}
 .row{display:flex;align-items:center;gap:10px;padding:9px 0;border-bottom:1px solid #222228}
 .row:last-child{border-bottom:0} .meta{flex:1;min-width:0;word-break:break-all}
 .tag{background:#2c2c33;color:#aeaeb2;border-radius:6px;padding:1px 6px;font-size:11px;margin-left:6px}
 .tag.alt{background:#1d3a2a;color:#7ad39a}
 button{background:#2c2c33;color:#fff;border:0;border-radius:9px;padding:8px 14px;font-size:13px}
 button.danger{background:#3a1f22;color:#ff8f8f}
 textarea{width:100%;height:190px;background:#0f0f12;color:#e5e5ea;border:1px solid #2a2a31;border-radius:10px;padding:10px;font:12px/1.5 ui-monospace,monospace}
 .chip{display:inline-flex;align-items:center;gap:6px;background:#1c1c22;border:1px solid #2a2a31;border-radius:999px;padding:7px 13px;margin:0 8px 8px 0;font-size:13px}
 select{background:#1c1c22;color:#fff;border:1px solid #2a2a31;border-radius:9px;padding:7px 10px;font-size:13px}
 pre{background:#0f0f12;border:1px solid #222228;border-radius:10px;padding:12px;font:12px/1.5 ui-monospace,monospace;max-height:260px;overflow:auto;white-space:pre-wrap}
 .hint{font-size:12px;color:#8e8e93;margin-top:6px}
</style>
<h1>薄音 · 音源管理</h1>
<div class=muted>${escape(serverUrl())}</div>

<div class=card>
  <h2 style="margin-top:0">首页数据源</h2>
  $home
  <div class=hint>选一个平台，发现页的歌单与榜单都来自它。</div>
  <h2>默认音质</h2>
  <select onchange="fetch('/prefs?quality='+this.value,{method:'POST'}).then(()=>location.reload())">$quality</select>
</div>

<div class=card>
  <h2 style="margin-top:0">已安装脚本（${list.size}）</h2>
  $rows
</div>

<div class=card>
  <h2 style="margin-top:0">导入音源脚本</h2>
  <form method=post action=/import>
    <textarea name=script placeholder="把 lx-music v4 自定义源脚本整段粘贴到这里"></textarea>
    <div class=hint>脚本里必须带 /* @name ... */ 头部注释，导入后立即生效。</div>
    <button style="margin-top:10px">导入并启用</button>
  </form>
</div>

<div class=card>
  <h2 style="margin-top:0">解析日志（最近 ${getLogs().size} 条）</h2>
  <pre>${escape(getLogs().takeLast(60).joinToString("\n"))}</pre>
</div>
<script>
function save(){
  const s=document.querySelector('input[name=home]:checked')
  if(s) fetch('/prefs?home='+s.value,{method:'POST'}).then(()=>location.reload())
}
</script>
""".trimIndent()
    }

    private fun statusJson(): String {
        val list = scripts.scripts.value
        return """
        {"home":"${prefs.homeSource.value.key}","quality":"${prefs.playQuality.value.key}",
         "scripts":[${list.joinToString(",") { """{"name":${q(it.name)},"enabled":${it.enabled},"builtin":${it.builtin}}""" }}]}
        """.trimIndent()
    }

    // ── 工具 ───────────────────────────────────────────────────────────────

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split('&').filter { it.isNotBlank() }.mapNotNull {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=', "")
            if (k.isBlank()) null else decodeForm(k) to decodeForm(v)
        }.toMap()

    private fun decodeForm(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun redirect(out: BufferedOutputStream) =
        send(out, 303, "text/plain", "", "Location: /\r\n")

    private fun send(out: BufferedOutputStream, code: Int, type: String, body: String, extra: String = "") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $code ${if (code == 200) "OK" else "OK"}\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append(extra)
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

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
        const val PORT = 8080
    }
}
