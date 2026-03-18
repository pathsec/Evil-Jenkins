package com.pathsec.jenkins.identity

import com.pathsec.jenkins.config.ServerConfig
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.slf4j.LoggerFactory
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

class ControllerIdentity(private val config: ServerConfig.IdentitySection) {
    private val log = LoggerFactory.getLogger(ControllerIdentity::class.java)

    val keyStore: KeyStore
    val keyPair: KeyPair
    val certificate: X509Certificate
    val fingerprint: String

    // Base64-encoded DER public key (for X-Instance-Identity header)
    val instanceIdentity: String

    init {
        val keystoreFile = File(config.keystorePath)
        keystoreFile.parentFile?.mkdirs()

        keyStore = KeyStore.getInstance("JKS")

        if (keystoreFile.exists()) {
            log.info("Loading existing keystore from ${config.keystorePath}")
            keystoreFile.inputStream().use {
                keyStore.load(it, config.keystorePassword.toCharArray())
            }
            val cert = keyStore.getCertificate("controller") as X509Certificate
            val privateKey = keyStore.getKey("controller", config.keystorePassword.toCharArray()) as java.security.PrivateKey
            val publicKey = cert.publicKey
            keyPair = KeyPair(publicKey, privateKey)
            certificate = cert
        } else {
            log.info("Generating new controller identity keypair")
            keyStore.load(null, config.keystorePassword.toCharArray())

            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            keyPair = kpg.generateKeyPair()

            val subject = X500Name("CN=Jenkins Agent Engine Controller")
            val now = Date()
            val notAfter = Date(now.time + 10L * 365 * 24 * 60 * 60 * 1000)

            val certBuilder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(System.currentTimeMillis()),
                now,
                notAfter,
                subject,
                keyPair.public
            )

            val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
            certificate = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))

            keyStore.setKeyEntry(
                "controller",
                keyPair.private,
                config.keystorePassword.toCharArray(),
                arrayOf(certificate)
            )

            keystoreFile.outputStream().use {
                keyStore.store(it, config.keystorePassword.toCharArray())
            }
            log.info("Keystore saved to ${config.keystorePath}")
        }

        // Compute SHA-256 fingerprint
        val md = MessageDigest.getInstance("SHA-256")
        fingerprint = md.digest(certificate.encoded)
            .joinToString(":") { "%02X".format(it) }

        // Instance identity = base64 of DER-encoded public key
        instanceIdentity = Base64.getEncoder().encodeToString(keyPair.public.encoded)
    }

    fun createSSLContext(): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, config.keystorePassword.toCharArray())

        val trustStore = KeyStore.getInstance("JKS")
        trustStore.load(null, null)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore as KeyStore?)

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, tmf.trustManagers, null)
        return ctx
    }
}
