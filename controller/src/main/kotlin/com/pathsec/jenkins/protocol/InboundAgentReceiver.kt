package com.pathsec.jenkins.protocol

import com.pathsec.jenkins.agent.AgentConnectionManager
import com.pathsec.jenkins.agent.AgentRegistry
import org.jenkinsci.remoting.engine.JnlpConnectionState
import org.jenkinsci.remoting.engine.JnlpConnectionStateListener
import org.jenkinsci.remoting.protocol.impl.ConnectionRefusalException
import org.slf4j.LoggerFactory

class InboundAgentReceiver(
    private val agentRegistry: AgentRegistry,
    private val connectionManager: AgentConnectionManager,
    private val remoteAddr: String
) : JnlpConnectionStateListener() {
    private val log = LoggerFactory.getLogger(InboundAgentReceiver::class.java)

    override fun beforeProperties(event: JnlpConnectionState) {
        log.debug("beforeProperties from $remoteAddr")
    }

    override fun afterProperties(event: JnlpConnectionState) {
        val agentName = event.getProperty(JnlpConnectionState.CLIENT_NAME_KEY)
        log.info("Agent handshake from $remoteAddr: name=$agentName")

        if (agentName == null) {
            log.warn("No agent name in handshake from $remoteAddr")
            event.reject(ConnectionRefusalException("Missing agent name"))
            return
        }

        val agent = agentRegistry.get(agentName)
        if (agent == null) {
            log.warn("Unknown agent: $agentName")
            event.reject(ConnectionRefusalException("Unknown agent: $agentName"))
            return
        }

        // Secret is validated by AgentClientDatabase (JnlpClientDatabase) before we get here
        log.info("Agent $agentName authenticated successfully")
        event.approve()
    }

    override fun afterChannel(event: JnlpConnectionState) {
        val agentName = event.getProperty(JnlpConnectionState.CLIENT_NAME_KEY) ?: "unknown"
        val channel = event.channel ?: run {
            log.error("No channel in afterChannel for $agentName")
            return
        }

        log.info("Channel established for agent $agentName")
        connectionManager.register(agentName, channel, remoteAddr)

        // Start ping thread for keepalive
        val ping = object : hudson.remoting.PingThread(channel, 10_000L, 60_000L) {
            override fun onDead() {
                log.warn("Ping timeout for agent $agentName")
                try { channel.close() } catch (_: Exception) {}
            }
            override fun onDead(cause: Throwable) {
                log.warn("Ping dead for agent $agentName: ${cause.message}")
                try { channel.close() } catch (_: Exception) {}
            }
        }
        ping.isDaemon = true
        ping.start()
    }

    override fun channelClosed(event: JnlpConnectionState) {
        val agentName = event.getProperty(JnlpConnectionState.CLIENT_NAME_KEY) ?: "unknown"
        log.info("Channel closed for agent $agentName from $remoteAddr")
    }
}
