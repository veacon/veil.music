package com.veacon.veilmusic.data.network

import android.app.Application
import com.veacon.veilmusic.data.MutPreferences
import com.veacon.veilmusic.data.datasource.DirectLyricsSource
import com.veacon.veilmusic.media.AppleDirectClient
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23],application=Application::class)
class LyricsCancellationTest {
    /** 歌词只走第三方平台（网易云→QQ→酷狗→酷我→咪咕）后，取消 getLyrics 应中断正在进行的
     *  搜索 HTTP 调用，且不会因为取消而误触发下一个平台。 */
    @Test fun windowCancellationCancelsActualHttpCallWithoutStartingFallback()=runBlocking {
        val entered=CompletableDeferred<Unit>();val finish=CountDownLatch(1);val held=AtomicReference<Call>();var requests=0
        val http=OkHttpClient.Builder().addInterceptor {chain->
            requests++;held.set(chain.call());entered.complete(Unit);finish.await()
            if(chain.call().isCanceled()) throw java.io.IOException("cancelled")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body("{}".toResponseBody()).build()
        }.build()
        val apple=AppleDirectClient()
        val prefs=MutPreferences(RuntimeEnvironment.getApplication())
        val thirdParty=com.veacon.veilmusic.data.datasource.ThirdPartyLyrics()
        // 第三方歌词用自己内部 new 的 OkHttpClient，注入 http 到 DirectLyricsSource 只会被 lrclib 用。
        // 这里把 ThirdPartyLyrics 的 http 换成拦截器版本，验证取消传播。
        thirdParty.javaClass.getDeclaredField("http").apply {isAccessible=true}.set(thirdParty,http)
        val source=DirectLyricsSource(apple,prefs,thirdParty,
            com.veacon.veilmusic.data.LyricsScriptPreferences(RuntimeEnvironment.getApplication()),http)
        try {
            withTimeout(5_000L) {
                val request=launch {source.getLyrics("123","cn","song","artist",60L)}
                entered.await();request.cancelAndJoin()
                assertTrue(held.get().isCanceled());assertEquals(1,requests)
            }
        } finally {finish.countDown()}
    }
}
