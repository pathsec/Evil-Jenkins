package com.pathsec.jenkins.api

import com.pathsec.jenkins.agent.AgentJarService
import com.pathsec.jenkins.agent.AgentRegistry
import com.pathsec.jenkins.config.ServerConfig
import io.javalin.Javalin
import io.javalin.http.Context
import org.slf4j.LoggerFactory
import java.time.Instant

class AgentJarRoutes(
    private val jarService: AgentJarService,
    private val registry: AgentRegistry,
    private val config: ServerConfig
) {
    private val log = LoggerFactory.getLogger(AgentJarRoutes::class.java)

    fun register(app: Javalin) {
        app.get("/jnlpJars/agent.jar", ::serveAgentJar)
        app.get("/jnlpJars/remoting.jar", ::serveAgentJar)
        app.post("/api/agent-jar/generate", ::generateAgentJar)
        app.get("/api/agents/{name}/launch-script", ::launchScript)
        app.get("/api/agents/{name}/docker-compose.yml", ::dockerCompose)
    }

    private fun serveAgentJar(ctx: Context) {
        val bytes = jarService.getAgentJarBytes() ?: run {
            ctx.status(503).json(mapOf("error" to "Agent JAR not available. Run POST /api/agent-jar/generate"))
            return
        }
        ctx.header("Content-Type", "application/java-archive")
        ctx.header("Content-Disposition", "attachment; filename=\"agent.jar\"")
        ctx.result(bytes)
    }

    private fun generateAgentJar(ctx: Context) {
        data class GenerateRequest(
            val remotingVersion: String = "3341.v0766d82b_dec0",
            val presetUrl: String? = null
        )

        val req = try {
            ctx.bodyAsClass(GenerateRequest::class.java)
        } catch (e: Exception) {
            GenerateRequest()
        }

        try {
            log.info("Generating agent.jar version ${req.remotingVersion}")
            val file = jarService.generateAgentJar(req.remotingVersion, req.presetUrl)
            ctx.json(mapOf(
                "status" to "generated",
                "version" to req.remotingVersion,
                "sizeBytes" to file.length(),
                "generatedAt" to Instant.now().toString()
            ))
        } catch (e: Exception) {
            log.error("Failed to generate agent.jar", e)
            ctx.status(500).json(mapOf("error" to "Failed to generate: ${e.message}"))
        }
    }

    private fun launchScript(ctx: Context) {
        val name = ctx.pathParam("name")
        val agent = registry.get(name) ?: run {
            ctx.status(404).json(mapOf("error" to "Agent not found"))
            return
        }
        val os = ctx.queryParam("os") ?: "linux"
        val baseUrl = config.server.baseUrl

        val script = if (os == "windows") {
            generatePowerShellScript(name, agent.secret, baseUrl)
        } else {
            generateBashScript(name, agent.secret, baseUrl)
        }

        ctx.header("Content-Type", "text/plain")
        ctx.header("Content-Disposition", "attachment; filename=\"launch-${name}.sh\"")
        ctx.result(script)
    }

    private fun dockerCompose(ctx: Context) {
        val name = ctx.pathParam("name")
        val agent = registry.get(name) ?: run {
            ctx.status(404).json(mapOf("error" to "Agent not found"))
            return
        }
        val baseUrl = config.server.baseUrl

        val yaml = """
version: '3.8'
services:
  $name:
    image: jenkins/inbound-agent:latest
    environment:
      JENKINS_URL: $baseUrl
      JENKINS_AGENT_NAME: $name
      JENKINS_SECRET: ${agent.secret}
      JENKINS_AGENT_WORKDIR: /home/jenkins/agent
    restart: unless-stopped
""".trimIndent()

        ctx.header("Content-Type", "application/x-yaml")
        ctx.result(yaml)
    }

    private fun generateBashScript(name: String, secret: String, baseUrl: String): String = """
#!/usr/bin/env bash
# Auto-generated agent launch script for: $name
set -euo pipefail

CONTROLLER_URL="${'$'}{CONTROLLER_URL:-$baseUrl}"
AGENT_NAME="$name"
AGENT_SECRET="$secret"
WORK_DIR="${'$'}{WORK_DIR:-/opt/agent}"
JAVA="${'$'}{JAVA_HOME:-}/bin/java"

AGENT_JAR="${'$'}{WORK_DIR}/agent.jar"
mkdir -p "${'$'}{WORK_DIR}"
if [ ! -f "${'$'}{AGENT_JAR}" ] || [ $(find "${'$'}{AGENT_JAR}" -mmin +1440 2>/dev/null | wc -l) -gt 0 ]; then
    echo "Downloading agent.jar from ${'$'}{CONTROLLER_URL}..."
    curl -sSfL "${'$'}{CONTROLLER_URL}/jnlpJars/agent.jar" -o "${'$'}{AGENT_JAR}.tmp"
    mv "${'$'}{AGENT_JAR}.tmp" "${'$'}{AGENT_JAR}"
fi

exec ${'$'}{JAVA} -jar "${'$'}{AGENT_JAR}" \
    -url "${'$'}{CONTROLLER_URL}" \
    -name "${'$'}{AGENT_NAME}" \
    -secret "${'$'}{AGENT_SECRET}" \
    -workDir "${'$'}{WORK_DIR}"
""".trimIndent()

    private fun generatePowerShellScript(name: String, secret: String, baseUrl: String): String = """
# Auto-generated agent launch script for: $name
${'$'}ControllerUrl = "${'$'}{env:CONTROLLER_URL:-"$baseUrl"}"
${'$'}AgentName = "$name"
${'$'}AgentSecret = "$secret"
${'$'}WorkDir = if (${'$'}env:WORK_DIR) { ${'$'}env:WORK_DIR } else { "C:\agent" }
${'$'}AgentJar = "${'$'}WorkDir\agent.jar"

New-Item -ItemType Directory -Force -Path ${'$'}WorkDir | Out-Null
if (-not (Test-Path ${'$'}AgentJar)) {
    Invoke-WebRequest -Uri "${'$'}ControllerUrl/jnlpJars/agent.jar" -OutFile ${'$'}AgentJar
}

java -jar ${'$'}AgentJar -url ${'$'}ControllerUrl -name ${'$'}AgentName -secret ${'$'}AgentSecret -workDir ${'$'}WorkDir
""".trimIndent()
}
