package com.veacon.veilmusic.util

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 应用级内存压力广播（波次3 P0-8）。
 *
 * `Application.onTrimMemory` 只有一个入口，但「让路」的动作分属不同组件：
 * Coil 图片缓存 → QuickJS 脚本缓存 → 预建下一首播放器 → 背景帧预算。
 * 这里只做分发，具体让路顺序由注册方各自实现（注册顺序即让路顺序）。
 */
object MemoryPressure {

    interface Sink {
        fun onTrim(level: Int)
        /** 压力解除（TRIM_MEMORY_UI_HIDDEN 之后回到前台等）时恢复。 */
        fun onRelaxed() {}
    }

    private val sinks = CopyOnWriteArrayList<Sink>()

    fun register(sink: Sink) {
        sinks.addIfAbsent(sink)
    }

    fun unregister(sink: Sink) {
        sinks.remove(sink)
    }

    fun trim(level: Int) {
        sinks.forEach { runCatching { it.onTrim(level) } }
    }

    fun relaxed() {
        sinks.forEach { runCatching { it.onRelaxed() } }
    }
}
