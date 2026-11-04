package com.veil.music.data.store

import android.content.Context
import com.veil.music.data.model.UserScript
import com.veil.music.source.SourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** Stores imported custom-source scripts and (re)loads enabled ones into [SourceManager]. */
class ScriptStore(private val context: Context, private val sourceManager: SourceManager) {
    private val store = JsonStore(
        File(context.filesDir, "scripts.json"),
        ListSerializer(UserScript.serializer()),
        emptyList(),
    )

    /**
     * 被用户主动删掉的随附音源（`name||author`）。
     *
     * 内置脚本每次启动都会由 [ensureBuiltInScripts] 补齐，所以「删掉」必须留痕，
     * 否则重启就回来了 —— 用户删它多半是因为这个源已经失效，一重启复活等于白删。
     */
    private val removedStore = JsonStore(
        File(context.filesDir, "removed_builtins.json"),
        ListSerializer(String.serializer()),
        emptyList(),
    )
    private var removed: Set<String> = emptySet()

    private val _scripts = MutableStateFlow<List<UserScript>>(emptyList())
    val scripts: StateFlow<List<UserScript>> = _scripts.asStateFlow()

    /** Load persisted scripts and start the enabled ones, then make sure every bundled
     *  built-in source (the .js files under assets/builtin_scripts) is present — so a fresh
     *  install is playable without hunting for a custom source first. */
    suspend fun loadAll() {
        val (list, gone) = withContext(Dispatchers.IO) { store.load() to removedStore.load() }
        _scripts.value = list
        removed = gone.toSet()
        // 先加载用户自己导入的源（内置源交给 ensureBuiltInScripts 统一处理，避免同一个脚本
        // 被加载两次 —— 每次 load 都会新建一个 QuickJS 上下文，重复加载等于白烧 CPU）。
        var changed = false
        for (script in _scripts.value.filter { it.enabled && !it.builtin }) {
            val err = loadAndRecordError(script)
            if (err != script.loadError) {
                _scripts.value = _scripts.value.map { if (it.id == script.id) it.copy(loadError = err) else it }
                changed = true
            }
        }
        ensureBuiltInScripts()
        if (changed) persist()
    }

    /**
     * 把脚本加载进 [SourceManager]，返回失败原因（成功则 null）。
     *
     * 加载成功时顺手把记录里的 [UserScript.loadError] 清掉 —— 上一个版本失败、这一版服务端
     * 修好了，界面上的红字要自己消失。
     */
    private suspend fun loadAndRecordError(script: UserScript): String? {
        val result = sourceManager.load(script)
        return result.exceptionOrNull()?.let { e ->
            e.message?.takeIf { it.isNotBlank() } ?: "加载失败"
        }
    }

    /** Import the lx v4 scripts shipped in `assets/builtin_scripts`, and drop the built-in
     *  copies that this build no longer ships.
     *
     *  Rules: a bundled script is imported when it isn't there yet, or when the installed copy
     *  is itself a built-in one whose source text has changed (i.e. the app shipped an update).
     *  A same-named script the *user* imported by hand is left alone.
     *
     *  The removal pass matters when the bundled set is swapped out wholesale (e.g. 旧的 15 个
     *  落雪源换成 10 月这批 7 个)：升级安装后那些不再随包的内置源会被卸载，用户自己导入的
     *  源不受影响。
     */
    suspend fun ensureBuiltInScripts() {
        val names = withContext(Dispatchers.IO) {
            runCatching { context.assets.list(BUILTIN_DIR)?.toList() }.getOrNull()
        } ?: return
        val shipped = mutableSetOf<Pair<String, String>>()
        for (fileName in names.filter { it.endsWith(".js") }.sorted()) {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    context.assets.open("$BUILTIN_DIR/$fileName").bufferedReader().use { it.readText() }
                }.getOrNull()
            } ?: continue
            val meta = parseHeader(raw)
            val scriptName = meta["name"]?.takeIf { it.isNotBlank() } ?: fileName.removeSuffix(".js")
            val author = meta["author"] ?: ""
            shipped += scriptName to author
            // 用户删过这个内置源 → 不要在启动时把它补回来。
            if (removedKey(scriptName, author) in removed) continue
            val existing = _scripts.value.firstOrNull { it.name == scriptName && it.author == author }
            when {
                // 已装、内容一致、而且上次加载是好的 → 不重新导入，但**要确认它这一轮真的在跑**。
                // 每次启动 SourceManager 都是空的，跳过加载等于这个源今天不工作。
                existing != null && existing.rawScript == raw && existing.loadError == null -> {
                    if (existing.enabled && sourceManager.loaded.value.none { it.script.id == existing.id }) {
                        val err = loadAndRecordError(existing)
                        if (err != null) {
                            _scripts.value = _scripts.value.map {
                                if (it.id == existing.id) it.copy(loadError = err) else it
                            }
                        }
                    }
                }
                // 同名但是用户自己导入的（非内置）→ 尊重用户，不覆盖
                existing != null && !existing.builtin -> Unit
                // 其余都重新导入：没装过、随包内容更新了、或者上次加载失败（再试一次 ——
                // 很多源是服务端临时抽风，下次启动就恢复了）
                else -> runCatching { import(raw, builtin = true) }
            }
        }
        // 卸载已不再随包的内置源（用户导入的不动）。
        val stale = _scripts.value.filter { it.builtin && (it.name to it.author) !in shipped }
        if (stale.isNotEmpty()) {
            stale.forEach { sourceManager.unload(it.id) }
            _scripts.value = _scripts.value - stale.toSet()
            persist()
        }
    }

    /** Result of [import]: [replaced] is the older copy of the same script (same name + author)
     *  that this import updated in place, or null when it was added as a new source. */
    data class Imported(val script: UserScript, val replaced: UserScript?) {
        val message: String
            get() = when {
                // 加载失败也要如实说：脚本进了列表但跑不起来，用户得知道该删还是该等作者修。
                script.loadError != null -> "已添加音源：${script.name}（加载失败：${script.loadError}）"
                replaced == null -> "已添加音源：${script.name}"
                else -> "已更新音源：${script.name}（替换了同名旧版本${replaced.version.takeIf { it.isNotBlank() }?.let { " v$it" } ?: ""}）"
            }
    }

    /**
     * Import a raw script: parse its header, load it, persist. Several sources can coexist; only
     * a script with the same name AND author counts as a new version of an existing one and
     * replaces it (keeping its place in the list). The old runtime is unloaded — previously it
     * stayed loaded in [SourceManager] invisibly — and same-named scripts by different authors
     * are kept side by side instead of silently overwriting each other.
     *
     * **加载失败也照样进列表**（[UserScript.loadError] 记下原因）。内置源失效是常态，
     * 界面上必须能看到它、才能停用或删掉；丢掉它只能让用户困惑"为什么少了一个源"。
     */
    suspend fun import(raw: String, builtin: Boolean = false): Result<Imported> {
        val meta = parseHeader(raw)
        val base = UserScript(
            name = meta["name"] ?: "未命名脚本",
            description = meta["description"] ?: "",
            version = meta["version"] ?: "",
            author = meta["author"] ?: "",
            homepage = meta["homepage"] ?: "",
            rawScript = raw,
            enabled = true,
            builtin = builtin,
        )
        val old = _scripts.value.firstOrNull { it.name == base.name && it.author == base.author }
        val error = loadAndRecordError(base)
        val script = base.copy(loadError = error)
        if (old != null) {
            sourceManager.unload(old.id)
            _scripts.value = _scripts.value.map { if (it.id == old.id) script else it }
        } else {
            _scripts.value = _scripts.value + script
        }
        persist()
        return Result.success(Imported(script, old))
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val script = _scripts.value.firstOrNull { it.id == id } ?: return
        if (enabled) {
            // 重新启用时再试一次加载，并把结果写回（失败也能看到原因）。
            val error = loadAndRecordError(script)
            _scripts.value = _scripts.value.map {
                if (it.id == id) it.copy(enabled = true, loadError = error) else it
            }
        } else {
            sourceManager.unload(id)
            _scripts.value = _scripts.value.map { if (it.id == id) it.copy(enabled = false) else it }
        }
        persist()
    }

    /**
     * 删除音源。随附音源也可以删 —— 一个失效的源留在列表里只会让解析每次先撞一次墙；
     * 删掉之后记进 [removedStore]，重启不会再被 [ensureBuiltInScripts] 补回来。
     * 想找回就用 [restoreBuiltIns]。
     */
    suspend fun remove(id: String) {
        val script = _scripts.value.firstOrNull { it.id == id } ?: return
        sourceManager.unload(id)
        _scripts.value = _scripts.value.filter { it.id != id }
        if (script.builtin) {
            removed = removed + removedKey(script.name, script.author)
            withContext(Dispatchers.IO) { removedStore.save(removed.toList()) }
        }
        persist()
    }

    /** 清空「已删除的内置源」名单，把随包内置脚本重新装回来。 */
    suspend fun restoreBuiltIns() {
        removed = emptySet()
        withContext(Dispatchers.IO) { removedStore.save(emptyList()) }
        ensureBuiltInScripts()
    }

    private suspend fun persist() = withContext(Dispatchers.IO) { store.save(_scripts.value) }

    companion object {
        /** assets 下存放随包随附音源脚本的目录。 */
        private const val BUILTIN_DIR = "builtin_scripts"

        /** 内置源的身份键：同名的不同作者算两个源，和 [import] 的去重口径保持一致。 */
        private fun removedKey(name: String, author: String) = "$name||$author"
        private val headerBlock = Regex("""/\*[\s\S]*?\*/""")
        private val tagLine = Regex("""@(\w+)\s+(.+)""")

        /** Parse the leading KDoc-style header of an lx v4 script (@name / @version / etc). */
        fun parseHeader(raw: String): Map<String, String> {
            val block = headerBlock.find(raw)?.value ?: return emptyMap()
            val out = mutableMapOf<String, String>()
            tagLine.findAll(block).forEach { m ->
                out[m.groupValues[1].lowercase()] = m.groupValues[2].trim().removeSuffix("*").trim()
            }
            return out
        }
    }
}
