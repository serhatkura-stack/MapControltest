package com.mapcontrol.comfort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ComfortPlanTest {

    private val cs = ComfortPlan.MODULE_CAR_SETTING
    private val hv = ComfortPlan.MODULE_HVAC
    private val vr = ComfortPlan.MODULE_VR

    @Test fun whitelistIsExactly() {
        val expected = setOf(
            ComfortPlan.Write(cs, 10, 2), ComfortPlan.Write(cs, 10, 1),
            ComfortPlan.Write(cs, 10, 3), ComfortPlan.Write(cs, 10, 6), ComfortPlan.Write(cs, 10, 0),
            ComfortPlan.Write(vr, 124, 1), ComfortPlan.Write(vr, 124, 2), ComfortPlan.Write(vr, 124, 3),
            ComfortPlan.Write(hv, 24, 2), ComfortPlan.Write(hv, 80, 2),
            ComfortPlan.Write(hv, 65, 0), ComfortPlan.Write(hv, 65, 1),
            ComfortPlan.Write(hv, 24, 0), ComfortPlan.Write(hv, 24, 1),
            ComfortPlan.Write(hv, 80, 0), ComfortPlan.Write(hv, 80, 1),
            ComfortPlan.Write(cs, 159, 0), ComfortPlan.Write(cs, 159, 1),
            ComfortPlan.Write(cs, 160, 0), ComfortPlan.Write(cs, 160, 1),
        )
        assertEquals(expected, ComfortPlan.ALLOWED)
    }

    @Test fun moduleConstantsMatchVdEventCarInfo() {
        assertEquals(327681, cs)
        assertEquals(327690, hv)
        assertEquals(327696, vr)
    }

    @Test fun everythingElseIsRejected() {
        val modules = listOf(cs, hv, vr, 327683, 0)
        var rejected = 0
        for (m in modules) for (id in 0..200) for (v in -1..8) {
            val w = ComfortPlan.Write(m, id, v)
            if (w in ComfortPlan.ALLOWED) {
                assertEquals(w, ComfortPlan.validate(w))
            } else {
                try {
                    ComfortPlan.validate(w)
                    fail("izinli olmamalıydı: $w")
                } catch (e: ComfortPlan.WriteNotAllowed) {
                    rejected++
                }
            }
        }
        assertTrue(rejected > 5000)
    }

    @Test fun hudCommandIdAndOtherHvacIdsAreRejected() {
        for (w in listOf(
            ComfortPlan.Write(327683, 200, 1), ComfortPlan.Write(cs, 10, 4), ComfortPlan.Write(cs, 10, 5),
            ComfortPlan.Write(cs, 160, 2), ComfortPlan.Write(hv, 65, 2), ComfortPlan.Write(vr, 124, 0), ComfortPlan.Write(vr, 124, 4), ComfortPlan.Write(hv, 65, 3), ComfortPlan.Write(cs, 159, 2),
            ComfortPlan.Write(hv, 10, 2), ComfortPlan.Write(vr, 10, 2), ComfortPlan.Write(cs, 124, 1),
        )) {
            try {
                ComfortPlan.validate(w)
                fail("izinli olmamalıydı: $w")
            } catch (e: ComfortPlan.WriteNotAllowed) {
                assertEquals(w, e.write)
            }
        }
    }

    @Test fun everyParsedWriteIsWhitelisted() {
        val cmds = listOf("shade_open", "shade_close", "shade_open_vr", "shade_close_vr", "shade_stop", "shade_stop_vr", "shade_open_stop",
            "defrost_front", "defrost_front_max", "defrost_rear", "defrost_rear_both", "wheel_heat")
        for (c in cmds) for (v in 0..1) for (stop in listOf(null, 3, 6, 0)) {
            // shade_stop_vr yalnızca stop=3 kabul eder; diğer stop değerleri reddedilir (ayrı testte).
            if (c == "shade_stop_vr" && stop != null && stop != 3) continue
            val cmd = ComfortPlan.parse(c, v, null, stop)
            for (s in cmd.steps) if (s is ComfortPlan.Step.Send) assertTrue(s.write in ComfortPlan.ALLOWED)
        }
    }

    private fun sends(cmd: ComfortPlan.Command) =
        cmd.steps.filterIsInstance<ComfortPlan.Step.Send>().map { it.write }

    @Test fun shadeCommands() {
        assertEquals(listOf(ComfortPlan.Write(cs, 10, 2)), sends(ComfortPlan.parse("shade_open")))
        assertEquals(listOf(ComfortPlan.Write(cs, 10, 1)), sends(ComfortPlan.parse("shade_close")))
        assertEquals(listOf(ComfortPlan.Write(vr, 124, 1)), sends(ComfortPlan.parse("shade_open_vr")))
        assertEquals(listOf(ComfortPlan.Write(vr, 124, 2)), sends(ComfortPlan.parse("shade_close_vr")))
        assertEquals(listOf(ComfortPlan.Write(cs, 10, 3)), sends(ComfortPlan.parse("shade_stop")))
        assertEquals(listOf(ComfortPlan.Write(cs, 10, 0)), sends(ComfortPlan.parse("shade_stop", stop = 0)))
    }

    @Test fun openStopSequenceAndDefaults() {
        val c = ComfortPlan.parse("shade_open_stop", afterMs = 1500, stop = 6)
        assertEquals(
            listOf(
                ComfortPlan.Step.Send(ComfortPlan.Write(cs, 10, 2)), ComfortPlan.Step.Wait(1500),
                ComfortPlan.Step.Send(ComfortPlan.Write(cs, 10, 6)),
            ),
            c.steps,
        )
        assertEquals(6000L, c.watchMs)
        val d = ComfortPlan.parse("shade_open_stop")
        assertEquals(ComfortPlan.Step.Wait(2000), d.steps[1])
        assertEquals(ComfortPlan.Write(cs, 10, 3), (d.steps[2] as ComfortPlan.Step.Send).write)
    }

    @Test fun badStopAndAfterAreRejected() {
        for (stop in listOf(1, 2, 4, 5, -1, 7, 255)) rejects { ComfortPlan.parse("shade_open_stop", stop = stop) }
        for (stop in listOf(1, 2, 5)) rejects { ComfortPlan.parse("shade_stop", stop = stop) }
        for (after in listOf(-1, 10_001, 1_000_000)) rejects { ComfortPlan.parse("shade_open_stop", afterMs = after) }
    }

    @Test fun onOffCommands() {
        assertEquals(listOf(ComfortPlan.Write(hv, 65, 1)), sends(ComfortPlan.parse("defrost_front", 1)))
        assertEquals(listOf(ComfortPlan.Write(hv, 24, 0)), sends(ComfortPlan.parse("defrost_front_max", 0)))
        assertEquals(listOf(ComfortPlan.Write(hv, 80, 1)), sends(ComfortPlan.parse("defrost_rear", 1)))
        assertEquals(
            listOf(ComfortPlan.Write(hv, 80, 0), ComfortPlan.Write(cs, 159, 0)),
            sends(ComfortPlan.parse("defrost_rear_both", 0)),
        )
        assertEquals(listOf(ComfortPlan.Write(cs, 160, 1)), sends(ComfortPlan.parse("wheel_heat", 1)))
    }

    @Test fun defrostOffCandidateTwo() {
        assertEquals(listOf(ComfortPlan.Write(hv, 24, 2)), sends(ComfortPlan.parse("defrost_front_max", 2)))
        assertEquals(listOf(ComfortPlan.Write(hv, 80, 2)), sends(ComfortPlan.parse("defrost_rear", 2)))
        for (c in listOf("defrost_front", "defrost_rear_both", "wheel_heat")) rejects { ComfortPlan.parse(c, 2) }
    }

    @Test fun vrStopCandidate() {
        assertEquals(listOf(ComfortPlan.Write(vr, 124, 3)), sends(ComfortPlan.parse("shade_stop_vr")))
        assertEquals(listOf(ComfortPlan.Write(vr, 124, 3)), sends(ComfortPlan.parse("shade_stop_vr", stop = 3)))
        for (stop in listOf(0, 1, 2, 6)) rejects { ComfortPlan.parse("shade_stop_vr", stop = stop) }
    }

    @Test fun onOffNeedsValidV() {
        for (c in listOf("defrost_front", "defrost_front_max", "defrost_rear", "defrost_rear_both", "wheel_heat")) {
            rejects { ComfortPlan.parse(c) }
            rejects { ComfortPlan.parse(c, 3) }
            rejects { ComfortPlan.parse(c, -1) }
        }
    }

    @Test fun unknownCommandsRejected() {
        rejects { ComfortPlan.parse(null) }
        rejects { ComfortPlan.parse("") }
        rejects { ComfortPlan.parse("seat_heat", 1) }
        rejects { ComfortPlan.parse("SHADE_OPEN") }
    }

    @Test fun readOnlyCommandsHaveNoWrites() {
        val info = ComfortPlan.parse("info")
        assertEquals(ComfortPlan.Kind.INFO, info.kind)
        assertTrue(info.steps.isEmpty())
        val t = ComfortPlan.parse("shade_time")
        assertEquals(ComfortPlan.Kind.TIMING, t.kind)
        assertTrue(t.steps.isEmpty())
        assertEquals(30_000L, t.watchMs)
        assertEquals(100L, t.intervalMs)
        assertEquals(listOf(ComfortPlan.SUNSHADE), t.watch)
    }

    @Test fun writeCommandsReadBackFor3sAt250ms() {
        val c = ComfortPlan.parse("wheel_heat", 1)
        assertEquals(3000L, c.watchMs)
        assertEquals(250L, c.intervalMs)
        assertEquals(listOf(ComfortPlan.STEER_WHEEL_HEAT), c.watch)
        assertEquals(2, ComfortPlan.parse("defrost_rear_both", 1).watch.size)
    }

    @Test fun changeTracker() {
        val t = ComfortPlan.ChangeTracker()
        assertTrue(t.observe("a", "[1]"))
        assertFalse(t.observe("a", "[1]"))
        assertTrue(t.observe("a", "[2]"))
        assertTrue(t.observe("b", "[2]"))
        assertTrue(t.observe("a", "null"))
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("IllegalArgumentException bekleniyordu")
        } catch (e: IllegalArgumentException) {
            // beklenen
        }
    }
}
