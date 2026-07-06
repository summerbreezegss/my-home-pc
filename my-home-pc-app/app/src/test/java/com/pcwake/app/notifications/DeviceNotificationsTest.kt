package com.pcwake.app.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceNotificationsTest {

    @Test
    fun `transition titles read naturally for both directions`() {
        assertEquals("Office PC is now online", DeviceNotifications.transitionTitle("Office PC", online = true))
        assertEquals("Office PC is now offline", DeviceNotifications.transitionTitle("Office PC", online = false))
    }
}
