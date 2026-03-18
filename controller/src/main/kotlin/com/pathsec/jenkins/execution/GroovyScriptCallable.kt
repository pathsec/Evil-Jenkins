package com.pathsec.jenkins.execution

import groovy.lang.Binding
import groovy.lang.Script
import hudson.remoting.Callable
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.jenkinsci.remoting.RoleChecker
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Executes a Groovy script on an agent using pre-compiled bytecode.
 *
 * Compilation happens on the controller (stable JVM + known Groovy/ASM version).
 * The agent only receives bytecode and runs it — no Groovy compiler or ASM
 * needed on the agent side, so any JVM version is supported.
 *
 * Use [GroovyScriptCallable.compile] to produce an instance from source.
 */
class GroovyScriptCallable private constructor(
    private val compiledClasses: Map<String, ByteArray>, // class name -> bytecode
    private val mainClassName: String,
    private val bindings: Map<String, String>
) : Callable<String, RuntimeException> {

    override fun checkRoles(checker: RoleChecker) {}

    override fun call(): String {
        val out = StringWriter()
        val pw = PrintWriter(out)

        // A classloader that resolves the pre-compiled script classes from the
        // embedded bytecode, delegating everything else to the agent's classloader.
        val parent = Thread.currentThread().contextClassLoader
        val loader = object : ClassLoader(parent) {
            override fun findClass(name: String): Class<*> {
                val bytes = compiledClasses[name] ?: throw ClassNotFoundException(name)
                return defineClass(name, bytes, 0, bytes.size)
            }
        }

        try {
            val scriptClass = loader.loadClass(mainClassName)
            val binding = Binding()
            binding.setVariable("out", pw)
            bindings.forEach { (k, v) -> binding.setVariable(k, v) }

            val script = scriptClass.getDeclaredConstructor(Binding::class.java)
                .newInstance(binding) as Script
            val result = script.run()
            if (result != null) pw.println("Result: $result")
        } catch (e: Exception) {
            e.printStackTrace(pw)
        } finally {
            pw.flush()
        }

        return out.toString()
    }

    companion object {
        private const val serialVersionUID = 2L

        /**
         * Compile [script] on the controller and return a dispatch-ready Callable.
         * Targets JDK 17 bytecode so the result runs on any agent with Java 17+.
         */
        fun compile(script: String, bindings: Map<String, String> = emptyMap()): GroovyScriptCallable {
            val config = CompilerConfiguration().apply {
                targetBytecode = CompilerConfiguration.JDK17
            }
            val unit = CompilationUnit(config)
            unit.addSource("Script.groovy", script)
            unit.compile(Phases.CLASS_GENERATION)

            val classes = mutableMapOf<String, ByteArray>()
            @Suppress("UNCHECKED_CAST")
            (unit.classes as List<*>).forEach { gc ->
                val name = gc!!.javaClass.getMethod("getName").invoke(gc) as String
                val bytes = gc.javaClass.getMethod("getBytes").invoke(gc) as ByteArray
                classes[name] = bytes
            }

            val mainClassName = (unit.classes as List<*>).first()!!.let {
                it.javaClass.getMethod("getName").invoke(it) as String
            }

            return GroovyScriptCallable(classes, mainClassName, bindings)
        }
    }
}
