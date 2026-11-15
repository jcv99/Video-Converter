package org.videoconverter.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

public class BatchWorkersTest {
    @Test
    public fun selectedLimitBoundsWork() {
        runBlocking {
            for (limit in listOf(1, 20, 50)) {
                withTimeout(5_000) {
                    val allActive = CompletableDeferred<Unit>()
                    var active = 0
                    var peak = 0
                    val finished = mutableListOf<Int>()
                    val positions = (0 until 55).toList()
                    runBatchWorkers(positions, { false }, workerLimit = limit) { position ->
                        active++
                        peak = maxOf(peak, active)
                        if (active == limit) allActive.complete(Unit)
                        allActive.await()
                        active--
                        finished += position
                    }
                    assertEquals(limit, peak)
                    assertEquals(positions, finished.sorted())
                }
            }
        }
    }

    @Test
    public fun cancellationLetsInFlightWorkFinishAndSkipsQueue() {
        runBlocking {
            withTimeout(5_000) {
                val twoActive = CompletableDeferred<Unit>()
                val started = mutableListOf<Int>()
                val finished = mutableListOf<Int>()
                var cancelled = false
                runBatchWorkers(listOf(0, 1, 2, 3), { cancelled }, workerLimit = 2) { position ->
                    started += position
                    if (started.size == 2) {
                        cancelled = true
                        twoActive.complete(Unit)
                    }
                    twoActive.await()
                    finished += position
                }
                assertEquals(listOf(0, 1), started)
                assertEquals(started, finished.sorted())
            }
        }
    }

    @Test
    public fun oneFileGetsOneAttempt() {
        runBlocking {
            var attempts = 0
            runBatchWorkers(listOf(7), { false }, workerLimit = DEFAULT_BATCH_WORKERS) { position ->
                assertEquals(7, position)
                attempts++
            }
            assertEquals(1, attempts)
        }
    }
}
