package com.epam.drill.plugins.test2code

import com.epam.drill.plugin.api.processing.*
import com.epam.drill.plugins.test2code.common.api.*
import kotlinx.atomicfu.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.*
import kotlin.coroutines.*

/**
 * Provides boolean array for the probe.
 * Implementations must be kotlin singleton objects.
 */
typealias ProbeArrayProvider = (Long, String, Int) -> BooleanArray

interface SessionProbeArrayProvider : ProbeArrayProvider {
    fun start(sessionId: String, testType: String, eventCallback: (Sequence<ExecDatum>) -> Unit = {})
    fun stop(sessionId: String): Sequence<ExecDatum>?
    fun cancel(sessionId: String)
}

const val DRIlL_TEST_NAME = "drill-test-name"

class ExecDatum(
    val id: Long,
    val name: String,
    val probes: BooleanArray,
    val testName: String = ""
)

fun ExecDatum.toExecClassData() = ExecClassData(
    id = id,
    className = name,
    probes = probes.toList(),
    testName = testName
)

typealias ExecData = PersistentMap<Long, ExecDatum>

internal val emptyExecData: ExecData = persistentHashMapOf()


object ProbesWorker : CoroutineScope {
    override val coroutineContext: CoroutineContext =
        Executors.newFixedThreadPool(4).asCoroutineDispatcher() + SupervisorJob()

    operator fun invoke(block: suspend () -> Unit) = launch { block() }

}

/**
 * A container for session runtime data and optionally runtime data of tests
 * TODO ad hoc implementation, rewrite to something more descent
 */
class ExecRuntime(
    private val dataStream: TimeSpanEventBus<ExecDatum>?
) : (Long, String, Int, String) -> BooleanArray {

    private val _execData = atomic(persistentHashMapOf<String, ExecData>())

    override fun invoke(
        id: Long,
        name: String,
        probeCount: Int,
        testName: String
    ): BooleanArray = _execData.updateAndGet { tests ->
        (tests[testName] ?: emptyExecData).let { execData ->
            if (id !in execData) {
                val mutatedData = execData.put(
                    id, ExecDatum(
                        id = id,
                        name = name,
                        probes = BooleanArray(probeCount),
                        testName = testName
                    )
                )
                tests.put(testName, mutatedData)
            } else tests
        }
    }[testName]!![id]!!.also { execDatum ->
        dataStream?.let { stream -> ProbesWorker { stream.send(execDatum) } }
    }.probes

    fun start(eventCallback: (Sequence<ExecDatum>) -> Unit) = dataStream?.let { stream ->
        ProbesWorker.launch {
            stream.collect { dataSeq -> eventCallback(dataSeq) }
        }
    }

    fun collect(): Sequence<ExecDatum> = _execData.value.values.asSequence().flatMap { it.values.asSequence() }

    fun close() {
        dataStream?.close()
    }
}

/**
 * Simple probe array provider that employs a lock-free map for runtime data storage.
 * This class is intended to be an ancestor for a concrete probe array provider object.
 * The provider must be a Kotlin singleton object, otherwise the instrumented probe calls will fail.
 */
open class SimpleSessionProbeArrayProvider(
    private val instrContext: IDrillContex = DrillContext,
    private val probeStreamPrv: () -> TimeSpanEventBus<ExecDatum>? = {
        TimeSpanEventBusImpl(
            10,
            coroutineScope = GlobalScope
        )
    }
) : SessionProbeArrayProvider {

    private val sessionRuntimes get() = _runtimes.value

    private val _runtimes = atomic(persistentHashMapOf<String, ExecRuntime>())

    override fun invoke(
        id: Long,
        name: String,
        probeCount: Int
    ): BooleanArray = instrContext()?.let { sessionId ->
        sessionRuntimes[sessionId]?.let { sessionRuntime ->
            val testName = instrContext[DRIlL_TEST_NAME] ?: "default"
            sessionRuntime(id, name, probeCount, testName)
        }
    } ?: BooleanArray(probeCount)

    override fun start(
        sessionId: String, testType: String, eventCallback: (Sequence<ExecDatum>) -> Unit
    ) {
        val execRuntime = _runtimes.updateAndGet {
            it + (sessionId to ExecRuntime(probeStreamPrv()))
        }[sessionId]!!
        execRuntime.start(eventCallback)
    }

    override fun stop(sessionId: String): Sequence<ExecDatum>? = remove(sessionId)?.collect()

    override fun cancel(sessionId: String) = remove(sessionId).let { Unit }

    private fun remove(sessionId: String): ExecRuntime? = _runtimes.getAndUpdate {
        it - sessionId
    }[sessionId]?.also(ExecRuntime::close)
}
