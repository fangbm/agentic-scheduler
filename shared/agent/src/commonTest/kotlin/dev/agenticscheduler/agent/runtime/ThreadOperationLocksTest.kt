package dev.agenticscheduler.agent.runtime

import dev.agenticscheduler.agent.history.AgentThreadId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadOperationLocksTest {
    @Test
    fun `operations for one thread wait for the active operation`() = runBlocking {
        val locks = ThreadOperationLocks()
        val threadId = AgentThreadId("00000000-0000-7000-8000-000000000001")
        val firstOperationEntered = CompletableDeferred<Unit>()
        val releaseFirstOperation = CompletableDeferred<Unit>()
        val completed = mutableListOf<Int>()

        val first = async {
            locks.withLock(threadId) {
                firstOperationEntered.complete(Unit)
                releaseFirstOperation.await()
                completed += 1
            }
        }
        firstOperationEntered.await()

        val second = async {
            locks.withLock(threadId) { completed += 2 }
        }
        yield()

        assertEquals(emptyList(), completed)
        releaseFirstOperation.complete(Unit)
        first.await()
        second.await()

        assertEquals(listOf(1, 2), completed)
    }
}
