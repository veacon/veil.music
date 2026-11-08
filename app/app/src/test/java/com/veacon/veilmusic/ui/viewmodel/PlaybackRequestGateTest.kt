package com.veacon.veilmusic.ui.viewmodel

import org.junit.Assert.*
import org.junit.Test

class PlaybackRequestGateTest {
    @Test fun newerSelectionInvalidatesThePreviousRequest() {
        val gate = PlaybackRequestGate()
        val first = gate.select()
        val second = gate.select()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
        assertTrue(second > first)
    }

    @Test fun pendingPauseSurvivesSourceCompletion() {
        val preparation = SourcePreparation()
        preparation.begin(1)
        preparation.toggle()
        assertTrue(preparation.isPending)
        assertEquals(false, preparation.finish(1))
        assertFalse(preparation.isPending)
    }

    @Test fun pendingPauseThenPlayStartsTheSelectedSource() {
        val preparation = SourcePreparation()
        preparation.begin(1)
        preparation.toggle()
        preparation.toggle()
        assertEquals(true, preparation.finish(1))
    }

    @Test fun skippedSourceCannotConsumeNewSongIntent() {
        val preparation = SourcePreparation()
        preparation.begin(1)
        preparation.begin(2)
        preparation.pause()
        assertNull(preparation.finish(1))
        assertTrue(preparation.isPending)
        assertEquals(false, preparation.finish(2))
    }

    @Test fun cancellationPreventsOldSourceFromStarting() {
        val preparation = SourcePreparation()
        preparation.begin(1)
        preparation.cancel()
        assertNull(preparation.finish(1))
    }
}
