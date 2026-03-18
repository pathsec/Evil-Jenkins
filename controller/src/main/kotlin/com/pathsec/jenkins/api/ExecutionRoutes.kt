package com.pathsec.jenkins.api

import com.pathsec.jenkins.agent.AgentConnectionManager
import com.pathsec.jenkins.agent.AgentRegistry
import com.pathsec.jenkins.execution.ScriptExecutor
import io.javalin.Javalin
import io.javalin.http.Context

class ExecutionRoutes(
    private val registry: AgentRegistry,
    private val connectionManager: AgentConnectionManager
) {
    private val executor = ScriptExecutor(registry, connectionManager)

    fun register(app: Javalin) {
        app.post("/api/execute", ::executeScript)
        app.get("/api/executions", ::listExecutions)
        app.get("/api/executions/{id}", ::getExecution)
        app.post("/api/executions/{id}/cancel", ::cancelExecution)
    }

    private fun executeScript(ctx: Context) {
        data class ExecuteRequest(
            val script: String,
            val target: String? = null,
            val labels: List<String> = emptyList(),
            val bindings: Map<String, String> = emptyMap(),
            val async: Boolean = false,
            val timeoutSeconds: Long = 60
        )

        val req = ctx.bodyAsClass(ExecuteRequest::class.java)
        if (req.script.isBlank()) {
            ctx.status(400).json(mapOf("error" to "Script is required"))
            return
        }

        val record = executor.execute(
            script = req.script,
            target = req.target,
            labels = req.labels,
            bindings = req.bindings,
            async = req.async,
            timeoutSeconds = req.timeoutSeconds
        )

        ctx.json(mapOf(
            "executionId" to record.id,
            "status" to record.status.name,
            "results" to record.results.map { r ->
                mapOf(
                    "agent" to r.agent,
                    "status" to r.status.name,
                    "output" to r.output,
                    "durationMs" to r.durationMs,
                    "error" to r.error
                )
            }
        ))
    }

    private fun listExecutions(ctx: Context) {
        ctx.json(executor.list().take(100).map { r ->
            mapOf(
                "id" to r.id,
                "status" to r.status.name,
                "target" to r.target,
                "createdAt" to r.createdAt.toString(),
                "completedAt" to r.completedAt?.toString()
            )
        })
    }

    private fun getExecution(ctx: Context) {
        val id = ctx.pathParam("id")
        val record = executor.get(id) ?: run {
            ctx.status(404).json(mapOf("error" to "Execution not found"))
            return
        }
        ctx.json(mapOf(
            "id" to record.id,
            "status" to record.status.name,
            "script" to record.script,
            "target" to record.target,
            "labels" to record.labels,
            "createdAt" to record.createdAt.toString(),
            "completedAt" to record.completedAt?.toString(),
            "results" to record.results.map { r ->
                mapOf(
                    "agent" to r.agent,
                    "status" to r.status.name,
                    "output" to r.output,
                    "durationMs" to r.durationMs,
                    "error" to r.error
                )
            }
        ))
    }

    private fun cancelExecution(ctx: Context) {
        val id = ctx.pathParam("id")
        if (executor.cancel(id)) {
            ctx.json(mapOf("status" to "cancelled", "id" to id))
        } else {
            ctx.status(404).json(mapOf("error" to "Execution not found or not cancellable"))
        }
    }
}
