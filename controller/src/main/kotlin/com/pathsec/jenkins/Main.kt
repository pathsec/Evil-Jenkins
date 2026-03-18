package com.pathsec.jenkins

import com.pathsec.jenkins.agent.AgentConnectionManager
import com.pathsec.jenkins.agent.AgentJarService
import com.pathsec.jenkins.agent.AgentRegistry
import com.pathsec.jenkins.api.AgentRoutes
import com.pathsec.jenkins.api.AgentJarRoutes
import com.pathsec.jenkins.api.ExecutionRoutes
import com.pathsec.jenkins.api.JenkinsCompatRoutes
import com.pathsec.jenkins.config.ServerConfig
import com.pathsec.jenkins.identity.ControllerIdentity
import com.pathsec.jenkins.protocol.TcpAgentListener
import io.javalin.Javalin
import io.javalin.http.UnauthorizedResponse
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.pathsec.jenkins.Main")

fun main() {
    log.info("Starting Evil Jenkins Controller")

    val config = ServerConfig.load()
    log.info("Config loaded: httpPort=${config.server.httpPort}, tcpPort=${config.server.tcpPort}")

    // Ensure data directory exists
    java.io.File("data").mkdirs()
    java.io.File(config.data.agentJarCache).mkdirs()

    // Initialize controller identity (X.509 keypair)
    val identity = ControllerIdentity(config.identity)
    log.info("Controller identity fingerprint: ${identity.fingerprint}")

    // Agent registry (name → secret, labels)
    val agentRegistry = AgentRegistry(config.data.agentsFile)

    // Agent connection manager (name → Channel)
    val connectionManager = AgentConnectionManager()

    // Agent JAR service (generate + cache agent.jar)
    val agentJarService = AgentJarService(config.data.agentJarCache)

    // Start the HTTP API server
    val app = Javalin.create { cfg ->
        cfg.showJavalinBanner = false
        cfg.bundledPlugins.enableCors { cors ->
            cors.addRule { it.anyHost() }
        }
    }

    // Enforce API token on all /api/* routes
    app.before("/api/*") { ctx ->
        val expected = config.api.token
        val provided = ctx.header("Authorization")?.removePrefix("Bearer ")?.trim() ?: ""
        if (provided != expected) {
            throw UnauthorizedResponse("Invalid or missing API token. Send: Authorization: Bearer <token>")
        }
    }

    val agentRoutes = AgentRoutes(agentRegistry, connectionManager, config)
    val executionRoutes = ExecutionRoutes(agentRegistry, connectionManager)
    val agentJarRoutes = AgentJarRoutes(agentJarService, agentRegistry, config)
    val jenkinsCompatRoutes = JenkinsCompatRoutes(identity, agentRegistry, config)

    agentRoutes.register(app)
    executionRoutes.register(app)
    agentJarRoutes.register(app)
    jenkinsCompatRoutes.register(app)

    app.start(config.server.httpHost, config.server.httpPort)
    log.info("HTTP server started on ${config.server.httpHost}:${config.server.httpPort}")

    // Start the TCP listener for inbound agents
    val tcpListener = TcpAgentListener(
        port = config.server.tcpPort,
        identity = identity,
        agentRegistry = agentRegistry,
        connectionManager = connectionManager
    )
    tcpListener.start()
    log.info("TCP agent listener started on port ${config.server.tcpPort}")

    log.info("Controller ready. Base URL: ${config.server.baseUrl}")
    log.info("API Token: ${config.api.token}")

    // Shutdown hook
    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutting down...")
        tcpListener.stop()
        app.stop()
        connectionManager.closeAll()
    })

    Thread.currentThread().join()
}
