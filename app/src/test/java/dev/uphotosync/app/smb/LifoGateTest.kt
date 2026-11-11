package dev.uphotosync.app.smb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

class LifoGateTest {

    @Test fun newestWaiterGoesFirst() = runBlocking(Dispatchers.Default) {
        val gate = LifoGate(1)
        val order = Collections.synchronizedList(ArrayList<String>())
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val holder = launch { gate.withPermit { holding.complete(Unit); release.await() } }
        holding.await()
        val first = launch { gate.withPermit { order += "first" } }
        waitForQueue()
        val second = launch { gate.withPermit { order += "second" } }
        waitForQueue()

        release.complete(Unit)
        holder.join(); first.join(); second.join()
        assertEquals(listOf("second", "first"), order)
    }

    @Test fun cancelledWaiterDoesNotBlockOthers() = runBlocking(Dispatchers.Default) {
        val gate = LifoGate(1)
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch { gate.withPermit { holding.complete(Unit); release.await() } }
        holding.await()

        val cancelled = launch { gate.withPermit { error("should not run") } }
        waitForQueue()
        cancelled.cancel()
        cancelled.join()

        release.complete(Unit)
        holder.join()
        var ran = false
        gate.withPermit { ran = true }
        assertEquals(true, ran)
    }

    @Test fun allowsUpToPermitsAtOnce() = runBlocking(Dispatchers.Default) {
        val gate = LifoGate(3)
        val inside = java.util.concurrent.atomic.AtomicInteger()
        var peak = 0
        val jobs = List(10) {
            launch {
                gate.withPermit {
                    val now = inside.incrementAndGet()
                    synchronized(this@LifoGateTest) { peak = maxOf(peak, now) }
                    repeat(5) { yield() }
                    inside.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.join() }
        assertEquals(true, peak in 1..3)
    }

    private suspend fun waitForQueue() {
        // Give the launched coroutine time to reach the gate and queue up.
        repeat(20) { yield() }
        kotlinx.coroutines.delay(20)
    }
}
