package com.epam.drill.plugins.test2code

import com.epam.drill.logger.api.*
import org.jacoco.core.internal.flow.*
import org.jacoco.core.internal.instr.*
import org.objectweb.asm.*

/**
 * Instrumenter type
 */
typealias DrillInstrumenter = (String, Long, ByteArray) -> ByteArray?

/**
 * JaCoCo instrumenter
 */
fun instrumenter(probeArrayProvider: ProbeArrayProvider, logger: Logger): DrillInstrumenter {
    return CustomInstrumenter(probeArrayProvider, logger)
}

private class CustomInstrumenter(
    private val probeArrayProvider: ProbeArrayProvider,
    private val logger: Logger
) : DrillInstrumenter {

    override fun invoke(className: String, classId: Long, classBody: ByteArray): ByteArray? = try {
        instrument(className, classId, classBody)
    } catch (e: Exception) {
        logger.error { "Error while instrumenting $className classId=$classId: ${e.message}" }
        null
    }

    fun instrument(className: String, classId: Long, classBody: ByteArray): ByteArray {
        val version = InstrSupport.getMajorVersion(classBody)

        //count probes before transformation
        val counter = ProbeCounter()
        val reader = InstrSupport.classReaderFor(classBody)
        reader.accept(ClassProbesAdapter(counter, false), 0)

        val strategy = DrillProbeStrategy(
            probeArrayProvider,
            className,
            classId,
            counter.count
        )
        val writer = object : ClassWriter(reader, 0) {
            override fun getCommonSuperClass(type1: String, type2: String): String = throw IllegalStateException()
        }
        val visitor = ClassProbesAdapter(
            ClassInstrumenter(strategy, writer),
            InstrSupport.needsFrames(version)
        )
        reader.accept(visitor, ClassReader.EXPAND_FRAMES)
        return writer.toByteArray()
    }
}

private class ProbeCounter : ClassProbesVisitor() {
    var count = 0
        private set

    override fun visitMethod(
        access: Int, name: String?, desc: String?, signature: String?, exceptions: Array<out String>?
    ): MethodProbesVisitor? {
        return null
    }

    override fun visitTotalProbeCount(count: Int) {
        this.count = count
    }

}


private class DrillProbeStrategy(
    private val probeArrayProvider: ProbeArrayProvider,
    private val className: String,
    private val classId: Long,
    private val probeCount: Int
) : IProbeArrayStrategy {
    override fun storeInstance(mv: MethodVisitor?, clinit: Boolean, variable: Int): Int = mv!!.run {
        val drillClassName = probeArrayProvider.javaClass.name.replace('.', '/')
        visitFieldInsn(Opcodes.GETSTATIC, drillClassName, "INSTANCE", "L$drillClassName;")
        // Stack[0]: Lcom/epam/drill/jacoco/Stuff;

        visitLdcInsn(classId)
        visitLdcInsn(className)
        visitLdcInsn(probeCount)
        visitMethodInsn(
            Opcodes.INVOKEVIRTUAL, drillClassName, "invoke", "(JLjava/lang/String;I)[Z",
            false
        )
        visitVarInsn(Opcodes.ASTORE, variable)
        5 //stack size
    }

    override fun addMembers(cv: ClassVisitor?, probeCount: Int) {}
}
