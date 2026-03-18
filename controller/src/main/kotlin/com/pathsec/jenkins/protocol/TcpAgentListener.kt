package com.pathsec.jenkins.protocol

import com.pathsec.jenkins.agent.AgentConnectionManager
import com.pathsec.jenkins.agent.AgentRegistry
import com.pathsec.jenkins.identity.ControllerIdentity
import org.jenkinsci.remoting.engine.JnlpProtocol4Handler
import org.jenkinsci.remoting.protocol.IOHub
import org.slf4j.LoggerFactory
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class TcpAgentListener(
    private val port: Int,
    private val identity: ControllerIdentity,
    private val agentRegistry: AgentRegistry,
    private val connectionManager: AgentConnectionManager
) {
    private val log = LoggerFactory.getLogger(TcpAgentListener::class.java)
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val acceptExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tcp-agent-listener-accept").also { it.isDaemon = true }
    }
    private val connectionExecutor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "tcp-agent-connection").also { it.isDaemon = true }
    }

    private var ioHub: IOHub? = null
    private var protocol4Handler: JnlpProtocol4Handler? = null

    fun start() {
        running.set(true)

        // IOHub is the NIO event loop required by JnlpProtocol4Handler
        ioHub = IOHub.create(connectionExecutor)

        val clientDatabase = AgentClientDatabase(agentRegistry)
        val sslContext = identity.createSSLContext()

        protocol4Handler = JnlpProtocol4Handler(
            clientDatabase,
            connectionExecutor,
            ioHub!!,
            sslContext,
            false, // server mode (false = controller, true = client/agent)
            true   // prefer NIO
        )

        serverSocket = ServerSocket(port)
        log.info("TCP agent listener bound to port $port")

        acceptExecutor.submit {
            while (running.get()) {
                try {
                    val socket = serverSocket!!.accept()
                    log.debug("Accepted connection from ${socket.remoteSocketAddress}")
                    handleConnection(socket)
                } catch (e: Exception) {
                    if (running.get()) {
                        log.warn("Error accepting connection: ${e.message}")
                    }
                }
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        connectionExecutor.submit {
            try {
                socket.tcpNoDelay = true
                val remoteAddr = socket.remoteSocketAddress.toString()
                log.debug("Handling connection from $remoteAddr")

                // Jenkins agents send a DataInputStream.readUTF() banner before the JNLP4
                // handshake begins: 2-byte length prefix + "Protocol:JNLP4-connect"
                // We must consume it before handing the socket to JnlpProtocol4Handler.
                val banner = DataInputStream(socket.inputStream).readUTF()
                log.debug("Protocol banner from $remoteAddr: $banner")
                when {
                    banner == "Protocol:Ping" -> {
                        socket.outputStream.write("Welcome\n".toByteArray())
                        socket.close()
                        return@submit
                    }
                    banner != "Protocol:JNLP4-connect" -> {
                        log.warn("Unsupported protocol from $remoteAddr: $banner")
                        socket.close()
                        return@submit
                    }
                }

                val receiver = InboundAgentReceiver(agentRegistry, connectionManager, remoteAddr)

                // JnlpProtocol4Handler.handle() takes java.net.Socket directly
                val future = protocol4Handler!!.handle(
                    socket,
                    emptyMap(),
                    listOf(receiver)
                )

                try {
                    future.get(60, TimeUnit.SECONDS)
                    log.debug("JNLP4 handshake completed for $remoteAddr")
                } catch (e: TimeoutException) {
                    log.warn("JNLP4 handshake timed out for $remoteAddr")
                    try { socket.close() } catch (_: Exception) {}
                } catch (e: Exception) {
                    log.warn("JNLP4 handshake failed for $remoteAddr: ${e.message}")
                    try { socket.close() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                log.warn("Error handling connection: ${e.message}")
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        acceptExecutor.shutdown()
        connectionExecutor.shutdown()
        ioHub?.close()
    }
}
