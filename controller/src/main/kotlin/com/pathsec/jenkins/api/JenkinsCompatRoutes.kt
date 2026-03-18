package com.pathsec.jenkins.api

import com.pathsec.jenkins.agent.AgentRegistry
import com.pathsec.jenkins.config.ServerConfig
import com.pathsec.jenkins.identity.ControllerIdentity
import io.javalin.Javalin
import io.javalin.http.Context

class JenkinsCompatRoutes(
    private val identity: ControllerIdentity,
    private val registry: AgentRegistry,
    private val config: ServerConfig
) {
    fun register(app: Javalin) {
        app.get("/tcpSlaveAgentListener/", ::tcpSlaveAgentListener)
        app.get("/tcpSlaveAgentListener", ::tcpSlaveAgentListener)
        app.get("/computer/{name}/slave-agent.jnlp", ::slaveAgentJnlp)
        app.get("/", ::rootEndpoint)
    }

    private fun tcpSlaveAgentListener(ctx: Context) {
        ctx.header("X-Jenkins-JNLP-Port", config.server.tcpPort.toString())
        ctx.header("X-Jenkins-Agent-Protocols", "JNLP4-connect, Ping")
        ctx.header("X-Instance-Identity", identity.instanceIdentity)
        ctx.header("X-Jenkins", "2.492")
        ctx.header("X-Hudson", "1.395")
        ctx.header("Content-Type", "text/plain")
        ctx.result("Jenkins Agent Listener ready")
    }

    private fun slaveAgentJnlp(ctx: Context) {
        val name = ctx.pathParam("name")
        val agent = registry.get(name) ?: run {
            ctx.status(404).result("Agent not found: $name")
            return
        }
        val baseUrl = config.server.baseUrl
        val tcpPort = config.server.tcpPort

        // Return JSON format that modern agents understand
        // Modern jenkins/inbound-agent uses -url flag which hits this endpoint
        val jnlpXml = """<?xml version="1.0" encoding="UTF-8"?>
<jnlp spec="1.0+" codebase="${baseUrl}">
  <information>
    <title>Jenkins Agent ${agent.name}</title>
    <vendor>Jenkins Agent Engine</vendor>
  </information>
  <security>
    <all-permissions/>
  </security>
  <resources>
    <j2se version="17"/>
    <jar href="${baseUrl}/jnlpJars/agent.jar"/>
  </resources>
  <application-desc main-class="hudson.remoting.Launcher">
    <argument>-url</argument>
    <argument>${baseUrl}</argument>
    <argument>-name</argument>
    <argument>${agent.name}</argument>
    <argument>-secret</argument>
    <argument>${agent.secret}</argument>
  </application-desc>
</jnlp>"""

        ctx.header("Content-Type", "application/x-java-jnlp-file")
        // Also return the secret in a header for direct secret lookup
        ctx.header("X-Jenkins-Agent-Secret", agent.secret)
        ctx.result(jnlpXml)
    }

    private fun rootEndpoint(ctx: Context) {
        ctx.header("X-Jenkins", "2.492")
        ctx.header("X-Hudson", "1.395")
        ctx.header("X-Jenkins-JNLP-Port", config.server.tcpPort.toString())
        ctx.header("X-Instance-Identity", identity.instanceIdentity)
        ctx.json(mapOf(
            "service" to "Jenkins Agent Engine",
            "version" to "1.0.0",
            "tcpPort" to config.server.tcpPort,
            "endpoints" to listOf(
                "POST /api/agents - Register agent",
                "GET  /api/agents - List agents",
                "POST /api/execute - Execute Groovy script",
                "GET  /jnlpJars/agent.jar - Download agent JAR",
                "GET  /tcpSlaveAgentListener/ - Agent discovery"
            )
        ))
    }
}
