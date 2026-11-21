package com.veacon.veilmusic.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * 设备档位的**唯一判定点**（波次3 P1-2）。
 *
 * 以前内存/GPU 相关的阈值散落在 Coil 配置、背景渲染、歌词离屏层、诊断面板里，
 * 各自拍脑袋；这里统一成一份可复现的判定，日志里也能直接看到结论。
 */
object DeviceTuning {

    data class Profile(
        /** ActivityManager.isLowRamDevice：系统本身认为这是低内存机。 */
        val lowRam: Boolean,
        /** ActivityManager.memoryClass：单进程可用的 Java 堆上限（MB）。 */
        val memoryClassMb: Int,
        val totalRamMb: Int,
        /** 粗分档：0=低端 1=中端 2=高端。 */
        val tier: Int,
        /** Coil 内存缓存上限（P1-1）。 */
        val coilCacheMb: Int,
        /** 歌词列是否默认走离屏合成（P1-5）。 */
        val lyricOffscreen: Boolean,
    ) {
        val label: String
            get() = when (tier) {
                0 -> "low"
                1 -> "mid"
                else -> "high"
            }
    }

    @Volatile private var cached: Profile? = null

    fun profile(context: Context): Profile {
        cached?.let { return it }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryClass = am?.memoryClass ?: 128
        val lowRam = am?.isLowRamDevice ?: false
        val totalRam = ((am?.let { ActivityManager.MemoryInfo().also { m -> it.getMemoryInfo(m) }.totalMem } ?: 0L) / (1 shl 20)).toInt()

        val tier = when {
            lowRam || totalRam in 1..1200 || memoryClass <= 96 -> 0
            totalRam <= 2500 || memoryClass <= 192 -> 1
            else -> 2
        }
        // P1-1：1GB 机（tier 0）24MB，中端 48MB，其余保持原来的 64MB。
        val coilMb = when (tier) {
            0 -> 24
            1 -> 48
            else -> 64
        }
        val profile = Profile(
            lowRam = lowRam,
            memoryClassMb = memoryClass,
            totalRamMb = totalRam,
            tier = tier,
            coilCacheMb = coilMb,
            // P1-5：低端机不做离屏合成（省一次全屏 layer），中/高端才开。
            lyricOffscreen = tier > 0 && Build.VERSION.SDK_INT >= 29,
        )
        cached = profile
        return profile
    }

    /** 供设置页/诊断面板打印：判定结果与依据一眼可读。 */
    fun describe(context: Context): String {
        val p = profile(context)
        return "tier=${p.label} lowRam=${p.lowRam} memoryClass=${p.memoryClassMb}MB " +
            "totalRam=${p.totalRamMb}MB coil=${p.coilCacheMb}MB lyricOffscreen=${p.lyricOffscreen}"
    }
}
