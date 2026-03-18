package com.pathsec.jenkins.protocol

import com.pathsec.jenkins.agent.AgentRegistry
import org.jenkinsci.remoting.engine.JnlpClientDatabase
import org.slf4j.LoggerFactory

class AgentClientDatabase(private val registry: AgentRegistry) : JnlpClientDatabase() {
    private val log = LoggerFactory.getLogger(AgentClientDatabase::class.java)

    override fun exists(clientName: String): Boolean {
        val exists = registry.get(clientName) != null
        log.debug("exists($clientName) = $exists")
        return exists
    }

    override fun getSecretOf(clientName: String): String? {
        val secret = registry.get(clientName)?.secret
        log.debug("getSecretOf($clientName) = ${if (secret != null) "found" else "null"}")
        return secret
    }
}
