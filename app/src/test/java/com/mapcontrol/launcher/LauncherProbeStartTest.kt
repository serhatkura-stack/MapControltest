package com.mapcontrol.launcher

import com.mapcontrol.launcher.LauncherProbe.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherProbeStartTest {
    private fun d(debuggable: Boolean = true, extra: Boolean? = null, restored: Boolean = false, history: Boolean = false, running: Boolean = false) =
        LauncherProbe.decide(debuggable, extra, restored, history, running)

    @Test fun neverAutoStartsWithoutExtra() {
        assertEquals(Action.NONE, d())
        assertEquals(Action.NONE, d(restored = true))
    }

    @Test fun startsOncePerExplicitIntent() {
        assertEquals(Action.START, d(extra = true))
        assertEquals(Action.NONE, d(extra = true, running = true))
    }

    @Test fun restoredOrRecentsRelaunchDoesNotRestart() {
        assertEquals(Action.NONE, d(extra = true, restored = true))
        assertEquals(Action.NONE, d(extra = true, history = true))
    }

    @Test fun releaseBuildNeverRuns() {
        assertEquals(Action.NONE, d(debuggable = false, extra = true))
        assertEquals(Action.NONE, d(debuggable = false, extra = false))
    }

    @Test fun falseStops() {
        assertEquals(Action.STOP, d(extra = false, running = true))
    }
}
