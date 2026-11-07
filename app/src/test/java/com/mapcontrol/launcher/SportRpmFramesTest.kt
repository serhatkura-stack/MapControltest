package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportRpmFramesTest {

    // --- Çerçeve çözümü (Spi2Ethenet, 7 Kasım araç kaydından biçim) ---

    @Test fun readsRpmFromFrame() {
        assertEquals(0x0F9D, SpiRpmParse.rpm("I/Spi2Ethenet(  501): 0028353866 | Read: 03 73 53 0B 00 FE 8B 02 46 4C FE 8D 02 0F 9D "))
        // -v raw biçimi, satır sonunda boşluk yok.
        assertEquals(765, SpiRpmParse.rpm("0029801327 | Read: 03 73 53 0B 00 FE 8B 02 46 4C FE 8D 02 02 FD"))
        // 0x8D ilk TLV.
        assertEquals(2651, SpiRpmParse.rpm("0029801327 | Read: 03 73 53 06 00 FE 8D 02 0A 5B"))
    }

    @Test fun frameWithoutRpmIsIgnored() {
        assertEquals(-1, SpiRpmParse.rpm("I/Spi2Ethenet(  501): 0029801327 | Read: 03 73 53 10 00 FE 42 02 07 88 FE 43 02 00 64 FE 8B 02 43 75"))
        assertEquals(-1, SpiRpmParse.rpm("0028353866 | Read: 03 73 53 05 00 0A 79 01 00"))
    }

    @Test fun otherLinesAreIgnored() {
        assertEquals(-1, SpiRpmParse.rpm("0028353926 | Write: 05 75 23 0C 00 10 7B 08 01 00 02 04 22 00 00 00"))
        assertEquals(-1, SpiRpmParse.rpm("0028354017 | Read: 04 44 01 03 00 00 00"))
        assertEquals(-1, SpiRpmParse.rpm(""))
        assertEquals(-1, SpiRpmParse.rpm("--------- beginning of main"))
    }

    @Test fun rpmInsideAnotherSignalsDataIsNotMistaken() {
        // FE 6D 04 verisi "FE 8D 02 0F" gibi görünse de TLV sınırına göre atlanır.
        assertEquals(-1, SpiRpmParse.rpm("Read: 03 73 53 08 00 FE 6D 04 FE 8D 02 0F"))
    }

    @Test fun malformedFramesAreRejected() {
        // Kesik veri.
        assertEquals(-1, SpiRpmParse.rpm("Read: 03 73 53 0B 00 FE 8B 02 46 4C FE 8D 02 0F"))
        // Uzunluk bayt'ı TLV'yi taşımıyor.
        assertEquals(-1, SpiRpmParse.rpm("Read: 03 73 53 04 00 FE 8D 02 0F 9D"))
        // Onaltılık değil.
        assertEquals(-1, SpiRpmParse.rpm("Read: 03 73 53 06 00 FE 8D 02 0G 9D"))
        // 0x8D uzunluğu 2 değil.
        assertEquals(-1, SpiRpmParse.rpm("Read: 03 73 53 05 00 FE 8D 01 0F"))
        // 00 ayracı yok.
        assertEquals(-1, SpiRpmParse.rpm("Read: 03 73 53 06 01 FE 8D 02 0F 9D"))
        // Aralık dışı (9000 üstü).
        assertEquals(-1, SpiRpmParse.rpm("Read: 03 73 53 06 00 FE 8D 02 FF FF"))
        // Bayt üç karakterden uzun.
        assertEquals(-1, SpiRpmParse.rpm("Read: 03 73 53 06 00 FE 8D 02 0F9 D"))
    }

    @Test fun hexByteNeedsSeparator() {
        assertEquals(0xAB, SpiRpmParse.hexByte("AB", 0))
        assertEquals(0xAB, SpiRpmParse.hexByte("ab cd", 0))
        assertEquals(-1, SpiRpmParse.hexByte("ABC", 0))
        assertEquals(-1, SpiRpmParse.hexByte("A", 0))
    }

    @Test fun summaryLineHasOnlyNumbers() {
        assertEquals("frame n=70 ort=142ms satır=400 son=2651 cpu=12ms/10s", RpmFrameReader.summaryLine(10_000, 70, 400, 2651, 12))
        assertEquals("frame n=0 ort=-ms satır=0 son=-1 cpu=0ms/10s", RpmFrameReader.summaryLine(10_000, 0, 0, -1, 0))
    }

    // --- Düzen kapısı (titremesiz) ---

    @Test fun layoutSwitchesOnlyAfterStableAvailability() {
        val g = RpmLayoutGate(false)
        assertFalse(g.update(0, true))
        assertFalse(g.update(2_000, true))
        // Kısa kesinti sayacı sıfırlar.
        assertFalse(g.update(2_500, false))
        assertFalse(g.update(3_000, true))
        assertFalse(g.update(5_500, true))
        assertTrue(g.update(6_000, true))
        assertTrue(g.rpmFirst)
        // Canlı kaldıkça bir şey değişmez.
        assertFalse(g.update(9_000, true))
    }

    @Test fun layoutFallsBackAfterStableLoss() {
        val g = RpmLayoutGate(true)
        assertFalse(g.update(0, false))
        assertFalse(g.update(2_999, false))
        assertTrue(g.update(3_000, false))
        assertFalse(g.rpmFirst)
    }

    @Test fun restartKeepsLayoutAndResetsCounter() {
        val g = RpmLayoutGate(false)
        g.update(0, true)
        g.restart(false)
        assertFalse(g.update(2_000, true))
        assertFalse(g.update(4_999, true))
        assertTrue(g.update(5_000, true))
    }
}
