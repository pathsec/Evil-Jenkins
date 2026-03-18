import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.DuplicatesStrategy

plugins {
    kotlin("jvm") version "1.9.25"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.jenkins-ci.org/releases/") }
    maven { url = uri("https://repo.jenkins-ci.org/public/") }
}

val remotingVersion = "3341.v0766d82b_dec0"

configurations.create("remotingAgent")

dependencies {
    "remotingAgent"("org.jenkins-ci.main:remoting:$remotingVersion")

    implementation("org.jenkins-ci.main:remoting:$remotingVersion")
    implementation("org.apache.groovy:groovy:4.0.24")
    implementation("io.javalin:javalin:6.4.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")
    implementation("ch.qos.logback:logback-classic:1.5.12")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.79")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.pathsec.jenkins.MainKt")
}

val generateAgentJar by tasks.registering(Jar::class) {
    archiveFileName.set("agent.jar")
    destinationDirectory.set(layout.buildDirectory.dir("agent"))

    from(configurations.named("remotingAgent").map { config ->
        config.map { if (it.isDirectory) it else zipTree(it) }
    })

    manifest {
        attributes(
            "Main-Class" to "hudson.remoting.Launcher",
            "Premain-Class" to "hudson.remoting.Launcher"
        )
    }

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(generateAgentJar)
    from(generateAgentJar.map { it.destinationDirectory }) {
        into("agent")
    }
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveBaseName.set("controller")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named("build") {
    dependsOn(generateAgentJar)
}
