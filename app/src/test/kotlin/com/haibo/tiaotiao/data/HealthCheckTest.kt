package com.haibo.tiaotiao.data

import org.junit.Assert.*
import org.junit.Test

class HealthCheckTest {
    private val healthy=HealthSnapshot(sdk=36,accessibilityEnabled=true,serviceBound=true)
    @Test fun `a healthy setup reports nothing`() {
        assertTrue(HealthCheck.evaluate(healthy).isEmpty())
    }
    @Test fun `accessibility states are distinguished and reported once`() {
        assertEquals(listOf("a11y_off"),HealthCheck.evaluate(healthy.copy(accessibilityEnabled=false,serviceBound=false,serviceSilent=true)).map {it.id})
        assertEquals(listOf("a11y_unbound"),HealthCheck.evaluate(healthy.copy(serviceBound=false)).map {it.id})
        assertEquals(listOf("a11y_silent"),HealthCheck.evaluate(healthy.copy(serviceSilent=true)).map {it.id})
    }
    @Test fun `critical issues come first`() {
        val issues=HealthCheck.evaluate(healthy.copy(batteryRestricted=true,autoSkip=false,serviceBound=false))
        assertEquals(listOf(HealthLevel.CRITICAL,HealthLevel.WARNING,HealthLevel.INFO),issues.map {it.level})
    }
    @Test fun `enhanced touch is only reported when the user turned it on`() {
        assertTrue(HealthCheck.evaluate(healthy.copy(enhancedTouch=false)).isEmpty())
        assertEquals("增强点击尚未配对",HealthCheck.evaluate(healthy.copy(enhancedTouch=true)).single().title)
        assertEquals("增强点击未运行：无线调试已关闭",HealthCheck.evaluate(healthy.copy(enhancedTouch=true,enhancedPaired=true)).single().title)
        assertTrue(HealthCheck.evaluate(healthy.copy(enhancedTouch=true,enhancedReady=true)).isEmpty())
    }
    @Test fun `cloud off is only mentioned when a key exists`() {
        assertEquals(listOf("missing_key"),HealthCheck.evaluate(healthy.copy(cloudEnabled=false,missingKey="Jev API Key")).map {it.id})
        assertEquals(listOf("cloud_off"),HealthCheck.evaluate(healthy.copy(cloudEnabled=false)).map {it.id})
    }
    @Test fun `each self-jumping launch ad gets its own entry`() {
        val issues=HealthCheck.evaluate(healthy.copy(adJumps=listOf("a.b" to "甲","c.d" to "乙")))
        assertEquals(listOf("a.b","c.d"),issues.map {it.packageName})
        assertTrue(issues.all {it.action==HealthAction.AD_JUMP})
    }
}
