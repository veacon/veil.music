package com.veacon.veilmusic.data

/**
 * Which audio decoder path ExoPlayer is allowed to use.
 *
 * [SOFTWARE] hands audio to the bundled FFmpeg renderer first (MediaCodec only as a fallback for
 * secure/unsupported formats). It is the default: FFmpeg handles HE-AAC/SBR and odd sample rates
 * identically on every box, which is what keeps playback at exactly 1.0x.
 *
 * [HARDWARE] drops the FFmpeg extension entirely and lets the platform MediaCodec decoders run.
 * That is the cheaper path on paper, but a few TV firmwares ship AAC decoders that report a
 * half-rate sample count for SBR streams — the audio then plays roughly twice as fast. The switch
 * exists so that behaviour can be escaped without shipping a new build.
 */
enum class DecoderMode(val key: String) {
    SOFTWARE("sw"),
    HARDWARE("hw");

    companion object {
        /** Unknown/absent values fall back to the historical default. */
        fun fromKey(raw: String?): DecoderMode = entries.firstOrNull { it.key == raw } ?: SOFTWARE
    }
}
