package com.veacon.veilmusic.data.auth

/**
 * 手机扫码登录配对页所用的 MusicKit 开发者令牌（ES256 JWT）。
 *
 * 取自上游 am_atv v0.7.0 发布包（https://github.com/Sam20060720/am_atv/releases）
 * 内置的开发者令牌，与上游配对页使用同一枚令牌，保证 MusicKit.configure 与
 * Apple 官方授权弹窗可用。抓 music.apple.com 得到的匿名 Bearer 不是合法的
 * MusicKit 开发者令牌，会让 configure 失败、授权框弹不出来。
 *
 * header : {"alg":"ES256","kid":"269C9KKFCU","typ":"JWT"}
 * payload: {"iss":"8V22V5X788","iat":1792837677,"exp":1808389737}（2027-04-22 到期）
 *
 * 到期后需从 am_atv 新版发布包重新提取并替换 [VALUE]。
 */
internal object MusicKitDeveloperToken {
    const val VALUE: String =
        "eyJhbGciOiJFUzI1NiIsImtpZCI6IjI2OUM5S0tGQ1UiLCJ0eXAiOiJKV1QifQ." +
            "eyJpc3MiOiI4VjIyVjVYNzg4IiwiaWF0IjoxNzkyODM3Njc3LCJleHAiOjE4MDgzODk3Mzd9." +
            "JoO6KwyJzTHZeEHydQz7p4TNog-ah9NtwdLX7ULlyfkh2Uzp-mAiXbWvbPUGHC650jW7wN-HOwnLiKBjP1CD2Q"
}
