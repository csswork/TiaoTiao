package com.haibo.tiaotiao.core

/** Clock values are monotonic milliseconds, independent of user clock changes. */
class SessionGate(private val durationMillis: Long = 10_000) {
    var packageName: String? = null; private set
    private var startedAt = 0L
    private val requested = mutableSetOf<String>()
    private var dispatched = false
    val actionDispatched: Boolean get() = dispatched
    fun observe(packageName: String?, now: Long) {
        if (this.packageName == packageName) return
        this.packageName = packageName
        startedAt = now
        requested.clear()
        dispatched = false
    }
    fun isOpen(packageName: String, now: Long) = this.packageName == packageName && !dispatched && now - startedAt in 0 until durationMillis
    fun claimRequest(fingerprint: String): Boolean = requested.size < 2 && requested.add(fingerprint)
    fun dispatched() { dispatched = true }
    fun reset() { packageName = null; requested.clear(); dispatched = false }
}
