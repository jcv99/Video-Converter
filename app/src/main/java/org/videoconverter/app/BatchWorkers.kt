package org.videoconverter.app

import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

internal const val MIN_BATCH_WORKERS = 1
internal const val MAX_BATCH_WORKERS = 50
internal const val DEFAULT_BATCH_WORKERS = 20

internal suspend fun runBatchWorkers(
    positions: List<Int>,
    isCancelled: () -> Boolean,
    workerLimit: Int,
    convert: suspend (position: Int) -> Unit,
) = coroutineScope {
    val workers = workerLimit.coerceIn(MIN_BATCH_WORKERS, MAX_BATCH_WORKERS).coerceAtMost(positions.size)
    val queue = positions.iterator()
    repeat(workers) {
        launch {
            while (true) {
                val position = synchronized(queue) {
                    if (!isCancelled() && queue.hasNext()) queue.next() else null
                } ?: break
                convert(position)
            }
        }
    }
}
