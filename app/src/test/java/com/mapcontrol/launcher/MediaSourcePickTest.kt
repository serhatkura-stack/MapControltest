package com.mapcontrol.launcher

import com.mapcontrol.launcher.MediaSourcePick.Cand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSourcePickTest {

    private val radio = Cand(MediaSourcePick.OEM_AUDIO_PKG, playing = false, hasTitle = true)   // boş "am" yer tutucusu
    private val bt = Cand(MediaSourcePick.BT_PKG, playing = false, hasTitle = false)
    private val btPlaying = Cand(MediaSourcePick.BT_PKG, playing = true, hasTitle = true)
    private val radioPlaying = Cand(MediaSourcePick.OEM_AUDIO_PKG, playing = true, hasTitle = true)
    private val spotify = Cand("com.spotify.music", playing = false, hasTitle = true)

    @Test fun emptyListPicksNothing() = assertEquals(-1, MediaSourcePick.pick(emptyList(), -1))

    @Test fun playingSessionAlwaysWins() {
        assertEquals(1, MediaSourcePick.pick(listOf(radio, btPlaying), 0))
        assertEquals(0, MediaSourcePick.pick(listOf(radioPlaying, bt), 1))
    }

    @Test fun radioPlaceholderNotShownWhenBluetoothSessionExists() {
        // 6 Kasım: radyo ("am") seçili, BT oturumu var, hiçbiri çalmıyor → kart BT'yi göstermeli.
        assertEquals(1, MediaSourcePick.pick(listOf(radio, bt), 0))
        assertEquals(1, MediaSourcePick.pick(listOf(radio, bt), -1))
    }

    @Test fun pausedSelectionKeptOtherwise() {
        assertEquals(0, MediaSourcePick.pick(listOf(spotify, bt), 0))   // duraklatılan müzik atlamaz
        assertEquals(1, MediaSourcePick.pick(listOf(radio, bt), 1))     // BT seçiliyse korunur
        assertEquals(0, MediaSourcePick.pick(listOf(radio), 0))         // BT yoksa radyo gösterilir
        assertEquals(0, MediaSourcePick.pick(listOf(spotify, bt), -1))  // başlıklı üçüncü taraf önce
    }

    @Test fun switchOnlyWhenOemSourceIsNotBluetooth() {
        assertFalse(MediaSourcePick.needsBtSwitch(OemAudioSource.BT_MUSIC))
        assertTrue(MediaSourcePick.needsBtSwitch(10))   // AM
        assertTrue(MediaSourcePick.needsBtSwitch(9))    // FM
        assertTrue(MediaSourcePick.needsBtSwitch(null)) // okunamadı: OEM gibi geçir (zararsız)
    }

    @Test fun switchPayloadMatchesOemLauncher() {
        // SVLauncher ImpAudioSourceManager.java:553-571 changeMeidaSource.
        val p = OemAudioSource.switchPayload()
        assertEquals(13, p.mediaType)
        assertEquals(6, p.playAction)
        assertEquals(13, p.mediaPlayActionInfo.reason)
        assertEquals(2, p.mediaPlayActionInfo.disPlayView)
        assertEquals(1, p.mediaPlayActionInfo.intent)
    }
}
