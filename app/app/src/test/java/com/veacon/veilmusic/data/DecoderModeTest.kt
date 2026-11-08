package com.veacon.veilmusic.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DecoderModeTest {
    @Test fun storedKeysRoundTrip() {
        for (mode in DecoderMode.entries) assertEquals(mode, DecoderMode.fromKey(mode.key))
    }

    @Test fun missingOrUnknownPreferenceFallsBackToTheHistoricalSoftwarePath() {
        assertEquals(DecoderMode.SOFTWARE, DecoderMode.fromKey(null))
        assertEquals(DecoderMode.SOFTWARE, DecoderMode.fromKey(""))
        assertEquals(DecoderMode.SOFTWARE, DecoderMode.fromKey("auto"))
    }
}
