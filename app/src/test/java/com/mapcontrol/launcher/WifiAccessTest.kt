package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiAccessTest {
    @Test fun permissionComesFirst() {
        assertEquals(WifiAccess.Gate.NEED_PERMISSION, WifiAccess.gate(hasPermission = false, locationOn = false))
        assertEquals(WifiAccess.Gate.NEED_PERMISSION, WifiAccess.gate(hasPermission = false, locationOn = true))
    }

    @Test fun locationOffAfterPermission() {
        assertEquals(WifiAccess.Gate.LOCATION_OFF, WifiAccess.gate(hasPermission = true, locationOn = false))
        assertEquals(WifiAccess.Gate.LIST, WifiAccess.gate(hasPermission = true, locationOn = true))
    }

    @Test fun firstAskRequestsAtRuntime() {
        assertEquals(WifiAccess.PermissionAction.REQUEST, WifiAccess.permissionAction(askedBefore = false, showRationale = false))
        assertEquals(WifiAccess.PermissionAction.REQUEST, WifiAccess.permissionAction(askedBefore = true, showRationale = true))
    }

    @Test fun deniedForeverOpensAppSettings() {
        assertEquals(WifiAccess.PermissionAction.OPEN_APP_SETTINGS, WifiAccess.permissionAction(askedBefore = true, showRationale = false))
    }
}
