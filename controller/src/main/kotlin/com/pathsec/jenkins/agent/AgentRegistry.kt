package com.pathsec.jenkins.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

data class AgentDefinition(
    val name: String,
    val secret: String,
    val labels: List<String> = emptyList(),
    val executors: Int = 1,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

class AgentRegistry(private val agentsFile: String) {
    private val log = LoggerFactory.getLogger(AgentRegistry::class.java)
    private val mapper = ObjectMapper().registerKotlinModule()
    private val agents = ConcurrentHashMap<String, AgentDefinition>()
    private val rng = SecureRandom()

    init {
        load()
    }

    fun register(name: String, labels: List<String>, executors: Int, description: String): AgentDefinition {
        val secret = generateSecret()
        val agent = AgentDefinition(name, secret, labels, executors, description)
        agents[name] = agent
        save()
        log.info("Registered agent: $name with labels $labels")
        return agent
    }

    fun get(name: String): AgentDefinition? = agents[name]

    fun list(): List<AgentDefinition> = agents.values.toList()

    fun remove(name: String): Boolean {
        val removed = agents.remove(name) != null
        if (removed) save()
        return removed
    }

    fun validateSecret(name: String, secret: String): Boolean {
        val agent = agents[name] ?: return false
        return agent.secret == secret
    }

    fun findByLabels(labels: List<String>): List<AgentDefinition> {
        if (labels.isEmpty()) return list()
        return agents.values.filter { agent ->
            labels.all { label -> label in agent.labels }
        }
    }

    private fun generateSecret(): String {
        val bytes = ByteArray(32)
        rng.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun load() {
        val file = File(agentsFile)
        if (!file.exists()) return
        try {
            val list: List<AgentDefinition> = mapper.readValue(file)
            list.forEach { agents[it.name] = it }
            log.info("Loaded ${agents.size} agents from $agentsFile")
        } catch (e: Exception) {
            log.warn("Failed to load agents file: ${e.message}")
        }
    }

    private fun save() {
        val file = File(agentsFile)
        file.parentFile?.mkdirs()
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, agents.values.toList())
    }
}
