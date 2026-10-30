package com.mapcontrol.hud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HudPayloadTest {

    @Test fun whitelistIsExactlyTheSevenSubcommands() {
        assertEquals(setOf(3, 52, 48, 49, 51, 50, 53), HudPayload.ALLOWED_SUBCOMMANDS)
    }

    @Test fun otherSubcommandsAreRejected() {
        // 2, 10-15 HUD parlaklık/yükseklik/pozisyon ayarlarıdır; hiçbiri geçmemeli.
        for (sub in (0..255) - setOf(3, 52, 48, 49, 51, 50, 53)) {
            try {
                HudPayload.requireAllowed(sub)
                fail("alt komut $sub geçmemeliydi")
            } catch (e: HudPayload.SubcommandNotAllowed) {
                assertEquals(sub, e.sub)
            }
            try {
                HudPayload.validate(intArrayOf(sub, 1, 0))
                fail("validate $sub geçmemeliydi")
            } catch (e: IllegalArgumentException) {
                // beklenen
            }
        }
    }

    @Test fun validateChecksLengthAndByteRange() {
        assertArrayEquals(intArrayOf(3, 1, 1), HudPayload.validate(intArrayOf(3, 1, 1)))
        for (bad in listOf(
            intArrayOf(3), intArrayOf(3, 1), intArrayOf(3, 2, 1), intArrayOf(3, 1, 1, 1),
            intArrayOf(3, 1, 256), intArrayOf(3, 1, -1), intArrayOf(49, 5, 1, 0, 1, 0), intArrayOf(51, 3, 0, 0, 0),
        )) {
            try {
                HudPayload.validate(bad)
                fail("geçmemeliydi: ${bad.contentToString()}")
            } catch (e: IllegalArgumentException) {
                // beklenen
            }
        }
    }

    @Test fun fixedFrames() {
        assertArrayEquals(intArrayOf(3, 1, 1), HudPayload.clear())
        assertArrayEquals(intArrayOf(52, 1, 0), HudPayload.navStarted())
    }

    @Test fun maneuverIsLittleEndian() {
        // Rapor örneği: ok=3, 500 m -> [49,5,3,0,244,1,0]
        assertArrayEquals(intArrayOf(49, 5, 3, 0, 244, 1, 0), HudPayload.maneuver(3, 500))
        assertArrayEquals(intArrayOf(49, 5, 9, 0, 250, 0, 0), HudPayload.maneuver(9, 250))
        assertArrayEquals(intArrayOf(49, 5, 29, 0, 0x40, 0x42, 0x0F), HudPayload.maneuver(29, 1_000_000))
    }

    @Test fun maneuverDistanceClampedTo3Bytes() {
        assertArrayEquals(intArrayOf(49, 5, 9, 0, 255, 255, 255), HudPayload.maneuver(9, Int.MAX_VALUE))
        assertArrayEquals(intArrayOf(49, 5, 9, 0, 0, 0, 0), HudPayload.maneuver(9, -5))
    }

    @Test fun iconOutOfRangeRejectedNotClamped() {
        for (bad in listOf(-1, 256, 70000)) {
            try {
                HudPayload.maneuver(bad, 100)
                fail("ikon $bad geçmemeliydi")
            } catch (e: IllegalArgumentException) {
                // beklenen
            }
        }
    }

    @Test fun totalDistance4BytesLittleEndianAndClamped() {
        assertArrayEquals(intArrayOf(51, 4, 5000 and 255, 5000 shr 8, 0, 0), HudPayload.totalDistance(5000))
        assertArrayEquals(intArrayOf(51, 4, 0xB0, 0x04, 0, 0), HudPayload.totalDistance(1200))
        assertArrayEquals(intArrayOf(51, 4, 255, 255, 255, 255), HudPayload.totalDistance(Long.MAX_VALUE))
        assertArrayEquals(intArrayOf(51, 4, 0, 0, 0, 0), HudPayload.totalDistance(-1))
    }

    @Test fun altFamilyScaling() {
        assertEquals(Pair(2500L, 0), HudPayload.scaleAlt(250))        // <1000 m: m*10, metre
        assertEquals(Pair(12L, 1), HudPayload.scaleAlt(1200))         // 1000..9999: m*10/1000, km
        assertEquals(Pair(99L, 1), HudPayload.scaleAlt(9999))
        assertEquals(Pair(120L, 1), HudPayload.scaleAlt(12_000))      // >=10000: round(m/1000)*10
        assertEquals(Pair(130L, 1), HudPayload.scaleAlt(12_500))
        assertEquals(Pair(0L, 0), HudPayload.scaleAlt(-3))
    }

    @Test fun altFamilyFrames() {
        // 250 m -> değer 2500 = 0x09C4, birim 0
        assertArrayEquals(intArrayOf(50, 6, 9, 0, 0xC4, 0x09, 0, 0), HudPayload.maneuverAlt(9, 250))
        // 1200 m -> değer 12, km
        assertArrayEquals(intArrayOf(53, 5, 12, 0, 0, 0, 1), HudPayload.totalDistanceAlt(1200))
    }

    @Test fun roadNameBasic() {
        assertArrayEquals(
            intArrayOf(48, 9, 'T'.code, 'e'.code, 's'.code, 't'.code, ' '.code, 'C'.code, 'a'.code, 'd'.code, '.'.code),
            HudPayload.roadName("Test Cad."),
        )
        assertArrayEquals(intArrayOf(48, 0), HudPayload.roadName(""))
    }

    @Test fun roadNameTruncatedAt47BytesAscii() {
        val out = HudPayload.roadName("a".repeat(100))
        assertEquals(49, out.size)
        assertEquals(47, out[1])
    }

    @Test fun roadNameNeverCutsMultibyteChar() {
        // "ş" 2 bayt: 24 adet = 48 bayt, 47'ye sığan en çok 23 (46 bayt).
        val s = "ş".repeat(24)
        val bytes = HudPayload.truncateUtf8(s, 47)
        assertEquals(46, bytes.size)
        assertEquals(s.substring(0, 23), String(bytes, Charsets.UTF_8))
        // Yalnızca geçerli UTF-8: yeniden kodlayınca aynı baytlar.
        assertArrayEquals(bytes, String(bytes, Charsets.UTF_8).toByteArray(Charsets.UTF_8))
        val frame = HudPayload.roadName(s)
        assertEquals(46, frame[1])
        assertEquals(48, frame.size)
    }

    @Test fun roadNameSurrogatePairNotSplit() {
        // Emoji 4 bayt: 46 bayt "a" + emoji = 50 > 47, emoji tamamen atılır.
        val s = "a".repeat(46) + "😀"
        val bytes = HudPayload.truncateUtf8(s, 47)
        assertEquals(46, bytes.size)
        assertTrue(bytes.all { it == 'a'.code.toByte() })
        // Tam sığıyorsa dahil edilir.
        assertEquals(4, HudPayload.truncateUtf8("😀", 47).size)
        assertEquals(0, HudPayload.truncateUtf8("😀", 3).size)
    }

    @Test fun roadNameBytesAreUnsigned() {
        val frame = HudPayload.roadName("ğ") // C4 9F
        assertArrayEquals(intArrayOf(48, 2, 0xC4, 0x9F), frame)
    }

    @Test fun littleEndianHelper() {
        assertArrayEquals(intArrayOf(0x34, 0x12), HudPayload.le(0x1234, 2))
        assertArrayEquals(intArrayOf(1, 0, 0), HudPayload.le(1, 3))
        assertArrayEquals(intArrayOf(0xFF, 0xFF, 0xFF, 0xFF), HudPayload.le(0xFFFFFFFFL, 4))
    }

    @Test fun constantsMatchReport() {
        assertEquals(327683, HudPayload.MODULE_HUD)
        assertEquals(200, HudPayload.CMD_ID)
    }

    @Test fun parkGate() {
        assertTrue(HudParkGate.evaluate(0, null).allowed)
        assertTrue(HudParkGate.evaluate(0, 0).allowed)
        assertFalse(HudParkGate.evaluate(null, 0).allowed)      // 119 okunamazsa izin yok
        assertFalse(HudParkGate.evaluate(1, 0).allowed)
        assertFalse(HudParkGate.evaluate(0, 5).allowed)         // eski hız hareketli
        assertFalse(HudParkGate.evaluate(-1, 0).allowed)
        assertFalse(HudParkGate.evaluate(65535, 0).allowed)
    }
}
