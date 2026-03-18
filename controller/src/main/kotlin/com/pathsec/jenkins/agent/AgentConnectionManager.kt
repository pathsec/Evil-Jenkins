package com.pathsec.jenkins.agent

import hudson.remoting.Channel
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class AgentConnectionManager {
    private val log = LoggerFactory.getLogger(AgentConnectionManager::class.java)
    private val connections = ConcurrentHashMap<String, AgentConnection>()

    fun register(name: String, channel: Channel, remoteAddress: String = "") {
        val existing = connections[name]
        if (existing != null && existing.isAlive()) {
            log.warn("Agent $name already connected, closing old channel")
            try { existing.channel.close() } catch (e: Exception) { /* ignore */ }
        }
        val conn = AgentConnection(name = name, channel = channel, remoteAddress = remoteAddress)
        connections[name] = conn

        // Listen for channel closure
        channel.addListener(object : Channel.Listener() {
            override fun onClosed(channel: Channel, cause: java.io.IOException?) {
                log.info("Agent $name channel closed: ${cause?.message ?: "clean"}")
                connections[name]?.status = ConnectionStatus.DISCONNECTED
            }
        })

        log.info("Agent $name connected from $remoteAddress")
    }

    fun get(name: String): AgentConnection? {
        val conn = connections[name] ?: return null
        return if (conn.isAlive()) conn else null
    }

    fun getOrDisconnected(name: String): AgentConnection? = connections[name]

    fun list(): List<AgentConnection> = connections.values.toList()

    fun disconnect(name: String) {
        val conn = connections[name] ?: return
        try {
            conn.channel.close()
        } catch (e: Exception) {
            log.warn("Error closing channel for $name: ${e.message}")
        }
    }

    fun closeAll() {
        connections.values.forEach { conn ->
            try { conn.channel.close() } catch (e: Exception) { /* ignore */ }
        }
        connections.clear()
    }

    fun isConnected(name: String): Boolean = get(name) != null
}
