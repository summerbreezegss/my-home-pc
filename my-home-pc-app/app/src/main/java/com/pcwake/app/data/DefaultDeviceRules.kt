package com.pcwake.app.data

/**
 * Decides when the stored default device should change in response to a freshly fetched
 * device list. Pure so the auto-select behavior is unit-testable; both places that fetch
 * the list (device list screen, Settings) apply the same rule through here.
 */
object DefaultDeviceRules {

    /**
     * Returns the device to persist as the new default, or null to leave the stored value
     * alone. Two cases produce an update:
     *
     * - No default stored and exactly one device exists — it's the only possible answer, so
     *   pick it without making the user visit Settings. With several devices, deliberately
     *   no guess: the tile/widget stay in their not-configured state until the user chooses.
     * - The stored default still exists but was renamed on the relay — refresh the stored
     *   name so the tile/widget don't keep showing the old one.
     *
     * A stored default that vanished from the list is left as-is rather than cleared: device
     * lists also come back wrong/empty on transient relay hiccups, and wiping the user's
     * choice over one bad fetch would be worse than a tile that errors until the relay is
     * fixed.
     */
    fun resolveUpdate(currentId: String, currentName: String, devices: List<Device>): Device? {
        if (currentId.isBlank()) return devices.singleOrNull()
        val stored = devices.firstOrNull { it.id == currentId } ?: return null
        return if (stored.name != currentName) stored else null
    }

    /**
     * Whether the current default device (by id) advertises the "shutdown" capability in a
     * freshly fetched list. False when there's no default, the default isn't in the list, or
     * it simply can't be shut down remotely — the caller caches this so the widget can hide
     * its Off button. Kept pure and separate from [resolveUpdate] because capabilities can
     * change without a rename, so it must be recomputed on every fetch, not only when the
     * stored default itself changes.
     */
    fun defaultSupportsShutdown(defaultId: String, devices: List<Device>): Boolean {
        if (defaultId.isBlank()) return false
        return devices.firstOrNull { it.id == defaultId }?.capabilities?.contains("shutdown") == true
    }
}
