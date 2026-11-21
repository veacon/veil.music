package com.veacon.veilmusic.media

/** Measured song output only. Missing capture is silence, never lyric-derived energy. */
data class AudioSpectrum(
    val bass: Float = 0f,
    val mid: Float = 0f,
    val treble: Float = 0f,
    val available: Boolean = false,
    /** A real capture may contain silence; distinguish that from a missing/stale session. */
    val captured: Boolean = available,
)
