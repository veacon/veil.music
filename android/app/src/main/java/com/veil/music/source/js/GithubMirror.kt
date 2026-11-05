package com.veil.music.source.js

/**
 * GitHub 加速前缀。
 *
 * 音源脚本（以及它们在初始化阶段拉的配置）有不少托管在 GitHub 上。大陆直连
 * `raw.githubusercontent.com` 经常是几十 KB/s 甚至直接超时，于是「随包音源脚本加载失败」
 * 看起来像脚本坏了。这里把一条 GitHub 链接展开成「加速前缀优先、直连兜底」的候选表。
 *
 * 只改写**仓库内文件 / release 附件**这类链接；`api.github.com` 之类保持原样 ——
 * 前缀代理不改写 Host，代理 API 请求通常直接 400。
 */
object GithubMirror {

    /** 按可用性排序。前缀代理的用法是「把原地址原样拼在前面」。 */
    private val PROXIES = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://ghproxy.net/",
        "https://gh.ddlc.top/",
    )

    /** 这条 URL 是否值得走加速。 */
    fun isGithubFile(url: String): Boolean {
        val u = url.trim()
        if (u.isEmpty()) return false
        if (u.contains("raw.githubusercontent.com/")) return true
        if (u.contains("objects.githubusercontent.com/")) return true
        if (u.contains("github.com/")) {
            // 仓库文件 / release / 源码包；排除 api.github.com 与 gist 之类。
            return u.contains("/raw/") || u.contains("/releases/download/") ||
                u.contains("/archive/") || u.contains("/blob/")
        }
        return false
    }

    /**
     * 候选地址：**加速前缀在前**，原地址永远排最后。
     *
     * 之前脚本初始化要拉 GitHub 上的配置，直连等 15 秒超时才算失败；把镜像放前面之后，
     * 正常情况几百毫秒就回来了，镜像全挂才回落到直连。
     */
    fun candidates(rawUrl: String): List<String> {
        val url = rawUrl.trim()
        if (!isGithubFile(url)) return listOf(url)
        val out = ArrayList<String>(PROXIES.size + 1)
        PROXIES.forEach { out += it + url }
        out += url
        return out
    }
}
