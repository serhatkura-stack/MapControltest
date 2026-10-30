package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOnlyDecodeTest {

    @Test fun driveTimeIsMinutesAndSeconds() {
        // Araç kaydı 2026-10-30: [0,0,29,45] -> 29 dk 45 sn.
        assertEquals(29, ReadOnlyDecode.driveMinutes(intArrayOf(0, 0, 29, 45)))
        assertEquals(30, ReadOnlyDecode.driveMinutes(intArrayOf(0, 0, 30, 3)))
        assertEquals(0, ReadOnlyDecode.driveMinutes(intArrayOf(0, 0, 0, 0)))
    }

    @Test fun driveTimeHoursAssumption() {
        assertEquals(72, ReadOnlyDecode.driveMinutes(intArrayOf(0, 1, 12, 0)))
        // Dakika baytı 60'ı aşarsa da toplam doğru.
        assertEquals(125, ReadOnlyDecode.driveMinutes(intArrayOf(0, 0, 125, 10)))
    }

    @Test fun driveTimeInvalid() {
        assertNull(ReadOnlyDecode.driveMinutes(null))
        assertNull(ReadOnlyDecode.driveMinutes(intArrayOf(0)))
        assertNull(ReadOnlyDecode.driveMinutes(intArrayOf(0, 0, 10, 75)))
        assertNull(ReadOnlyDecode.driveMinutes(intArrayOf(255, 255, 255, 255)))
        assertNull(ReadOnlyDecode.driveMinutes(intArrayOf(0, 0, -1, 5)))
    }

    @Test fun breakAfterTwoHours() {
        assertFalse(ReadOnlyDecode.breakDue(119))
        assertTrue(ReadOnlyDecode.breakDue(120))
        assertTrue(ReadOnlyDecode.breakDue(300))
        assertFalse(ReadOnlyDecode.breakDue(null))
    }

    @Test fun batteryVoltsMatchesHealthTile() {
        assertEquals(14.2, ReadOnlyDecode.batteryVolts(142)!!, 1e-9)
        assertEquals(14.1, ReadOnlyDecode.batteryVolts(141)!!, 1e-9)
        assertNull(ReadOnlyDecode.batteryVolts(10_998))
        assertNull(ReadOnlyDecode.batteryVolts(79))
        assertNull(ReadOnlyDecode.batteryVolts(0))
        assertNull(ReadOnlyDecode.batteryVolts(0xFFFF))
        assertNull(ReadOnlyDecode.batteryVolts(null))
    }

    @Test fun batterySocZeroIsUnknown() {
        assertEquals(85, ReadOnlyDecode.batterySoc(85))
        assertEquals(100, ReadOnlyDecode.batterySoc(100))
        assertNull(ReadOnlyDecode.batterySoc(0))
        assertNull(ReadOnlyDecode.batterySoc(255))
        assertNull(ReadOnlyDecode.batterySoc(null))
    }

    @Test fun batteryLowThresholds() {
        assertFalse(ReadOnlyDecode.batteryLow(14.1, 85))
        assertFalse(ReadOnlyDecode.batteryLow(11.35, null))
        assertTrue(ReadOnlyDecode.batteryLow(10.5, null))
        assertTrue(ReadOnlyDecode.batteryLow(16.0, null))
        assertTrue(ReadOnlyDecode.batteryLow(null, 20))
        assertFalse(ReadOnlyDecode.batteryLow(null, null))
    }

    @Test fun padState() {
        assertEquals(ReadOnlyDecode.PAD_EMPTY, ReadOnlyDecode.padState(0))
        assertEquals(ReadOnlyDecode.PAD_CHARGING, ReadOnlyDecode.padState(1))
        assertEquals(ReadOnlyDecode.PAD_PRESENT, ReadOnlyDecode.padState(2))
        assertNull(ReadOnlyDecode.padState(0xFF))
        assertNull(ReadOnlyDecode.padState(null))
    }

    @Test fun phoneForgottenUsesBoardFlagAndReminderSetting() {
        assertEquals(true, ReadOnlyDecode.phoneForgotten(1, 1))
        assertEquals(false, ReadOnlyDecode.phoneForgotten(1, 0))
        // Araçta ölçülen: pedde telefon yok (46 = 0), hatırlatma ayarı 47 = 1.
        assertEquals(false, ReadOnlyDecode.phoneForgotten(0, 1))
        assertNull(ReadOnlyDecode.phoneForgotten(null, 1))
        assertNull(ReadOnlyDecode.phoneForgotten(7, 1))
    }

    @Test fun windowTwoIsClosed() {
        assertEquals(false, ReadOnlyDecode.windowOpen(2))
        assertEquals(true, ReadOnlyDecode.windowOpen(6))
        assertEquals(true, ReadOnlyDecode.windowOpen(4))
        assertEquals(true, ReadOnlyDecode.windowOpen(1))
        assertNull(ReadOnlyDecode.windowOpen(0))
        assertNull(ReadOnlyDecode.windowOpen(0xFF))
        assertNull(ReadOnlyDecode.windowOpen(null))
    }

    @Test fun flagsZeroClosedOneOpen() {
        assertEquals(false, ReadOnlyDecode.flagOpen(0))
        assertEquals(true, ReadOnlyDecode.flagOpen(1))
        assertNull(ReadOnlyDecode.flagOpen(0xFF))
        assertNull(ReadOnlyDecode.flagOpen(null))
        assertEquals(true, ReadOnlyDecode.handbrakeOn(1))
        assertEquals(false, ReadOnlyDecode.fuelCapOpen(0))
    }

    @Test fun extraDataListsOpenParts() {
        val data = ExtraData(
            connected = true,
            parts = mapOf(
                BodyPart.WINDOW_LF to true, BodyPart.WINDOW_RF to false, BodyPart.HOOD to true,
                BodyPart.DOOR_LF to false,
            ),
        )
        assertTrue(data.bodyKnown)
        assertEquals(listOf(BodyPart.WINDOW_LF, BodyPart.HOOD), data.openParts())
        assertTrue(data.anyWindowOpen())
        assertTrue(data.hoodOpen())
        assertFalse(ExtraData().bodyKnown)
        assertTrue(ExtraData(parts = mapOf(BodyPart.HOOD to false)).openParts().isEmpty())
        assertFalse(ExtraData(parts = mapOf(BodyPart.DOOR_LF to true)).anyWindowOpen())
    }
}
