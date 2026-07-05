package com.pcwake.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiNetworkMonitorTest {

    @Test
    fun `strips the quotes the framework wraps around real SSIDs`() {
        assertEquals("HomeNet_5G", WifiNetworkMonitor.normalizeSsid("\"HomeNet_5G\""))
    }

    @Test
    fun `keeps an unquoted SSID as-is`() {
        assertEquals("HomeNet_5G", WifiNetworkMonitor.normalizeSsid("HomeNet_5G"))
    }

    @Test
    fun `keeps interior quotes`() {
        assertEquals("Greg's \"Guest\"", WifiNetworkMonitor.normalizeSsid("\"Greg's \"Guest\"\""))
    }

    @Test
    fun `unknown ssid sentinel becomes null`() {
        assertNull(WifiNetworkMonitor.normalizeSsid("<unknown ssid>"))
    }

    @Test
    fun `null, blank and empty-quoted become null`() {
        assertNull(WifiNetworkMonitor.normalizeSsid(null))
        assertNull(WifiNetworkMonitor.normalizeSsid(""))
        assertNull(WifiNetworkMonitor.normalizeSsid("   "))
        assertNull(WifiNetworkMonitor.normalizeSsid("\"\""))
    }
}
