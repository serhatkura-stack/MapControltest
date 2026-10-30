package com.mapcontrol.hud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HudLimitPayloadTest {

    private fun rejects(module: Int, cmd: Int, values: IntArray) {
        try {
            HudLimitPayload.validate(module, cmd, values)
            fail("geçmemeliydi: $module/$cmd ${values.contentToString()}")
        } catch (e: IllegalArgumentException) {
            // beklenen
        }
    }

    @Test fun signTableHasAllFortyThreeEntriesWithUniqueIndexes() {
        assertEquals(43, HudLimitPayload.SIGN_INDEX.size)
        assertEquals((1..43).toSet(), HudLimitPayload.SIGN_INDEX.values.toSet())
    }

    @Test fun signTableSpotChecks() {
        assertEquals(1, HudLimitPayload.signIndex(5))
        assertEquals(15, HudLimitPayload.signIndex(50))
        assertEquals(26, HudLimitPayload.signIndex(100))
        assertEquals(29, HudLimitPayload.signIndex(120))
        assertEquals(43, HudLimitPayload.signIndex(140))
        assertEquals(34, HudLimitPayload.signIndex(180))
        assertEquals(38, HudLimitPayload.signIndex(75))
        assertEquals(0, HudLimitPayload.signIndex(0))
        assertEquals(0, HudLimitPayload.signIndex(51))
        assertEquals(0, HudLimitPayload.signIndex(250))
    }

    @Test fun limitPayload() {
        val p = HudLimitPayload.limit(50)
        assertEquals(327682, p.module)
        assertEquals(123, p.cmd)
        assertArrayEquals(intArrayOf(1, 50, 0, 10, 34, 0, 0, 0), p.values)
        assertArrayEquals(intArrayOf(2, 100, 0, 10, 34, 0, 0, 0), HudLimitPayload.limit(100, 2).values)
    }

    @Test fun signPayload() {
        val p = HudLimitPayload.sign(50)
        assertEquals(327682, p.module)
        assertEquals(125, p.cmd)
        assertArrayEquals(intArrayOf(0, 0, 0, 15), p.values)
        assertArrayEquals(intArrayOf(0, 0, 0, 26), HudLimitPayload.sign(100).values)
    }

    @Test fun clearPayloads() {
        assertArrayEquals(intArrayOf(0, 0, 0, 10, 34, 0, 0, 0), HudLimitPayload.clearLimit().values)
        assertArrayEquals(intArrayOf(0, 0, 0, 0), HudLimitPayload.clearSign().values)
    }

    @Test fun kmhRange() {
        HudLimitPayload.limit(0); HudLimitPayload.limit(250)
        for (bad in listOf(-1, 251, 1000)) {
            try { HudLimitPayload.limit(bad); fail("limit $bad") } catch (e: IllegalArgumentException) { }
            try { HudLimitPayload.sign(bad); fail("sign $bad") } catch (e: IllegalArgumentException) { }
        }
    }

    @Test fun whitelistIsExactlyTwoCommands() {
        assertEquals(setOf(Pair(327682, 123), Pair(327682, 125), Pair(327682, 112)), HudLimitPayload.ALLOWED.keys)
        assertEquals(8, HudLimitPayload.ALLOWED[Pair(327682, 123)])
        assertEquals(4, HudLimitPayload.ALLOWED[Pair(327682, 125)])
        assertEquals(listOf(0, 0, 0, 0, 1, 0, 0, 0), HudLimitPayload.unknownLimit().values.toList())
    }

    @Test fun otherModulesAndCommandsRejected() {
        rejects(327683, 123, IntArray(8))
        rejects(327683, 200, intArrayOf(3, 1, 1))
        rejects(327682, 124, IntArray(8))
        rejects(327682, 200, IntArray(8))
        for (cmd in 0..300) if (cmd != 123 && cmd != 125 && cmd != 112) rejects(327682, cmd, IntArray(8))
    }

    @Test fun lengthAndRangeChecked() {
        rejects(327682, 123, IntArray(7))
        rejects(327682, 123, IntArray(9))
        rejects(327682, 125, IntArray(3))
        rejects(327682, 125, IntArray(5))
        rejects(327682, 123, intArrayOf(1, 251, 0, 10, 34, 0, 0, 0))
        rejects(327682, 123, intArrayOf(1, 50, 0, 10, 34, 0, 0, 256))
        rejects(327682, 123, intArrayOf(1, 50, 0, 10, 34, 0, 0, -1))
        rejects(327682, 125, intArrayOf(0, 0, 0, 44))
        rejects(327682, 125, intArrayOf(1, 0, 0, 5))
        assertTrue(HudLimitPayload.validate(327682, 125, intArrayOf(0, 0, 0, 43)).values[3] == 43)
    }
}

class TurboDogPayloadTest {

    @Test fun distDecreasesAndClamps() {
        assertEquals(300, TurboDogPayload.distAt(300, 0))
        assertEquals(280, TurboDogPayload.distAt(300, 1))
        assertEquals(100, TurboDogPayload.distAt(300, 10))
        assertEquals(0, TurboDogPayload.distAt(100, 10))
    }

    @Test fun turboMessages() {
        assertEquals(mapOf("CODE" to 202, "DATA" to 1), TurboDogPayload.turboStart())
        assertEquals(mapOf("CODE" to 203), TurboDogPayload.turboEnd())
        val t = TurboDogPayload.turboTurn(2, 300, 1000L)
        assertEquals(201, t["CODE"]); assertEquals(2, t["TURN_TYPE"]); assertEquals(300, t["TURN_DIST"])
        assertEquals(181_000L, t["ARRIVE_TIME"]); assertEquals("Test Cad.", t["CURRENT_ROAD"])
        assertEquals("Deneme Sk.", t["NEXT_ROAD"]); assertEquals(2000, t["TOTAL_DIST"])
    }

    @Test fun amapMessages() {
        assertEquals(mapOf("KEY_TYPE" to 10019, "EXTRA_STATE" to 8), TurboDogPayload.amapState(8))
        val g = TurboDogPayload.amapGuide(3, 200)
        assertEquals(10001, g["KEY_TYPE"]); assertEquals(3, g["ICON"]); assertEquals(200, g["SEG_REMAIN_DIS"])
        assertEquals(50, g["LIMITED_SPEED"]); assertEquals(0, g["CAMERA_SPEED"])
    }
}
