package com.pcwake.app.data

import com.google.gson.annotations.SerializedName

data class HealthResponse(
    val status: String,
    val version: String
)

data class Device(
    val id: String,
    val name: String,
    val capabilities: List<String> = emptyList()
)

data class DeviceListResponse(
    val devices: List<Device>
)

data class WakeResponse(
    val id: String,
    val action: String,
    val result: String
)

data class ActionResponse(
    val id: String,
    val action: String,
    val result: String,
    @SerializedName("agent_response") val agentResponse: String?
)

data class StatusResponse(
    val id: String,
    val online: Boolean,
    val method: String,
    @SerializedName("last_seen") val lastSeen: String?,
    @SerializedName("agent_reachable") val agentReachable: Boolean?
)
