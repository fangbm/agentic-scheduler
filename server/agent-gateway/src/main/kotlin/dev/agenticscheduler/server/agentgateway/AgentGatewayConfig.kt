package dev.agenticscheduler.server.agentgateway

data class AgentGatewayConfig(
    val bindHost: String = "127.0.0.1",
    val port: Int = 8091,
    val modelBaseUrl: String,
    val modelName: String,
    val modelApiKey: String? = null,
    val gatewayToken: String,
) {
    init {
        require(modelBaseUrl.startsWith("http://") || modelBaseUrl.startsWith("https://"))
        require(modelName.isNotBlank())
        require(gatewayToken.isNotBlank())
        require(port in 1..65_535)
    }

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): AgentGatewayConfig = AgentGatewayConfig(
            bindHost = environment["AGENT_GATEWAY_BIND_HOST"] ?: "0.0.0.0",
            port = environment["AGENT_GATEWAY_PORT"]?.toIntOrNull() ?: 8091,
            modelBaseUrl = requireNotNull(environment["MODEL_BASE_URL"]) { "MODEL_BASE_URL is required." }.trimEnd('/'),
            modelName = requireNotNull(environment["MODEL_NAME"]) { "MODEL_NAME is required." },
            modelApiKey = environment["MODEL_API_KEY"]?.takeIf { it.isNotBlank() },
            gatewayToken = requireNotNull(environment["AGENT_GATEWAY_TOKEN"]) { "AGENT_GATEWAY_TOKEN is required." },
        )
    }
}
