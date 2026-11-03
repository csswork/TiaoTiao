package com.haibo.tiaotiao.core

/** Clock values are monotonic milliseconds, independent of user clock changes. */
class SessionGate(private val durationMillis: Long = 10_000) {
    var packageName: String? = null; private set
    var generation: Long = 0; private set
    private var startedAt = 0L
    private val requested = mutableSetOf<String>()
    private var dispatchCount=0
    val actionDispatched: Boolean get() = dispatchCount>0
    fun observe(packageName: String?, now: Long) {
        if (this.packageName == packageName) return
        generation++
        this.packageName = packageName
        startedAt = now
        requested.clear()
        dispatchCount=0
    }
    /** A readable application's owner is useful even when OEM focus/active flags are both false.
     * Missing roots and system/IME/overlay windows do not establish an application transition. */
    fun observeWindow(ownerPackage: String?, applicationWindow: Boolean, now: Long, deviceLocked: Boolean = false) {
        if (deviceLocked) { reset(); return }
        if (applicationWindow && !ownerPackage.isNullOrBlank()) observe(ownerPackage, now)
    }
    fun isOpen(packageName: String, now: Long) = this.packageName == packageName && dispatchCount==0 && now - startedAt in 0 until durationMillis
    fun canRetry(packageName: String,now: Long) = this.packageName==packageName && dispatchCount==1 && now-startedAt in 0 until durationMillis
    /** An unresolved new owner cannot inherit the previous application's completed session. */
    fun canAwait(owner: String, now: Long) = packageName!=owner || isOpen(owner,now)
    fun claimRequest(fingerprint: String): Boolean = requested.size < 2 && requested.add(fingerprint)
    fun dispatched() { dispatchCount++ }
    fun reset() { generation++; packageName = null; requested.clear(); dispatchCount=0 }
}
