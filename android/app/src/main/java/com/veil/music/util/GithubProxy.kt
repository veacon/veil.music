package com.veil.music.util

/**
 * GitHub 访问线路：4 条公共加速 + 1 条直连。
 *
 * 电视盒子一般连不上 `api.github.com` / `github.com` —— 检查更新、下载 APK 都会卡在
 * 超时上。这里给 4 条国内可达的公共加速线路，外加一条「直连」给网络本来就能通
 * GitHub 的用户（家里挂了代理、或者干脆在海外），用户在「设置 → 关于 → 检查更新」
 * 下面选一条即可；选中的那条排在最前，其余线路仍然兜底，所以哪怕首选线路当天挂了，
 * 检查也只会慢一点，不会直接失败。
 *
 * [prefix] 的用法就是「拼在前面」：`prefix + "https://api.github.com/repos/..."`，
 * 这是 gh-proxy 系列约定的格式，四条加速线路都支持；[DIRECT] 的 prefix 是空串，
 * 拼出来就是原始地址。
 */
enum class GithubProxy(
    val label: String,
    val prefix: String,
    val desc: String,
) {
    /** 实测能代理 API 的一条，默认就用它。 */
    GH_PROXY(
        label = "gh-proxy.com",
        prefix = "https://gh-proxy.com/",
        desc = "综合线路：API 与 Release 文件都能加速（默认）",
    ),

    /** 老牌线路，Release 下载稳，API 偶尔超时（超时会自动换下一条）。 */
    GHPROXY_NET(
        label = "ghproxy.net",
        prefix = "https://ghproxy.net/",
        desc = "老牌线路：Release 文件稳定，API 偏慢",
    ),

    GHFAST(
        label = "ghfast.top",
        prefix = "https://ghfast.top/",
        desc = "下载较快，API 能力有限",
    ),

    GH_PROXY_NET(
        label = "gh-proxy.net",
        prefix = "https://gh-proxy.net/",
        desc = "备用线路：前三条都失效时用",
    ),

    /**
     * 直连：不经过任何加速站，直接用原始 `github.com` / `api.github.com` 地址。
     *
     * 放在最后一条，既是列表里的第五个选项，也是兜底顺序里的最后一环 —— 加速站全挂、
     * 而本机网络其实能通 GitHub 时，仍然能检查更新。
     */
    DIRECT(
        label = "直连",
        prefix = "",
        desc = "不走加速站，直接访问 GitHub；网络本身能通时最快最稳",
    ),
    ;

    /** 这条线路是不是「不用加速」。 */
    val isDirect: Boolean get() = prefix.isEmpty()

    /** 展示用：把前缀里的协议与结尾斜杠去掉（直连显示 github.com）。 */
    val host: String get() = if (isDirect) "github.com" else prefix.removePrefix("https://").trimEnd('/')

    companion object {
        fun fromName(name: String?): GithubProxy =
            entries.firstOrNull { it.name == name } ?: GH_PROXY
    }
}
