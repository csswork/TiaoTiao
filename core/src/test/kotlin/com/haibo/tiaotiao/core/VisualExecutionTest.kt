package com.haibo.tiaotiao.core

import org.junit.Assert.*
import org.junit.Test

class VisualExecutionTest {
    @Test fun `explicit fast path needs ad evidence and a stable edge skip in both frames`() {
        val signals=VisualSignals(true,null,listOf(area),false,listOf(area))
        assertEquals(area,VisualExecution.explicitTarget(signals,area))
        assertNull(VisualExecution.explicitTarget(signals,null))
        assertNull(VisualExecution.explicitTarget(signals.copy(advertisement=false),area))
        assertNull(VisualExecution.explicitTarget(signals.copy(explicitSkips=emptyList()),area))
        assertNull(VisualExecution.explicitTarget(signals.copy(home=true),area))
        assertNull(VisualExecution.explicitTarget(signals.copy(explicitSkips=listOf(area,Area(.1f,.8f,.2f,.85f))),area))
    }
    private val area=Area(.88f,.04f,.97f,.08f)
    @Test fun `calibration cannot jump to a nearby nonoverlapping control`() {
        val expected=Area(.9f,.06f,.95f,.07f)
        assertNull(VisualPolicy.controlTarget(expected,listOf(Area(.9f,.075f,.95f,.085f))))
        assertEquals(expected,VisualPolicy.controlTarget(expected,listOf(expected)))
    }
    private val screen=ScreenSnapshot("org.example.reader",1,1080,2400,emptyList(),0,1)
    private val rule=VisualRule(packageName=screen.packageName,versionCode=1,scene=digest("scene"),
        target=area,targetHash="01".repeat(32),label="skip",source="vision",createdAt=0,tapMethod=TapMethod.GESTURE)
    private val ad=VisualSignals(true,null,listOf(area),false)
    @Test fun `retry requires the unchanged ad and freshly observed same control`() {
        fun retry(after: ScreenSnapshot=screen,signals: VisualSignals=ad,difference: Double=0.0,elapsed: Long=1000,attempts: Int=1)=
            VisualExecution.retryTarget(rule,screen,after,area,signals,difference,elapsed,attempts)
        assertEquals(area,retry())
        assertNull(retry(after=screen.copy(packageName="org.example.other")))
        assertNull(retry(after=screen.copy(windowId=2)))
        assertNull(retry(signals=ad.copy(home=true)))
        assertNull(retry(signals=ad.copy(advertisement=false)))
        assertNull(retry(signals=ad.copy(skips=emptyList())))
        assertNull(retry(difference=.1));assertNull(retry(elapsed=300));assertNull(retry(elapsed=3000));assertNull(retry(attempts=2))
    }
    @Test fun `session permits at most one bounded retry and no third click`() {
        val gate=SessionGate();gate.observe(screen.packageName,1000)
        gate.dispatched();assertFalse(gate.isOpen(screen.packageName,2000));assertTrue(gate.canRetry(screen.packageName,2000))
        assertFalse(gate.canRetry("org.example.other",2000));assertFalse(gate.canRetry(screen.packageName,11_001))
        gate.dispatched();assertFalse(gate.canRetry(screen.packageName,2500))
    }
    @Test fun `retry preserves the exact manually selected point`() {
        val adjusted=Area(.9f,.05f,.95f,.07f)
        val observed=Area(.905f,.05f,.955f,.07f)
        assertEquals(adjusted,VisualExecution.retryTarget(rule.copy(userAdjusted=true),screen,screen,adjusted,
            ad.copy(skips=listOf(observed)),0.0,1000,1))
    }
}
