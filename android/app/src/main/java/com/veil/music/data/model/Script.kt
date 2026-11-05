package com.veil.music.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** An imported lx-music v4 user source script, mirroring iOS `UserScript`. */
@Serializable
data class UserScript(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val version: String = "",
    val author: String = "",
    val homepage: String = "",
    val rawScript: String,
    val importedAt: Long = System.currentTimeMillis(),
    val enabled: Boolean = true,
    /** true = 随包（assets/builtin_scripts）的音源。随包音源脚本升级时会被新版覆盖；用户自己
     *  导入的同名音源不会被内置版本顶掉。旧数据缺该字段时默认 false（即用户导入）。 */
    val builtin: Boolean = false,
    /**
     * 最近一次加载失败的原因，null = 加载正常。
     *
     * 脚本加载失败**不等于**不该出现在列表里：内置源失效是常见情况（服务端一改就全废），
     * 用户需要看到它、并且能把它删掉或停用。早先失败就直接丢弃，界面上凭空少了几个源，
     * 用户完全不知道它们存在过，也就无从处理。
     */
    val loadError: String? = null,
)

/** Capabilities a script reports via `lx.send('inited', { sources })`. */
@Serializable
data class ScriptCapabilities(
    val sources: Map<SourceID, SourceCapability> = emptyMap(),
)

@Serializable
data class SourceCapability(
    val type: String = "music",
    val actions: List<String> = emptyList(),
    val qualities: List<Quality> = emptyList(),
)
