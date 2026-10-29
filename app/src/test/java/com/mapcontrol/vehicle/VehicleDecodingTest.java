package com.mapcontrol.vehicle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class VehicleDecodingTest {

    @Test
    public void rpmIsCombinedTimesFour() {
        assertEquals(752, VehicleMetricsSnapshot.rpmFromCombined(188));
        assertEquals(0, VehicleMetricsSnapshot.rpmFromCombined(0));
        assertEquals(4, VehicleMetricsSnapshot.RPM_FACTOR);
    }

    @Test
    public void combineRawBigEndian() {
        assertEquals(0x0102, VehicleMetricsCache.combineRaw(new int[]{0x01, 0x02}));
        assertEquals(0x010203, VehicleMetricsCache.combineRaw(new int[]{1, 2, 3}));
        assertEquals(188, VehicleMetricsCache.combineRaw(new int[]{0, 188}));
    }

    @Test
    public void combineRawSingleAndEmpty() {
        assertEquals(42, VehicleMetricsCache.combineRaw(new int[]{42}));
        assertEquals(VehicleMetricsCache.INVALID, VehicleMetricsCache.combineRaw(new int[0]));
        assertEquals(VehicleMetricsCache.INVALID, VehicleMetricsCache.combineRaw(null));
    }

    @Test
    public void combineRawMasksBytes() {
        // Multi-byte: each element masked to 8 bits.
        assertEquals(0x00FF, VehicleMetricsCache.combineRaw(new int[]{0x100, 0xFF}));
    }

    @Test
    public void driveModeShortLabels() {
        assertEquals("ECO", DriveModeStyle.shortLabel(0));
        assertEquals("NORMAL", DriveModeStyle.shortLabel(1));
        assertEquals("SPORT", DriveModeStyle.shortLabel(2));
        assertEquals("SNOW", DriveModeStyle.shortLabel(3));
        assertEquals("MUD", DriveModeStyle.shortLabel(4));
        assertEquals("SAND", DriveModeStyle.shortLabel(5));
        assertEquals("OFFROAD", DriveModeStyle.shortLabel(6));
        assertNull(DriveModeStyle.shortLabel(7));
        assertNull(DriveModeStyle.shortLabel(-1));
    }
}
