package com.veacon.veilmusic.ui.viewmodel

import android.app.Application
import com.veacon.veilmusic.data.CrossfadePreferences
import com.veacon.veilmusic.ui.background.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23],application=Application::class)
class V9PreferencesTest {
    @Test fun freshCrossfadeDefaultsToFiveSecondsAndExplicitUserValueIsPreserved() {
        val context=RuntimeEnvironment.getApplication()
        assertEquals(5000L,CrossfadePreferences(context).getDuration())
        CrossfadePreferences(context).setDuration(9000)
        assertEquals(9000L,CrossfadePreferences(context).getDuration())
    }
    @Test fun oldBackgroundConstraintsConsolidateWithoutOverridingOffOrStatic() {
        // P0-7：低电量档已废弃，不再自动降到 POWER_SAVE。
        assertEquals(FluidQuality.STATIC,unifiedFluidQuality(FluidQuality.STANDARD,true))
        assertEquals(FluidQuality.STANDARD,unifiedFluidQuality(FluidQuality.STANDARD,false))
        assertEquals(FluidQuality.OFF,unifiedFluidQuality(FluidQuality.OFF,true))
        assertEquals(FluidQuality.STATIC,unifiedFluidQuality(FluidQuality.STATIC,false))
    }
}
