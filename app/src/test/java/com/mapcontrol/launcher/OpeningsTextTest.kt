package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningsTextTest {

    /** Türkçe kaynaklarla aynı kalıplar (JVM'de kaynak yok). */
    private val words = object : OpeningWords {
        override fun door(part: BodyPart) = when (part) {
            BodyPart.DOOR_LF -> "Sol ön kapı"
            BodyPart.DOOR_RF -> "Sağ ön kapı"
            BodyPart.DOOR_LR -> "Sol arka kapı"
            else -> "Sağ arka kapı"
        }
        override fun doors(n: Int) = "$n kapı"
        override fun trunk() = "Bagaj"
        override fun hood() = "Kaput"
        override fun sunroof() = "Tavan"
        override fun window(part: BodyPart) = "Sol ön cam"
        override fun windows(n: Int) = "$n cam"
        override fun one(a: String) = "$a açık"
        override fun two(a: String, b: String) = "$a ve $b açık"
        override fun many(total: Int, list: String) = "$total yer açık: $list"
        override fun lower(s: String) = s.lowercase(java.util.Locale("tr", "TR"))
    }

    @Test
    fun nothingOpenGivesNoLine() {
        assertNull(OpeningsText.phrase(Openings.NONE, words))
        assertFalse(Openings.NONE.any)
    }

    @Test
    fun namesOneAndTwoParts() {
        assertEquals("Bagaj açık", OpeningsText.phrase(Openings(trunk = true), words))
        assertEquals("Sol ön kapı ve bagaj açık", OpeningsText.phrase(Openings(doorFl = true, trunk = true), words))
    }

    @Test
    fun groupsDoorsAndShortensThreeOrMore() {
        assertEquals("2 kapı açık", OpeningsText.phrase(Openings(doorFl = true, doorRr = true), words))
        val o = Openings(doorFl = true, doorFr = true, trunk = true, hood = true)
        assertEquals(4, o.count)
        assertEquals("4 yer açık: 2 kapı, bagaj, kaput", OpeningsText.phrase(o, words))
    }

    @Test
    fun carPartsAndDoorFlagFollowTheSameModel() {
        val o = Openings(doorRl = true, trunk = true)
        assertTrue(o.anyDoor)
        assertEquals(setOf(CarPart.DOOR_RL, CarPart.TRUNK), o.carParts())
        assertFalse(Openings(trunk = true).anyDoor)
    }
}
