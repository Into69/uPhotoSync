package dev.uphotosync.app.smb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Lets at most [permits] callers run at once, like a semaphore, but hands a freed slot to the
 * most recent waiter first (last in, first out).
 *
 * Used for NAS thumbnails: while scrolling, the files that just came on screen are the ones
 * worth loading, and ones that scrolled away leave the queue.
 */
class LifoGate(private val permits: Int) {
    private val lock = Any()
    private var free = permits
    private val waiters = ArrayDeque<CompletableDeferred<Unit>>()

    /** True when nothing is running or waiting. */
    private val idle = MutableStateFlow(true)

    suspend fun <T> withPermit(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    /** Suspends until nothing is running or waiting, for low-priority background work. */
    suspend fun awaitIdle() {
        idle.first { it }
    }

    private fun updateIdle() {
        idle.value = free == permits && waiters.isEmpty()
    }

    private suspend fun acquire() {
        val ticket = synchronized(lock) {
            if (free > 0) {
                free--
                updateIdle()
                return
            }
            CompletableDeferred<Unit>().also {
                waiters.addFirst(it)
                updateIdle()
            }
        }
        try {
            ticket.await()
        } catch (e: CancellationException) {
            // Cancelled while queued: leave the queue. If a slot was handed over at the same
            // moment, pass it on so it isn't lost.
            val handedOver = synchronized(lock) {
                val queued = waiters.remove(ticket)
                updateIdle()
                !queued
            }
            if (handedOver) release()
            throw e
        }
    }

    private fun release() {
        val next = synchronized(lock) {
            val n = waiters.removeFirstOrNull()
            if (n == null) free++
            updateIdle()
            n
        }
        next?.complete(Unit)
    }
}
