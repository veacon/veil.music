package com.veacon.veilmusic.media

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 解码器黑名单的存取语义。
 *
 * 三条约束：① 按「机型 | mime」隔离，换机型不误伤；
 * ② 重复上报不重复写；③ 条目有上限，长期使用不会把 prefs 撑爆。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DecoderPolicyTest {

    private fun policy(device: String): DecoderPolicy {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("decoder_policy_test_$device", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return DecoderPolicy(prefs, deviceTag = device)
    }

    @Test fun failureIsScopedToDeviceAndMime() {
        val p = policy("BOX-A")
        p.reportFailure("audio/flac", "OMX.bad.flac.decoder")

        assertTrue("OMX.bad.flac.decoder" in p.blockedFor("audio/flac"))
        // 其它格式不受影响
        assertTrue(p.blockedFor("audio/mp4a-latm").isEmpty())
        // 另一台机型不继承这条黑名单
        assertTrue(policy("BOX-B").blockedFor("audio/flac").isEmpty())
    }

    @Test fun repeatedFailureDoesNotDuplicate() {
        val p = policy("BOX-C")
        repeat(5) { p.reportFailure("audio/flac", "OMX.bad.flac.decoder") }
        assertEquals(1, p.snapshot().split(",").count { it.isNotBlank() })
    }

    @Test fun blankCodecNameIsIgnored() {
        val p = policy("BOX-D")
        p.reportFailure("audio/flac", null)
        p.reportFailure("audio/flac", "")
        assertEquals("none", p.snapshot())
    }

    @Test fun blacklistIsCapped() {
        val p = policy("BOX-E")
        repeat(50) { p.reportFailure("audio/flac", "codec-$it") }
        assertTrue("黑名单未截断", p.snapshot().split(",").count { it.isNotBlank() } <= 32)
        // 最新的那条一定还在（截断保留最近）
        assertTrue("codec-49" in p.blockedFor("audio/flac"))
    }

    @Test fun unknownMimeHasNoBlacklist() {
        val p = policy("BOX-F")
        assertFalse(p.blockedFor("audio/whatever").isNotEmpty())
    }
}
