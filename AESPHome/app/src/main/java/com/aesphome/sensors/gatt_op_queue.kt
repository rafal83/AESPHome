package com.aesphome

/*

  GattOpQueue
    A generic, Android-independent serial operation queue with a per-operation timeout and
    stale-callback protection, extracted out of BluetoothGattProxy.Connection so its trickiest
    logic — a timeout firing exactly once, a callback that arrives after its op already timed
    out being ignored instead of completing a DIFFERENT, newer operation, and the queue never
    permanently stalling — is unit-testable without Robolectric/a real BluetoothGatt (see
    GattOpQueueTest.kt). `schedule`/`cancel` are injected so tests can drive a fake clock
    instead of sleeping in real time; production wires them to a Handler.postDelayed/
    removeCallbacks pair (see BluetoothGattProxy).

    Why Android needs this at all: it silently drops a second GATT operation issued on the
    same BluetoothGatt before the previous one's callback has fired, so every operation for a
    connection must be strictly one-at-a-time. The problem this queue specifically guards
    against is Android sometimes accepting an operation (readCharacteristic(), requestMtu(),
    ...) and then never calling the corresponding callback at all — which, without a timeout,
    leaves the queue blocked forever.

*/

internal class GattOpQueue(
    private val timeoutMs: Long,
    private val schedule: (Runnable) -> Any,
    private val cancel: (Any) -> Unit,
) {
  private class Op(val token: Any, val run: () -> Unit, val onTimeout: (Any) -> Unit)

  private val queue = ArrayDeque<Op>()
  private var current: Op? = null
  private var currentTimeoutHandle: Any? = null
  private var generation = 0L
  private var currentGeneration = -1L

  // `token` identifies the pending operation — pass whatever the eventual callback can be
  // matched against (e.g. the BluetoothGattCharacteristic reference for a read/write, or a
  // fixed per-kind sentinel object for a one-off op like requestMtu()). Runs immediately if
  // the queue was idle, otherwise waits its turn.
  @Synchronized fun enqueue(token: Any, onTimeout: (Any) -> Unit, run: () -> Unit) {
    queue.addLast(Op(token, run, onTimeout))
    if (current == null) runNext()
  }

  private fun runNext() {
    val next = queue.removeFirstOrNull()
    current = next
    if (next == null) return
    val gen = ++generation
    currentGeneration = gen
    val runnable = Runnable { onTimeoutFired(gen) }
    currentTimeoutHandle = schedule(runnable)
    next.run()
  }

  // Call from the real callback (onCharacteristicRead, onMtuChanged, ...) with the same
  // token the op was enqueued with. Returns true if it matched the currently in-flight op —
  // the caller should act on the callback's data/error. Returns false if it didn't — either
  // unrelated, or this op already timed out and the queue moved on — and the caller MUST
  // ignore the callback's payload rather than treat it as completing whatever op is current
  // now (that op is a different request; forwarding this callback's data for it would be a
  // second, wrong response for a request HA was already told failed via the timeout).
  @Synchronized fun complete(token: Any): Boolean {
    val op = current ?: return false
    if (op.token != token) return false
    cancelTimeout()
    current = null
    runNext()
    return true
  }

  @Synchronized private fun onTimeoutFired(gen: Long) {
    if (gen != currentGeneration || current == null) return // already completed or superseded
    val op = current!!
    current = null
    currentTimeoutHandle = null
    runNext()
    op.onTimeout(op.token)
  }

  private fun cancelTimeout() {
    currentTimeoutHandle?.let(cancel)
    currentTimeoutHandle = null
  }

  // Drops every pending and in-flight op without invoking any onTimeout callback — for when
  // the whole connection is going away (disconnect) and nothing further should be reported
  // for operations that no longer have a connection to report against.
  @Synchronized fun cancelAll() {
    cancelTimeout()
    current = null
    queue.clear()
  }

  @Synchronized fun isIdle(): Boolean = current == null && queue.isEmpty()
}
