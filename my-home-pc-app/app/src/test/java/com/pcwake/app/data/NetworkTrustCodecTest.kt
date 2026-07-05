package com.pcwake.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkTrustCodecTest {

    @Test
    fun `round-trips a mixed map`() {
        val decisions = mapOf(
            "HomeNet_5G" to true,
            "HomeNet" to true,
            "Cafe Roma Free WiFi" to false
        )
        assertEquals(decisions, NetworkTrustCodec.decode(NetworkTrustCodec.encode(decisions)))
    }

    @Test
    fun `round-trips SSIDs with awkward characters`() {
        // SSIDs may legally contain quotes, commas, emoji, JSON syntax, leading/trailing spaces.
        val decisions = mapOf(
            """Greg's "Guest" Wi-Fi""" to true,
            "a,b:c{d}e" to false,
            " padded " to true,
            "📶 free wifi 📶" to false
        )
        assertEquals(decisions, NetworkTrustCodec.decode(NetworkTrustCodec.encode(decisions)))
    }

    @Test
    fun `empty map round-trips`() {
        assertEquals(emptyMap<String, Boolean>(), NetworkTrustCodec.decode(NetworkTrustCodec.encode(emptyMap())))
    }

    @Test
    fun `null and blank input decode to empty`() {
        assertEquals(emptyMap<String, Boolean>(), NetworkTrustCodec.decode(null))
        assertEquals(emptyMap<String, Boolean>(), NetworkTrustCodec.decode(""))
        assertEquals(emptyMap<String, Boolean>(), NetworkTrustCodec.decode("   "))
    }

    @Test
    fun `malformed input decodes to empty instead of crashing`() {
        assertEquals(emptyMap<String, Boolean>(), NetworkTrustCodec.decode("not json at all"))
        assertEquals(emptyMap<String, Boolean>(), NetworkTrustCodec.decode("[1,2,3]"))
        assertEquals(emptyMap<String, Boolean>(), NetworkTrustCodec.decode("{\"unterminated\": tr"))
    }
}