package com.veacon.veilmusic.data

/**
 * Which audio decoder path ExoPlayer is allowed to use.
 *
 * [SOFTWARE] hands audio to the bundled FFmpeg renderer first (MediaCodec only as a fallback for
 * secure/unsupported formats). FFmpeg handles HE-AAC/SBR and odd sample rates identically on every
 * box; it remains the escape hatch when a platform decoder misbehaves.
 *
 * [HARDWARE] drops the FFmpeg extension entirely and lets the platform MediaCodec decoders run.
 * This is the factory default. A few TV firmwares ship AAC decoders that report a half-rate sample
 * count for SBR streams (audio then plays roughly twice as fast) — the settings switch exists so
 * users can fall back to SOFTWARE without shipping a new build.
 */
enum class DecoderMode(val key: String) {
    SOFTWARE("sw"),
    HARDWARE("hw");

    companion object {
        /** 出厂默认：硬解 / MediaCodec。已有持久化选择始终优先于默认值。 */
        val DEFAULT = HARDWARE

        /** Unknown/absent values fall back to the factory default. */
        fun fromKey(raw: String?): DecoderMode = entries.firstOrNull { it.key == raw } ?: DEFAULT
    }
}
