package com.veil.music

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.veil.music.ui.AppShell
import com.veil.music.ui.viewmodel.NavigationViewModel
import com.veil.music.ui.viewmodel.PlayerViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val navVm: NavigationViewModel by viewModels()
    private val playerVm: PlayerViewModel by viewModels()

    private val pipActionName = "com.veil.music.PIP_TOGGLE"
    private var pipReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            com.veil.music.ui.theme.VeilTheme { AppShell() }
        }
        // 必须放在 setContent 之后：Activity 的窗口在 setContentView 之前还没有 DecorView，
        // 此时访问 window.insetsController 会在 PhoneWindow 内部对 mDecor 取方法 → NPE。
        // 2.2.0 把这一行写在 setContent 之前，导致任何设备一启动就闪退。
        applyImmersiveMode()
        lifecycleScope.launch {
            playerVm.state.map { it.isPlaying }.distinctUntilChanged().collect {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                    runCatching { setPictureInPictureParams(pipParams()) }
                }
            }
        }
    }

    /**
     * 只在 API 26+ 调用（`enterPip()` 已做版本判断）。
     * `PictureInPictureParams.Builder` / `RemoteAction` 都是 O 才引入的。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipParams(): PictureInPictureParams {
        val playing = playerVm.state.value.isPlaying
        val icon = Icon.createWithResource(
            this,
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
        )
        val label = if (playing) "Pause" else "Play"
        val pi = PendingIntent.getBroadcast(
            this, 0,
            Intent(pipActionName).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(RemoteAction(icon, label, label, pi)))
            .build()
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    /**
     * `Activity.isInPictureInPictureMode` 是 API 24 才有的；minSdk 23 的老设备上直接调用会
     * NoSuchMethodError。API 23 上 PiP 本来就不存在，恒为 false 即可。
     */
    private fun isInPipCompat(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    /**
     * 全屏：隐藏状态栏与导航栏。
     *
     * 这是台电视应用，系统栏本身就是干扰 —— 电视上根本没有状态栏还好，手机 / 平板投屏时
     * 顶部会多出一条时间电量，把 56dp 的顶部导航挤歪。主题里的 windowFullscreen 只管启动
     * 那一刻，部分系统（MIUI / EMUI）在窗口重新获得焦点时会把状态栏画回来，所以这里再补一次。
     *
     * 用 androidx 的兼容实现（api < 30 自动走 systemUiVisibility），并整体包在 runCatching 里：
     * 全屏只是锦上添花，任何机型上失败都不能把启动流程带走。
     */
    private fun applyImmersiveMode() {
        runCatching {
            // 这行不是废话：onCreate 早期 DecorView 还没建出来，先取一次把它 create 掉，
            // 后面的 insetsController 才不会拿到 null。
            val decor = window.decorView
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, decor).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 画中画是个独立的窗口，这时候别去动系统栏，否则小窗里会出现一条黑边。
        if (hasFocus && !isInPipCompat()) applyImmersiveMode()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 只有真的在放的时候才进 PiP —— 暂停状态弹个静止小窗没有意义。
        if (navVm.isOnNowPlaying && playerVm.state.value.isPlaying && !isInPipCompat()) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        playerVm.setPipMode(isInPip)
        if (isInPip) {
            if (pipReceiver == null) {
                pipReceiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) {
                        if (i?.action == pipActionName) {
                            playerVm.togglePlayPause()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                                runCatching { setPictureInPictureParams(pipParams()) }
                            }
                        }
                    }
                }
                val filter = IntentFilter(pipActionName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(pipReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    registerReceiver(pipReceiver, filter)
                }
            }
        } else {
            pipReceiver?.let { runCatching { unregisterReceiver(it) } }
            pipReceiver = null
        }
    }

    override fun onStop() {
        super.onStop()
        // 后台播放由设置项决定：关掉时离开应用即暂停。
        if (!isInPipCompat() && !playerVm.state.value.backgroundPlayEnabled) playerVm.pause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU -> {
                    if (navVm.isOnNowPlaying) navVm.toggleQueuePanel()
                    else navVm.navigateToNowPlaying()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { playerVm.togglePlayPause(); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT     -> { playerVm.next(); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { playerVm.prev(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { playerVm.next(); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND       -> { playerVm.prev(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
