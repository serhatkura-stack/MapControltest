package com.mapcontrol.launcher

import com.desaysv.ivi.vdb.event.id.bt.VDEventBT
import com.mapcontrol.launcher.MediaSourcePick.Cand
import com.mapcontrol.launcher.MediaSourcePick.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSourcePickTest {

    private val radio = Cand(MediaSourcePick.OEM_AUDIO_PKG, playing = false, hasTitle = true)   // boş "am" yer tutucusu
    private val radioOld = Cand(MediaSourcePick.OEM_AUDIO_PKG_OLD, playing = false, hasTitle = true)
    private val bt = Cand(MediaSourcePick.BT_PKG, playing = false, hasTitle = false)
    private val btPlaying = Cand(MediaSourcePick.BT_PKG, playing = true, hasTitle = true)
    private val radioPlaying = Cand(MediaSourcePick.OEM_AUDIO_PKG, playing = true, hasTitle = true)
    private val spotify = Cand("com.spotify.music", playing = false, hasTitle = true)

    @Test fun emptyListPicksNothing() = assertEquals(-1, MediaSourcePick.pick(emptyList(), -1))

    @Test fun ourCarOemAudioPackage() {
        // Bizim araç: dumpsys media_session package=com.desaysv.svaudioapp; arkadaşın aracı com.desaysv.audioapp.
        assertEquals("com.desaysv.svaudioapp", MediaSourcePick.OEM_AUDIO_PKG)
        assertTrue(MediaSourcePick.isOemAudio("com.desaysv.svaudioapp"))
        assertTrue(MediaSourcePick.isOemAudio("com.desaysv.audioapp"))
        assertFalse(MediaSourcePick.isOemAudio("com.spotify.music"))
        assertFalse(MediaSourcePick.isOemAudio(null))
    }

    @Test fun playingSessionAlwaysWins() {
        assertEquals(1, MediaSourcePick.pick(listOf(radio, btPlaying), 0))
        assertEquals(0, MediaSourcePick.pick(listOf(radioPlaying, bt), 1))
    }

    @Test fun radioPlaceholderNotShownWhenBluetoothSessionExists() {
        // 6 Kasım: radyo ("am") seçili, BT oturumu var, hiçbiri çalmıyor → kart BT'yi göstermeli.
        assertEquals(1, MediaSourcePick.pick(listOf(radio, bt), 0))
        assertEquals(1, MediaSourcePick.pick(listOf(radio, bt), -1))
        assertEquals(1, MediaSourcePick.pick(listOf(radioOld, bt), 0))   // arkadaşın aracı
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

    @Test fun pathPrefersBtServiceThenMediaServiceThenPlain() {
        // Kaynak 13 ama odak başkasında olabilir: BT servisi varsa yine MUSIC_PLAY.
        assertEquals(Path.BT_PLAY, MediaSourcePick.path(13, btBus = true, mediaBus = true))
        assertEquals(Path.ALREADY_BT, MediaSourcePick.path(13, btBus = false, mediaBus = true))
        assertEquals(Path.ALREADY_BT, MediaSourcePick.path(13, btBus = false, mediaBus = false))
        assertEquals(Path.BT_PLAY, MediaSourcePick.path(9, btBus = true, mediaBus = true))
        assertEquals(Path.BT_PLAY, MediaSourcePick.path(null, btBus = true, mediaBus = false))
        assertEquals(Path.OPEN_SOURCE, MediaSourcePick.path(10, btBus = false, mediaBus = true))
        assertEquals(Path.PLAIN, MediaSourcePick.path(null, btBus = false, mediaBus = false))
    }

    @Test fun switchPayloadUsesReasonCards() {
        // SVLauncher AudioSourceManager.java:719-727 (reason=8); svaudioapp BTMusicService.java:105-127 reason 13'ü yok sayar.
        val p = OemAudioSource.switchPayload()
        assertEquals(13, p.mediaType)
        assertEquals(6, p.playAction)
        assertEquals(8, p.mediaPlayActionInfo.reason)
        assertEquals(2, p.mediaPlayActionInfo.disPlayView)
        assertEquals(1, p.mediaPlayActionInfo.intent)
    }

    @Test fun btPlayPayloadMatchesSvAudioApp() {
        // svaudioapp BTMusicManager.java:364-370,524-526: MUSIC_PLAY{playCtrl=1, playType=0}.
        val p = OemAudioSource.btPlayPayload()
        assertEquals(1, p.playCtrl)
        assertEquals(0, p.playType)
        assertEquals(262948, VDEventBT.MUSIC_PLAY)
    }
}
