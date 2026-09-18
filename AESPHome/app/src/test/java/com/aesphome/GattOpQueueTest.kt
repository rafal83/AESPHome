package com.aesphome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Drives GattOpQueue with a fake, manually-advanced clock instead of a real Handler — pending
// timeout Runnables are just stored with their delay and only actually invoked when the test
// calls fire(), so these run instantly and deterministically instead of sleeping for real.
private class FakeScheduler {
  data class Pending(val runnable: Runnable, var cancelled: Boolean = false)
  val pending = mutableListOf<Pending>()

  fun schedule(): (Runnable) -> Any = { r -> Pending(r).also { pending.add(it) } }
  fun cancel(): (Any) -> Unit = { handle -> (handle as Pending).cancelled = true }

  // Fires every not-yet-cancelled pending timeout, oldest first — simulates "enough real time
  // passed that every currently-scheduled timeout would have fired."
  fun fireAll() {
    val toFire = pending.filter { !it.cancelled }
    pending.clear()
    toFire.forEach { it.runnable.run() }
  }
}

class GattOpQueueTest {

  private fun newQueue(scheduler: FakeScheduler) = GattOpQueue(10_000L, scheduler.schedule(), scheduler.cancel())

  @Test
  fun `callback before timeout completes normally and unblocks the queue`() {
    val scheduler = FakeScheduler()
    val queue = newQueue(scheduler)
    var ran = false

    queue.enqueue("op1", onTimeout = { throw AssertionError("should not time out") }) { ran = true }
    assertTrue("op should run immediately when queue is idle", ran)

    assertTrue("complete() with the matching token should succeed", queue.complete("op1"))
    assertTrue("queue should be idle again after completion", queue.isIdle())
  }

  @Test
  fun `operation that never gets a callback times out and releases the queue`() {
    val scheduler = FakeScheduler()
    val queue = newQueue(scheduler)
    var timedOut = false

    queue.enqueue("stuck", onTimeout = { timedOut = true }) { /* never calls complete() */ }
    assertFalse(timedOut)

    scheduler.fireAll()
    assertTrue("onTimeout should have fired", timedOut)
    assertTrue("queue should be released after a timeout", queue.isIdle())
  }

  @Test
  fun `a callback that arrives after its op already timed out is ignored, not applied to the next op`() {
    val scheduler = FakeScheduler()
    val queue = newQueue(scheduler)
    val timedOutTokens = mutableListOf<Any>()
    var secondOpRan = false

    queue.enqueue("first", onTimeout = { timedOutTokens.add(it) }) { /* never completes */ }
    scheduler.fireAll() // "first" times out; queue is now idle again

    queue.enqueue("second", onTimeout = { throw AssertionError("second should not time out") }) { secondOpRan = true }
    assertTrue(secondOpRan)

    // The late callback for "first" finally arrives — must NOT be mistaken for completing "second".
    assertFalse("stale callback for a timed-out op must not match a different, newer op", queue.complete("first"))
    assertEquals(listOf<Any>("first"), timedOutTokens)

    // "second" is still properly outstanding and completes normally.
    assertTrue(queue.complete("second"))
    assertTrue(queue.isIdle())
  }

  @Test
  fun `queued operations run strictly in order, one at a time`() {
    val scheduler = FakeScheduler()
    val queue = newQueue(scheduler)
    val started = mutableListOf<String>()

    queue.enqueue("a", onTimeout = {}) { started.add("a") }
    queue.enqueue("b", onTimeout = {}) { started.add("b") } // must NOT start yet — "a" is in flight
    queue.enqueue("c", onTimeout = {}) { started.add("c") }

    assertEquals(listOf("a"), started)

    queue.complete("a")
    assertEquals(listOf("a", "b"), started)

    queue.complete("b")
    assertEquals(listOf("a", "b", "c"), started)

    queue.complete("c")
    assertTrue(queue.isIdle())
  }

  @Test
  fun `complete with the wrong token is ignored and does not advance the queue`() {
    val scheduler = FakeScheduler()
    val queue = newQueue(scheduler)
    var ran = false

    queue.enqueue("real", onTimeout = {}) { ran = true }
    assertTrue(ran)

    assertFalse(queue.complete("unrelated"))
    assertFalse("queue should still be waiting on the real op", queue.isIdle())

    assertTrue(queue.complete("real"))
    assertTrue(queue.isIdle())
  }

  @Test
  fun `cancelAll drops pending and in-flight ops without firing onTimeout`() {
    val scheduler = FakeScheduler()
    val queue = newQueue(scheduler)

    queue.enqueue("first", onTimeout = { throw AssertionError("must not fire after cancelAll") }) {}
    queue.enqueue("second", onTimeout = { throw AssertionError("must not fire after cancelAll") }) {}

    queue.cancelAll()
    assertTrue(queue.isIdle())

    scheduler.fireAll() // even if a stale timeout Runnable is still around, cancelAll should have neutered it
    assertFalse(queue.complete("first"))
  }
}
