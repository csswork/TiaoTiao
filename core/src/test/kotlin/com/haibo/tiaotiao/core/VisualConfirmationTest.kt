package com.haibo.tiaotiao.core

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class VisualConfirmationTest {
    private val rule=VisualRule(packageName="org.example.reader",versionCode=1,scene=digest("scene"),target=Area(.8f,.04f,.96f,.1f),
        targetHash="01".repeat(32),label="skip",source="vision",createdAt=1,tapMethod=TapMethod.GESTURE)
    @Test fun `manual confirmation activates without inventing AI evidence`() {
        val confirmed=VisualPolicy.confirm(rule,1000)
        assertTrue(confirmed.active());assertTrue(confirmed.manuallyConfirmed())
        assertEquals(0,confirmed.successfulSessions());assertTrue(confirmed.evidence.isEmpty())
        assertNotEquals(rule.behavior(),confirmed.behavior())
        VisualPolicy.validate(confirmed)
        assertTrue(VisualPolicy.decode(ruleJson.encodeToString(VisualBundle(rules=listOf(confirmed)))).rules.single().active())
    }
    @Test fun `a later uncertain timing result preserves a manual confirmation`() {
        val confirmed=VisualPolicy.confirm(rule,1000)
        val checked=VisualPolicy.record(confirmed,"session",false,true,true,2000,"unknown")
        assertTrue(checked.active());assertEquals(0,checked.successfulSessions())
    }
    @Test fun `confirmation is bound to the exact action and is stripped on import`() {
        val confirmed=VisualPolicy.confirm(rule,1000)
        assertFalse(confirmed.copy(target=Area(.1f,.7f,.2f,.8f)).active())
        assertFalse(confirmed.copy(learningRevision=confirmed.learningRevision+1).active())
        val imported=VisualPolicy.import(ruleJson.encodeToString(VisualBundle(rules=listOf(confirmed)))).single()
        assertFalse(imported.active());assertNull(imported.manualConfirmation)
    }
    @Test fun `an actual negative result can pause a human confirmed rule`() {
        val confirmed=VisualPolicy.confirm(rule,1000)
        assertEquals(VisualRuleState.PAUSED,VisualPolicy.record(confirmed,"bad",true,false,true,2000).state)
    }
}
