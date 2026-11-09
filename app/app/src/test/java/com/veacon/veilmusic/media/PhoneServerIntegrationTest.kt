package com.veacon.veilmusic.media

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import android.net.ConnectivityManager
import com.veacon.veilmusic.R
import com.veacon.veilmusic.data.*
import com.veacon.veilmusic.data.auth.TokenVerifier
import com.veacon.veilmusic.data.auth.TokenVerification
import com.veacon.veilmusic.data.repository.MusicRepository
import com.veacon.veilmusic.ui.viewmodel.OnboardingViewModel
import kotlinx.coroutines.*
import java.net.BindException
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = Application::class)
class PhoneServerIntegrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val verifier = TokenVerifier { TokenVerification.Valid }

    private fun server(): InAppWebServer {
        clearPhoneNetworks(context)
        addPhoneNetwork(context, false, "192.168.1.4")
        val type = Class.forName("sun.misc.Unsafe")
        val unsafe = type.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val repo = type.getMethod("allocateInstance", Class::class.java).invoke(unsafe, MusicRepository::class.java) as MusicRepository
        val lx = com.veacon.veilmusic.luoxue.LxPlayback(context, com.veacon.veilmusic.data.AudioQualityPreferences(context))
        val importer = com.veacon.veilmusic.data.local.PlaylistImporter(okhttp3.OkHttpClient(),
            com.veacon.veilmusic.data.local.LocalLibraryStore(context, com.veacon.veilmusic.data.di.NetworkModule.provideMoshi()))
        return InAppWebServer(context, MutPreferences(context), repo, LyricsOffsetPreferences(context),
            CrossfadePreferences(context), StandalonePreferences(context), ServerPreferences(context), BeatAnalyzer(), verifier, lx, importer)
    }

    private fun awaitReady(server: InAppWebServer) = runBlocking {
        withTimeout(5000) { while (!server.running) delay(10) }
    }

    private fun get(port: Int, path: String): String = Socket("127.0.0.1", port).use { client ->
        client.soTimeout = 5000
        client.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray())
        client.getInputStream().bufferedReader().readText()
    }

    @Test fun conflictServesPhonePageAndStatusOnAdvertisedPortDespiteBusyEventPort() {
        val blockers = listOf(8080, 8081).mapNotNull { try { ServerSocket(it) } catch (_: BindException) { null } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = server()
        try {
            assertEquals("", server.serverUrl())
            server.boot(scope)
            awaitReady(server)
            assertNotEquals(8080, server.port)
            assertNotEquals(8081, server.port)
            assertEquals("http://192.168.1.4:${server.port}", server.serverUrl())
            val page = get(server.port, "/")
            assertTrue(page.startsWith("HTTP/1.1 200 OK"))
            assertTrue(page.contains("Apple Music"))
            val status = get(server.port, "/status").substringAfter("\r\n\r\n")
            assertEquals(server.serverUrl(), JSONObject(status).getString("url"))
            assertFalse(JSONObject(status).getBoolean("hasMUT"))
        } finally { server.stop(); scope.cancel(); blockers.forEach { it.close() } }
    }

    @Test fun stopHidesAddressAndRestartRebindsWithoutStaleCleanupClosingNewListener() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = server()
        try {
            repeat(4) {
                server.boot(scope)
                awaitReady(server)
                assertTrue(get(server.port, "/status").startsWith("HTTP/1.1 200 OK"))
                server.stop()
                assertFalse(server.running)
                assertEquals(0, server.port)
                assertEquals("", server.serverUrl())
            }
        } finally { server.stop(); scope.cancel() }
    }

    @Test fun onboardingTracksBoundAddressAndStopWithoutWaitingForTokenVerification() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = server()
        val store = ViewModelStore()
        val vm = OnboardingViewModel(OnboardingPreferences(context), MutPreferences(context), server, verifier)
        store.put("login", vm)
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("", vm.state.value.phoneUrl)
            assertTrue(vm.state.value.phoneServerUnavailable)
            server.boot(scope)
            awaitReady(server)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(server.serverUrl(), vm.state.value.phoneUrl)
            assertFalse(vm.state.value.phoneServerUnavailable)
            server.stop()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("", vm.state.value.phoneUrl)
            assertTrue(vm.state.value.phoneServerUnavailable)
        } finally { server.stop(); scope.cancel(); store.clear() }
    }

    @Test fun ethernetBootPublishesRealWiredAddressWhenWifiHasNoIp() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = server()
        clearPhoneNetworks(context)
        addPhoneNetwork(context, true, "192.168.1.44")
        try {
            server.boot(scope)
            awaitReady(server)
            assertEquals("http://192.168.1.44:${server.port}", server.serverUrl())
            val status = JSONObject(get(server.port, "/status").substringAfter("\r\n\r\n"))
            assertEquals(server.serverUrl(), status.getString("url"))
        } finally { server.stop(); scope.cancel() }
    }

    @Test fun waitingHotspotAndDisconnectUpdateLoginImmediatelyAndRetainListeningPort() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = server()
        clearPhoneNetworks(context)
        val network = addPhoneNetwork(context, false, null)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val store = ViewModelStore()
        val vm = OnboardingViewModel(OnboardingPreferences(context), MutPreferences(context), server, verifier)
        store.put("login", vm)
        try {
            server.boot(scope)
            awaitReady(server)
            shadowOf(Looper.getMainLooper()).idle()
            val port = server.port
            assertEquals("", vm.state.value.phoneUrl)
            assertEquals(R.string.phone_network_waiting, vm.state.value.phoneSetupMessage)
            val callbacks = shadowOf(cm).networkCallbacks.toList()
            callbacks.forEach { it.onLinkPropertiesChanged(network, phoneLinks("192.168.43.20")) }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("http://192.168.43.20:$port", vm.state.value.phoneUrl)
            assertNull(vm.state.value.phoneSetupMessage)
            callbacks.forEach { it.onLinkPropertiesChanged(network, phoneLinks("192.168.99.20")) }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("http://192.168.99.20:$port", vm.state.value.phoneUrl)
            assertEquals(port, server.port)
            assertTrue(get(port, "/status").contains("http://192.168.99.20:$port"))
            callbacks.forEach { it.onLost(network) }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("", vm.state.value.phoneUrl)
            assertEquals(R.string.msg_172, vm.state.value.phoneSetupMessage)
            assertEquals(port, server.port)
        } finally { server.stop(); scope.cancel(); store.clear() }
    }

    /** 头 + 表单正文一次性写入，复现浏览器把整个 POST 放在同一个 TCP 包里的场景。 */
    private fun post(port: Int, path: String, form: String): String = Socket("127.0.0.1", port).use { client ->
        client.soTimeout = 8000
        val body = form.toByteArray(Charsets.UTF_8)
        val header = "POST $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        client.getOutputStream().write(header.toByteArray(Charsets.UTF_8) + body)
        client.getOutputStream().flush()
        client.getInputStream().bufferedReader(Charsets.UTF_8).readText()
    }

    /** 回归：扫码打开手机配置页后，粘贴中文歌曲列表导入歌单必须收到响应并落库。 */
    @Test fun playlistImportPostWithChineseBodyIsReceivedIntact() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = server().also { it.setEnabled(true) }
        try {
            server.boot(scope)
            awaitReady(server)
            val text = java.net.URLEncoder.encode("晴天 - 周杰伦\n富士山下 - 陈奕迅", "UTF-8")
            // 显式固定中文：本测试回归的是「中文正文不丢失」，响应文案随界面语言切换。
            val response = post(server.port, "/playlist-import?json=1&lang=zh", "url=&text=$text")
            assertTrue(response, response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response, response.contains("已导入"))
            assertTrue(response, response.contains("文本歌单"))
            // 两行都要解析出来：正文被截断时只会剩 1 首。
            assertTrue(response, response.contains("2 首"))
        } finally { server.stop(); scope.cancel() }
    }

    @Test fun disabledServerDoesNotPublishANonfunctionalQrAddress() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = server()
        try {
            server.setEnabled(false)
            server.boot(scope)
            assertFalse(server.running)
            assertEquals("", server.serverUrl())
        } finally { server.stop(); scope.cancel() }
    }
}
