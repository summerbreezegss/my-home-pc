package com.pcwake.app.data

sealed class RelayConnection {
    abstract val api: RelayApi
    abstract val displayName: String

    data class Local(override val api: RelayApi) : RelayConnection() {
        override val displayName = "Local"
    }

    data class Remote(override val api: RelayApi) : RelayConnection() {
        override val displayName = "Remote"
    }
}

object RelayConnectionResolver {
    private const val LOCAL_PROBE_TIMEOUT_SECONDS = 2L

    /**
     * [allowLocal] comes from [NetworkTrustGate]: the Local probe (and the bearer-token
     * traffic that follows it) only happens on a Wi-Fi network the user has trusted.
     * Deliberately no default value — every caller must have consulted the gate.
     */
    suspend fun resolve(repository: SettingsRepository, allowLocal: Boolean): RelayConnection {
        val localHost = repository.getLocalHost()
        val port = repository.getPort()

        if (allowLocal && localHost.isNotBlank()) {
            val localApi = RelayClient.create(localHost, port, connectTimeoutSeconds = LOCAL_PROBE_TIMEOUT_SECONDS)
            try {
                localApi.health()
                return RelayConnection.Local(localApi)
            } catch (e: Exception) {
                // local unreachable — fall through to remote
            }
        }

        return RelayConnection.Remote(RelayClient.create(repository.getRemoteHost(), port))
    }
}
