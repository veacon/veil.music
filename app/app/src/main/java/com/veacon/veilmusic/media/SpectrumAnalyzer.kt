package com.veacon.veilmusic.media

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Android Visualizer FFT is signed interleaved complex data; sampling rate is milliHertz. */
class SpectrumAnalyzer {
    private val levels = FloatArray(3)
    private val powers = FloatArray(3)
    private val counts = IntArray(3)
    private var levelGateOpen = false

    fun analyze(
        fft: ByteArray,
        samplingRateMilliHz: Int,
        deltaMs: Long,
        rmsMilliBels: Int? = null,
    ): AudioSpectrum {
        if (fft.size < 4 || samplingRateMilliHz <= 0) return AudioSpectrum()
        powers.fill(0f)
        counts.fill(0)
        val hzPerBin = samplingRateMilliHz / 1_000f / fft.size
        // First pair is DC and Nyquist, not a complex bin.
        for (bin in 1 until fft.size / 2) {
            val hz = bin * hzPerBin
            val band = when {
                hz in 40f..<250f -> 0
                hz in 250f..<2_000f -> 1
                hz in 2_000f..10_000f -> 2
                else -> continue
            }
            val real = fft[bin * 2].toFloat()
            val imaginary = fft[bin * 2 + 1].toFloat()
            powers[band] += real * real + imaginary * imaginary
            counts[band]++
        }
        // FFT normalization can amplify near-silence. Gate it using the independent
        // absolute level when the route supports RMS measurement, with hysteresis so
        // a quiet passage cannot toggle the silhouette on every capture callback.
        val audible = if (rmsMilliBels != null) {
            levelGateOpen = rmsMilliBels > if (levelGateOpen) -6_400 else -5_800
            levelGateOpen
        } else true
        var hasSignal = false
        for (band in levels.indices) {
            val rms = sqrt(powers[band] / counts[band].coerceAtLeast(1))
            // Each signed complex FFT component can reach 128. Using 33 as
            // full scale clipped ordinary bass hits and flattened the rhythm.
            val fullScaleMagnitude = 181.02f // sqrt(128^2 + 128^2)
            // Reject ±1 signed-byte quantization noise even if peak/RMS is unavailable.
            val noiseFloor = 1.5f
            val target = if (audible) (ln(1f + (rms - noiseFloor).coerceAtLeast(0f)) /
                ln(1f + fullScaleMagnitude - noiseFloor)).coerceIn(0f, 1f) else 0f
            if (target > 0f) hasSignal = true
            val timeConstant = if (target > levels[band]) 28f else when (band) {
                0 -> 115f
                1 -> 100f
                else -> 75f
            }
            val blend = 1f - exp(-deltaMs.coerceIn(1L, 250L) / timeConstant)
            levels[band] += (target - levels[band]) * blend
        }
        return AudioSpectrum(levels[0], levels[1], levels[2], hasSignal, captured = true)
    }
}
