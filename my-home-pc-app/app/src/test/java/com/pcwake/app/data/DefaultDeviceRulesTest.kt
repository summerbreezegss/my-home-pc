package com.pcwake.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultDeviceRulesTest {

    private val officePc = Device(id = "office-pc", name = "Office PC")
    private val homePc = Device(id = "home-pc", name = "Home PC")

    @Test
    fun `auto-selects the only device when no default is stored`() {
        assertEquals(officePc, DefaultDeviceRules.resolveUpdate("", "", listOf(officePc)))
    }

    @Test
    fun `never guesses between multiple devices`() {
        assertNull(DefaultDeviceRules.resolveUpdate("", "", listOf(officePc, homePc)))
    }

    @Test
    fun `no update when the stored default is unchanged`() {
        assertNull(DefaultDeviceRules.resolveUpdate("office-pc", "Office PC", listOf(officePc, homePc)))
    }

    @Test
    fun `refreshes the stored name after a rename on the relay`() {
        val renamed = officePc.copy(name = "Greg's Office PC")
        assertEquals(renamed, DefaultDeviceRules.resolveUpdate("office-pc", "Office PC", listOf(renamed, homePc)))
    }

    @Test
    fun `a default missing from the list is left alone, not cleared`() {
        assertNull(DefaultDeviceRules.resolveUpdate("office-pc", "Office PC", listOf(homePc)))
    }

    @Test
    fun `empty device list changes nothing, with or without a stored default`() {
        assertNull(DefaultDeviceRules.resolveUpdate("", "", emptyList()))
        assertNull(DefaultDeviceRules.resolveUpdate("office-pc", "Office PC", emptyList()))
    }

    @Test
    fun `does not auto-select even a single device while another is already the default`() {
        // Stored default vanished but one other device remains — switching the tile/widget to
        // a different PC silently would be a guess, exactly what the rule exists to avoid.
        assertNull(DefaultDeviceRules.resolveUpdate("office-pc", "Office PC", listOf(homePc)))
    }

    private val shutdownPc = Device(id = "office-pc", name = "Office PC", capabilities = listOf("restart", "shutdown"))
    private val wakeOnlyPc = Device(id = "home-pc", name = "Home PC", capabilities = listOf("restart"))

    @Test
    fun `default supports shutdown when it advertises the capability`() {
        assertTrue(DefaultDeviceRules.defaultSupportsShutdown("office-pc", listOf(shutdownPc, wakeOnlyPc)))
    }

    @Test
    fun `default does not support shutdown without the capability`() {
        assertFalse(DefaultDeviceRules.defaultSupportsShutdown("home-pc", listOf(shutdownPc, wakeOnlyPc)))
    }

    @Test
    fun `no shutdown support when there is no default or it is absent from the list`() {
        assertFalse(DefaultDeviceRules.defaultSupportsShutdown("", listOf(shutdownPc)))
        assertFalse(DefaultDeviceRules.defaultSupportsShutdown("ghost-pc", listOf(shutdownPc)))
    }
}
