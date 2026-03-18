package com.pathsec.jenkins.api

import com.pathsec.jenkins.agent.AgentConnectionManager
import com.pathsec.jenkins.agent.AgentRegistry
import com.pathsec.jenkins.config.ServerConfig
import io.javalin.Javalin
import io.javalin.http.Context

class AgentRoutes(
    private val registry: AgentRegistry,
    private val connectionManager: AgentConnectionManager,
    private val config: ServerConfig
) {
    fun register(app: Javalin) {
        app.post("/api/agents", ::registerAgent)
        app.get("/api/agents", ::listAgents)
        app.get("/api/agents/{name}", ::getAgent)
        app.delete("/api/agents/{name}", ::deleteAgent)
        app.post("/api/agents/{name}/disconnect", ::disconnectAgent)
    }

    private fun registerAgent(ctx: Context) {
        data class RegisterRequest(
            val name: String,
            val labels: List<String> = emptyList(),
            val executors: Int = 1,
            val description: String = ""
        )

        val req = ctx.bodyAsClass(RegisterRequest::class.java)
        if (req.name.isBlank()) {
            ctx.status(400).json(mapOf("error" to "Agent name is required"))
            return
        }

        val agent = registry.register(req.name, req.labels, req.executors, req.description)
        val baseUrl = config.server.baseUrl

        ctx.status(201).json(mapOf(
            "name" to agent.name,
            "secret" to agent.secret,
            "labels" to agent.labels,
            "executors" to agent.executors,
            "jnlpUrl" to "$baseUrl/computer/${agent.name}/slave-agent.jnlp",
            "agentJarUrl" to "$baseUrl/jnlpJars/agent.jar",
            "launchScriptUrl" to "$baseUrl/api/agents/${agent.name}/launch-script",
            "launchCommand" to "java -jar agent.jar -url $baseUrl -name ${agent.name} -secret ${agent.secret} -workDir /opt/agent"
        ))
    }

    private fun listAgents(ctx: Context) {
        val agents = registry.list().map { agent ->
            val conn = connectionManager.getOrDisconnected(agent.name)
            mapOf(
                "name" to agent.name,
                "labels" to agent.labels,
                "executors" to agent.executors,
                "connected" to connectionManager.isConnected(agent.name),
                "connectedAt" to conn?.connectedAt?.toString(),
                "remoteAddress" to conn?.remoteAddress
            )
        }
        ctx.json(agents)
    }

    private fun getAgent(ctx: Context) {
        val name = ctx.pathParam("name")
        val agent = registry.get(name) ?: run {
            ctx.status(404).json(mapOf("error" to "Agent not found"))
            return
        }
        val conn = connectionManager.getOrDisconnected(name)
        ctx.json(mapOf(
            "name" to agent.name,
            "secret" to agent.secret,
            "labels" to agent.labels,
            "executors" to agent.executors,
            "description" to agent.description,
            "createdAt" to agent.createdAt,
            "connected" to connectionManager.isConnected(name),
            "connectedAt" to conn?.connectedAt?.toString(),
            "remoteAddress" to conn?.remoteAddress
        ))
    }

    private fun deleteAgent(ctx: Context) {
        val name = ctx.pathParam("name")
        connectionManager.disconnect(name)
        if (registry.remove(name)) {
            ctx.status(204)
        } else {
            ctx.status(404).json(mapOf("error" to "Agent not found"))
        }
    }

    private fun disconnectAgent(ctx: Context) {
        val name = ctx.pathParam("name")
        connectionManager.disconnect(name)
        ctx.json(mapOf("status" to "disconnected", "agent" to name))
    }
}
