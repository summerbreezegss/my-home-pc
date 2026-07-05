package com.pcwake.app.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * (De)serializes the SSID → trusted map as a JSON object string. Built with JsonObject rather
 * than reflected model classes so R8 needs no keep rules for it, and SSIDs can contain any
 * characters without escaping concerns. Malformed stored data degrades to an empty map.
 */
object NetworkTrustCodec {

    fun encode(decisions: Map<String, Boolean>): String {
        val obj = JsonObject()
        decisions.forEach { (ssid, trusted) -> obj.addProperty(ssid, trusted) }
        return obj.toString()
    }

    fun decode(json: String?): Map<String, Boolean> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            JsonParser.parseString(json).asJsonObject.entrySet()
                .associate { (ssid, value) -> ssid to value.asBoolean }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}