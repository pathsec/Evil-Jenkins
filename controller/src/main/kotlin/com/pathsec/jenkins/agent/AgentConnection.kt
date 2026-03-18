package com.pathsec.jenkins.agent

import hudson.remoting.Channel
import java.time.Instant

enum class ConnectionStatus { CONNECTED, DISCONNECTED, CONNECTING }

data class AgentConnection(
    val name: String,
    val channel: Channel,
    val connectedAt: Instant = Instant.now(),
    var status: ConnectionStatus = ConnectionStatus.CONNECTED,
    val remoteAddress: String = ""
) {
    fun isAlive(): Boolean = !channel.isClosingOrClosed && status == ConnectionStatus.CONNECTED
}
