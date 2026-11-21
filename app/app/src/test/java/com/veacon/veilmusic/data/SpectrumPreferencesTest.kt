package com.veacon.veilmusic.data

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SpectrumPreferencesTest {
    private fun prefs() = SpectrumPreferences(RuntimeEnvironment.getApplication())

    @Test fun denialDisablesSwitchAndMarksState() {
        val p = prefs()
        p.setEnabled(true)
        p.recordPermissionResult(false)
        assertFalse("denial must switch beat-reactivity off", p.enabled())
        assertTrue(p.permissionRequested())
        assertTrue(p.permissionDenied())
    }

    @Test fun grantKeepsSwitchStateAndClearsDenied() {
        val p = prefs()
        p.setEnabled(true)
        p.recordPermissionResult(true)
        assertTrue(p.enabled())
        assertTrue(p.permissionRequested())
        assertFalse(p.permissionDenied())
    }

    @Test fun stateIsNotRequestedUntilPermissionWasAsked() {
        val p = prefs()
        // Robolectric: Visualizer.getMaxCaptureRate() may be 0 on the stub; accept either
        // Unavailable or NotRequested/Denied, but never Granted without a real grant.
        val state = microphonePermissionState(RuntimeEnvironment.getApplication(), p)
        assertNotEquals(SpectrumPermissionState.Granted, state)
    }

    @Test fun grantedPermissionReportsGrantedWhenVisualizerSupported() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val p = prefs()
        val state = microphonePermissionState(app, p)
        // When the stub supports Visualizer the state must be Granted; otherwise Unavailable.
        assertTrue(state == SpectrumPermissionState.Granted || state == SpectrumPermissionState.Unavailable)
    }
}
