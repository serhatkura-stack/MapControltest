package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Genişletilmiş telefon sözleşmesinin saf yardımcıları (DTMF, ses yönü, sayaç, yoklama, rehber yenileme). */
class PhoneContractTest {

    @Test fun dtmfIsAsciiDecimal() {
        assertEquals("48", DtmfCode.vdNumber('0'))
        assertEquals("53", DtmfCode.vdNumber('5'))
        assertEquals("57", DtmfCode.vdNumber('9'))
        assertEquals("42", DtmfCode.vdNumber('*'))
        assertEquals("35", DtmfCode.vdNumber('#'))
        assertNull(DtmfCode.vdNumber('a'))
        assertNull(DtmfCode.vdNumber('+'))
        // OEM servisi Byte.parseByte ile okur: hepsi bayta sığmalı.
        for (c in "0123456789*#") assertEquals(c.code, DtmfCode.vdNumber(c)!!.toByte().toInt())
    }

    @Test fun audioMode() {
        assertEquals(false, AudioModeMap.onPhone(1))
        assertEquals(true, AudioModeMap.onPhone(2))
        assertNull(AudioModeMap.onPhone(3))
        assertNull(AudioModeMap.onPhone(-1))
    }

    @Test fun callClock() {
        assertEquals(1_000L, CallClock.startElapsed(oemStartWallMs = 50_000L, nowWallMs = 90_000L, nowElapsedMs = 41_000L))
        assertNull(CallClock.startElapsed(0L, 90_000L, 41_000L))
        assertNull(CallClock.startElapsed(200_000L, 90_000L, 41_000L))
        assertNull(CallClock.startElapsed(1L, 13L * 3600_000L, 41_000L))
        // Küçük saat kayması (gelecek) 0 yaş sayılır.
        assertEquals(41_000L, CallClock.startElapsed(92_000L, 90_000L, 41_000L))
    }

    @Test fun pollOnlyDuringCall() {
        assertTrue(CallPoll.shouldPoll(CallKind.ACTIVE, true))
        assertTrue(CallPoll.shouldPoll(CallKind.INCOMING, true))
        assertTrue(CallPoll.shouldPoll(CallKind.DIALING, true))
        assertFalse(CallPoll.shouldPoll(CallKind.ENDED, true))
        assertFalse(CallPoll.shouldPoll(CallKind.NONE, true))
        assertFalse(CallPoll.shouldPoll(CallKind.ACTIVE, false))
    }

    @Test fun phonebookRefresh() {
        assertEquals(PhonebookRefresh.Step.CACHE, PhonebookRefresh.next(false, null, null, false))
        assertEquals(PhonebookRefresh.Step.NONE, PhonebookRefresh.next(false, 12, null, false))
        assertEquals(PhonebookRefresh.Step.NONE, PhonebookRefresh.next(true, 0, null, false))
        assertEquals(PhonebookRefresh.Step.DOWNLOAD, PhonebookRefresh.next(false, 0, 20, false))
        assertEquals(PhonebookRefresh.Step.DOWNLOAD, PhonebookRefresh.next(false, 0, null, false))
        assertEquals(PhonebookRefresh.Step.NONE, PhonebookRefresh.next(false, 0, 0, false))
        assertEquals(PhonebookRefresh.Step.NONE, PhonebookRefresh.next(false, 0, 21, false))
        assertEquals(PhonebookRefresh.Step.NONE, PhonebookRefresh.next(false, 0, 1, true))
    }

    @Test fun snapshotDefaultsBackwardCompatible() {
        val s = PhoneSnapshot()
        assertFalse(s.canAudioTransfer)
        assertFalse(s.canDtmf)
        assertEquals(0, s.directoryVersion)
        val c = CallState(CallKind.ACTIVE, "x", "1", 5L, true)
        assertFalse(c.audioOnPhone)
        assertNull(c.photoKey)
        assertFalse(c.toString().contains("x"))
    }
}
