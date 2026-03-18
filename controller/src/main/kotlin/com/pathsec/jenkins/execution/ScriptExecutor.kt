package com.pathsec.jenkins.execution

import com.pathsec.jenkins.agent.AgentConnectionManager
import com.pathsec.jenkins.agent.AgentRegistry
import groovy.lang.Script
import hudson.remoting.Channel
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

enum class ExecutionStatus { PENDING, RUNNING, SUCCESS, ERROR, TIMEOUT, CANCELLED }

data class AgentExecutionResult(
    val agent: String,
    val status: ExecutionStatus,
    val output: String,
    val durationMs: Long,
    val error: String? = null
)

data class ExecutionRecord(
    val id: String = UUID.randomUUID().toString(),
    val script: String,
    val target: String?,
    val labels: List<String>,
    val bindings: Map<String, String>,
    val async: Boolean,
    val timeoutSeconds: Long,
    val createdAt: Instant = Instant.now(),
    var completedAt: Instant? = null,
    var status: ExecutionStatus = ExecutionStatus.PENDING,
    var results: List<AgentExecutionResult> = emptyList()
)

class ScriptExecutor(
    private val agentRegistry: AgentRegistry,
    private val connectionManager: AgentConnectionManager
) {
    private val log = LoggerFactory.getLogger(ScriptExecutor::class.java)
    private val history = ConcurrentHashMap<String, ExecutionRecord>()
    private val activeFutures = ConcurrentHashMap<String, List<Future<*>>>()

    fun execute(
        script: String,
        target: String? = null,
        labels: List<String> = emptyList(),
        bindings: Map<String, String> = emptyMap(),
        async: Boolean = false,
        timeoutSeconds: Long = 60
    ): ExecutionRecord {
        val record = ExecutionRecord(
            script = script,
            target = target,
            labels = labels,
            bindings = bindings,
            async = async,
            timeoutSeconds = timeoutSeconds
        )
        history[record.id] = record

        // Determine target agents
        val targetAgents = when {
            target != null -> listOfNotNull(connectionManager.get(target)).also {
                if (it.isEmpty()) log.warn("Agent $target not connected")
            }
            labels.isNotEmpty() -> {
                val named = agentRegistry.findByLabels(labels)
                named.mapNotNull { connectionManager.get(it.name) }
            }
            else -> connectionManager.list().filter { it.isAlive() }
        }

        if (targetAgents.isEmpty()) {
            record.status = ExecutionStatus.ERROR
            record.results = listOf(
                AgentExecutionResult("none", ExecutionStatus.ERROR, "", 0, "No connected agents found")
            )
            record.completedAt = Instant.now()
            return record
        }

        record.status = ExecutionStatus.RUNNING

        // Compile on the controller — agent only receives bytecode, no Groovy compiler needed there.
        val callable = try {
            GroovyScriptCallable.compile(script, bindings)
        } catch (e: Exception) {
            record.status = ExecutionStatus.ERROR
            record.results = listOf(AgentExecutionResult("controller", ExecutionStatus.ERROR, "", 0,
                "Compilation failed: ${buildErrorMessage(e)}"))
            record.completedAt = Instant.now()
            return record
        }

        if (async) {
            val futures = targetAgents.map { conn ->
                preloadGroovyRuntime(conn.channel)
                conn.channel.callAsync(callable)
            }
            activeFutures[record.id] = futures

            // Poll in background
            Thread {
                val results = targetAgents.mapIndexed { i, conn ->
                    val start = System.currentTimeMillis()
                    try {
                        val output = futures[i].get(timeoutSeconds, TimeUnit.SECONDS)
                        val elapsed = System.currentTimeMillis() - start
                        AgentExecutionResult(conn.name, ExecutionStatus.SUCCESS, output, elapsed)
                    } catch (e: TimeoutException) {
                        futures[i].cancel(true)
                        AgentExecutionResult(conn.name, ExecutionStatus.TIMEOUT, "", System.currentTimeMillis() - start, "Timeout after ${timeoutSeconds}s")
                    } catch (e: Exception) {
                        val fullError = buildErrorMessage(e)
                        log.error("Async script execution failed on agent ${conn.name}: $fullError", e)
                        AgentExecutionResult(conn.name, ExecutionStatus.ERROR, "", System.currentTimeMillis() - start, fullError)
                    }
                }
                record.results = results
                record.status = if (results.all { it.status == ExecutionStatus.SUCCESS }) ExecutionStatus.SUCCESS else ExecutionStatus.ERROR
                record.completedAt = Instant.now()
                activeFutures.remove(record.id)
            }.also { it.isDaemon = true }.start()

            return record
        } else {
            val results = targetAgents.map { conn ->
                val start = System.currentTimeMillis()
                try {
                    preloadGroovyRuntime(conn.channel)
                    val output = conn.channel.call(callable)
                    val elapsed = System.currentTimeMillis() - start
                    AgentExecutionResult(conn.name, ExecutionStatus.SUCCESS, output, elapsed)
                } catch (e: Exception) {
                    val elapsed = System.currentTimeMillis() - start
                    val fullError = buildErrorMessage(e)
                    log.error("Script execution failed on agent ${conn.name}: $fullError", e)
                    AgentExecutionResult(conn.name, ExecutionStatus.ERROR, "", elapsed, fullError)
                }
            }
            record.results = results
            record.status = if (results.all { it.status == ExecutionStatus.SUCCESS }) ExecutionStatus.SUCCESS else ExecutionStatus.ERROR
            record.completedAt = Instant.now()
            return record
        }
    }

    // Push the Groovy runtime JAR to the agent so groovy.lang.Script and Binding
    // are available for the pre-compiled bytecode to run against.
    private fun preloadGroovyRuntime(channel: Channel) {
        try {
            channel.preloadJar(ScriptExecutor::class.java.classLoader, Script::class.java)
        } catch (e: Exception) {
            log.warn("Failed to preload Groovy runtime on channel ${channel.name}: ${e.message}")
        }
    }

    // Unwrap the full cause chain into a readable message.
    private fun buildErrorMessage(e: Throwable): String {
        val sb = StringBuilder(e.toString())
        var cause = e.cause
        while (cause != null) {
            sb.append("\nCaused by: ").append(cause.toString())
            cause = cause.cause
        }
        return sb.toString()
    }

    fun get(id: String): ExecutionRecord? = history[id]

    fun list(): List<ExecutionRecord> = history.values.sortedByDescending { it.createdAt }

    fun cancel(id: String): Boolean {
        val futures = activeFutures[id] ?: return false
        futures.forEach { it.cancel(true) }
        history[id]?.also {
            it.status = ExecutionStatus.CANCELLED
            it.completedAt = Instant.now()
        }
        activeFutures.remove(id)
        return true
    }
}
