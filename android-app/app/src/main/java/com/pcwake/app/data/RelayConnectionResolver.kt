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

    suspend fun resolve(repository: SettingsRepository): RelayConnection {
        val localHost = repository.getLocalHost()
        val port = repository.getPort()

        if (localHost.isNotBlank()) {
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
