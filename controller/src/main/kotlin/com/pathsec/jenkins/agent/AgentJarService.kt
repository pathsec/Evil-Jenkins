package com.pathsec.jenkins.agent

import org.slf4j.LoggerFactory
import java.io.File
import java.net.URL
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipInputStream

class AgentJarService(private val cacheDir: String) {
    private val log = LoggerFactory.getLogger(AgentJarService::class.java)
    private val defaultJarFile = File(cacheDir, "agent-default.jar")

    init {
        File(cacheDir).mkdirs()
        // Try to extract agent.jar from our resources (bundled at build time)
        extractBundledAgentJar()
        // If still not present (e.g. running without shadowJar), download automatically
        if (!defaultJarFile.exists()) {
            log.info("No bundled agent.jar found — downloading from Maven on first startup...")
            try {
                val generated = generateAgentJar()
                generated.copyTo(defaultJarFile, overwrite = true)
                log.info("agent.jar downloaded and cached (${defaultJarFile.length()} bytes)")
            } catch (e: Exception) {
                log.warn("Auto-download of agent.jar failed: ${e.message}. Use POST /api/agent-jar/generate to retry.")
            }
        }
    }

    private fun extractBundledAgentJar() {
        if (defaultJarFile.exists()) return
        val resource = AgentJarService::class.java.classLoader.getResourceAsStream("agent/agent.jar")
        if (resource != null) {
            log.info("Extracting bundled agent.jar to cache")
            resource.use { input ->
                defaultJarFile.outputStream().use { out -> input.copyTo(out) }
            }
        } else {
            log.warn("No bundled agent.jar found in resources. Will need to generate or download.")
        }
    }

    fun getAgentJar(): File? {
        if (defaultJarFile.exists()) return defaultJarFile
        return null
    }

    fun getAgentJarBytes(): ByteArray? {
        return getAgentJar()?.readBytes()
    }

    fun generateAgentJar(
        remotingVersion: String = "3341.v0766d82b_dec0",
        presetUrl: String? = null
    ): File {
        val targetFile = File(cacheDir, "agent-$remotingVersion.jar")
        if (targetFile.exists()) {
            log.info("Using cached agent jar for version $remotingVersion")
            return targetFile
        }

        log.info("Generating agent.jar for remoting version $remotingVersion")

        // Download remoting jar from Jenkins Maven repo
        val mavenUrl = "https://repo.jenkins-ci.org/releases/org/jenkins-ci/main/remoting/" +
                "$remotingVersion/remoting-$remotingVersion.jar"

        log.info("Downloading remoting jar from: $mavenUrl")
        val remotingBytes = URL(mavenUrl).readBytes()
        val tempFile = File(cacheDir, "remoting-$remotingVersion-temp.jar")
        tempFile.writeBytes(remotingBytes)

        // Repackage with correct manifest
        repackageJar(tempFile, targetFile)
        tempFile.delete()

        log.info("Generated agent.jar: ${targetFile.length()} bytes")
        return targetFile
    }

    private fun repackageJar(source: File, target: File) {
        val manifest = Manifest()
        manifest.mainAttributes.apply {
            putValue("Manifest-Version", "1.0")
            putValue("Main-Class", "hudson.remoting.Launcher")
            putValue("Premain-Class", "hudson.remoting.Launcher")
        }

        JarOutputStream(target.outputStream(), manifest).use { jos ->
            ZipInputStream(source.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    // Skip signature files and MANIFEST.MF (we provide our own)
                    if (!name.startsWith("META-INF/") || name == "META-INF/" ||
                        name.startsWith("META-INF/services/") ||
                        name.startsWith("META-INF/maven/")) {
                        try {
                            jos.putNextEntry(java.util.zip.ZipEntry(name))
                            zis.copyTo(jos)
                            jos.closeEntry()
                        } catch (e: java.util.zip.ZipException) {
                            // Skip duplicate entries
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }
    }
}
