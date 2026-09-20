package dev.agenticscheduler.server.sync

/** Runtime-only configuration; secrets never appear in responses or logs. */
data class SyncServerConfig(
    val jdbcUrl: String,
    val jdbcUser: String,
    val jdbcPassword: String,
    val bindHost: String = "127.0.0.1",
    val port: Int = 8080,
    val maxRequestBodyBytes: Long = 1_200_000,
    val maxCiphertextBytes: Int = 1_048_576,
    val maxFetchLimit: Int = 100,
    val adminToken: String? = null,
    val invitationTtlSeconds: Long = 900,
    val tlsTerminated: Boolean = false,
) {
    init {
        require(jdbcUrl.isNotBlank()) { "SYNC_DATABASE_URL must not be blank." }
        require(jdbcUser.isNotBlank()) { "SYNC_DATABASE_USER must not be blank." }
        require(jdbcPassword.isNotBlank()) { "SYNC_DATABASE_PASSWORD must not be blank." }
        require(port in 1..65535)
        require(maxRequestBodyBytes > 0)
        require(maxCiphertextBytes > 0)
        require(maxFetchLimit in 1..1000)
        require(invitationTtlSeconds in 60..86_400)
        require(isLoopback(bindHost) || tlsTerminated) {
            "Non-loopback sync binding requires TLS at the server or an explicitly trusted TLS-terminating proxy."
        }
    }

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): SyncServerConfig =
            SyncServerConfig(
                jdbcUrl = environment.required("SYNC_DATABASE_URL"),
                jdbcUser = environment.required("SYNC_DATABASE_USER"),
                jdbcPassword = environment.required("SYNC_DATABASE_PASSWORD"),
                bindHost = environment["SYNC_BIND_HOST"] ?: "127.0.0.1",
                port = environment["SYNC_PORT"]?.toIntOrNull() ?: 8080,
                maxRequestBodyBytes = environment["SYNC_MAX_REQUEST_BYTES"]?.toLongOrNull() ?: 1_200_000,
                maxCiphertextBytes = environment["SYNC_MAX_CIPHERTEXT_BYTES"]?.toIntOrNull() ?: 1_048_576,
                maxFetchLimit = environment["SYNC_MAX_FETCH_LIMIT"]?.toIntOrNull() ?: 100,
                adminToken = environment["SYNC_ADMIN_TOKEN"]?.takeIf(String::isNotBlank),
                invitationTtlSeconds = environment["SYNC_INVITATION_TTL_SECONDS"]?.toLongOrNull() ?: 900,
                tlsTerminated = environment["SYNC_TLS_TERMINATED"]?.toBooleanStrictOrNull() ?: false,
            )

        private fun Map<String, String>.required(name: String): String =
            get(name)?.takeIf(String::isNotBlank) ?: error("$name is required.")

        private fun isLoopback(host: String): Boolean = host == "localhost" || host == "127.0.0.1" || host == "::1"
    }
}
