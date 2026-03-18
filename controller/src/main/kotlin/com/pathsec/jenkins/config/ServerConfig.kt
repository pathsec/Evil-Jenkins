package com.pathsec.jenkins.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

data class ServerConfig(
    val server: ServerSection = ServerSection(),
    val identity: IdentitySection = IdentitySection(),
    val data: DataSection = DataSection(),
    val api: ApiSection = ApiSection()
) {
    data class ServerSection(
        val httpHost: String = "127.0.0.1",
        val httpPort: Int = 8080,
        val tcpPort: Int = 50000,
        val baseUrl: String = "http://localhost:8080"
    )

    data class IdentitySection(
        val keystorePath: String = "data/controller.jks",
        val keystorePassword: String = "jenkins-agent-engine"
    )

    data class DataSection(
        val agentsFile: String = "data/agents.json",
        val agentJarCache: String = "data/agent-jar-cache"
    )

    data class ApiSection(
        val token: String = "changeme-api-token"
    )

    companion object {
        fun load(): ServerConfig {
            val resource = ServerConfig::class.java.classLoader
                .getResourceAsStream("application.yml")
                ?: return ServerConfig()

            val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
            return try {
                mapper.readValue(resource, ServerConfig::class.java)
            } catch (e: Exception) {
                ServerConfig()
            }
        }
    }
}
