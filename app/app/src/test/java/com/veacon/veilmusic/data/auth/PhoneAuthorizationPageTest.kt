package com.veacon.veilmusic.data.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 手机扫码配对页：MusicKit.configure 必须用真正的开发者令牌，
 * 且 Apple 授权弹窗里的请求方名称要显示电视的局域网 IP:端口（authority）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PhoneAuthorizationPageTest {
    @Test fun bundledDeveloperTokenIsAValidEs256Jwt() {
        val token = MusicKitDeveloperToken.VALUE
        val parts = token.split('.')
        assertTrue(parts.size == 3)
        assertTrue(parts.all { it.isNotBlank() })
        assertTrue(parts[0].startsWith("eyJ")) // base64url of {"alg":"ES256",...}
        // exp must still be in the future (1808389737 = 2027-04-22 UTC).
        val payload = String(
            java.util.Base64.getUrlDecoder().decode(parts[1] + "=".repeat((4 - parts[1].length % 4) % 4)),
            Charsets.UTF_8,
        )
        assertTrue(payload.contains("\"iss\":\"8V22V5X788\""))
        val exp = Regex("\"exp\":(\\d+)").find(payload)?.groupValues?.get(1)?.toLongOrNull()
        assertTrue(exp != null && exp!! > System.currentTimeMillis() / 1000)
    }

    @Test fun pageUsesBundledTokenAndAuthorityAsAppName() {
        val authority = "192.168.1.16:35089"
        val html = phoneMusicAuthorizationPage(
            developerToken = MusicKitDeveloperToken.VALUE,
            appBuild = "2.3.14",
            languageTag = "zh-CN",
            path = "/pair/abc/",
            appName = authority,
        )
        assertTrue(html.contains("developerToken:\"${MusicKitDeveloperToken.VALUE}\""))
        assertTrue(html.contains("app:{name:\"$authority\""))
        assertFalse(html.contains("name:'vmlx tv'"))
        assertTrue(html.contains("musickit.js"))
    }
}
