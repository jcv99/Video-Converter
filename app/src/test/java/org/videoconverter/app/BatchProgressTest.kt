package org.videoconverter.app

import org.junit.Assert.assertEquals
import org.junit.Test

public class BatchProgressTest {
    @Test
    public fun unknownProgressStaysIndeterminate() {
        assertEquals(-1, batchProgress(total = 1, completed = 0, active = 1, reported = listOf(-1)))
        assertEquals(-1, batchProgress(total = 4, completed = 0, active = 3, reported = listOf(-1, -1)))
    }

    @Test
    public fun anEmptyBatchIsIndeterminate() {
        assertEquals(-1, batchProgress(total = 0, completed = 0, active = 0, reported = emptyList()))
    }

    @Test
    public fun aTerminalStateMakesTheBatchDeterminate() {
        assertEquals(25, batchProgress(total = 4, completed = 1, active = 1, reported = listOf(-1)))
        assertEquals(50, batchProgress(total = 2, completed = 1, active = 0, reported = emptyList()))
    }

    @Test
    public fun aKnownPercentageMakesTheBatchDeterminate() {
        assertEquals(50, batchProgress(total = 2, completed = 0, active = 1, reported = listOf(100)))
        assertEquals(75, batchProgress(total = 2, completed = 1, active = 1, reported = listOf(50)))
    }

    @Test
    public fun unknownReportsAreExcludedFromTheSum() {
        // The unknown worker is not worth zero, and it is not worth its share either.
        val mixed = batchProgress(total = 2, completed = 0, active = 2, reported = listOf(-1, 60))
        val onlyKnown = batchProgress(total = 2, completed = 0, active = 2, reported = listOf(60))
        assertEquals(onlyKnown, mixed)
        assertEquals(30, mixed)
    }

    @Test
    public fun progressNeverLeavesTheValidRange() {
        assertEquals(100, batchProgress(total = 1, completed = 1, active = 0, reported = emptyList()))
        assertEquals(0, batchProgress(total = 3, completed = 0, active = 0, reported = emptyList()))
    }
}
