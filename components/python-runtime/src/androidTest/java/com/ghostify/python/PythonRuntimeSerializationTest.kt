package com.ghostify.python

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * T-029 (device half) — concurrent bridge calls are serialized end to end.
 *
 * The interpreter assigns a global sequence number inside `probe_serial`.
 * Serialization means each call runs to completion before the next one starts,
 * so the n concurrent calls must observe n distinct, gapless sequence numbers
 * — duplicates or gaps would prove interleaving/corruption. The strict FIFO
 * ordering guarantee of the client dispatcher is additionally proven by the
 * JVM unit test `SerialExecutorTest`.
 */
@RunWith(AndroidJUnit4::class)
class PythonRuntimeSerializationTest : PythonRuntimeInstrumentedTest() {

    @Test
    fun t029_concurrentCallsAreSerialized() = runBlocking {
        val n = 12
        val results = (0 until n)
            .map { i -> async(Dispatchers.Default) { PythonRuntime.call("ghostify_dl", "probe_serial", "call-$i") } }
            .awaitAll()
            .map { it as Map<*, *> }

        val seqs = results.map { (it["seq"] as? Number)?.toLong() ?: -1L }
        val tags = results.map { it["tag"] as? String }

        assertEquals("every call must map to exactly one sequence number", results.size, seqs.size)
        assertEquals("sequence numbers must be unique (no interleaving)", seqs.distinct().size, seqs.size)
        assertEquals(
            "all $n sequence numbers must be present (no lost or duplicated work)",
            (1L..n.toLong()).toSet(),
            seqs.toSet(),
        )
        assertEquals("tags must map 1:1 to results", tags.size, tags.distinct().size)
    }
}
