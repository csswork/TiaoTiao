package com.haibo.tiaotiao.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewEvidenceTest {
    private val closed=ReviewEvidence(certain=true,accepted=true,originalTargetVisible=false,visualChange=true,localClosed=true)
    @Test fun `an agreed close is a success`() {
        assertEquals(ReviewOutcome.SUCCESS,closed.outcome())
    }
    @Test fun `a model's not-closed verdict against a clearly closed screen is ignored, not a failure`() {
        assertEquals(ReviewOutcome.IGNORED,closed.copy(accepted=false).outcome())
        assertEquals(ReviewOutcome.FAILURE,closed.copy(accepted=false,localClosed=false).outcome())
    }
    @Test fun `local evidence still vetoes a model's home verdict`() {
        assertEquals(ReviewOutcome.IGNORED,closed.copy(originalTargetVisible=true).outcome())
        assertEquals(ReviewOutcome.IGNORED,closed.copy(visualChange=false).outcome())
    }
    @Test fun `an uncertain review changes nothing`() {
        assertEquals(ReviewOutcome.IGNORED,closed.copy(certain=false).outcome())
        assertEquals(ReviewOutcome.IGNORED,closed.copy(certain=false,accepted=false,localClosed=false).outcome())
    }
    @Test fun `two launches closed on local evidence promote a rule like two agreed reviews`() {
        val rule=VisualRule(packageName="org.example.stay",versionCode=1,scene=digest("scene"),target=Area(.86f,.08f,.94f,.09f),
            targetHash="01".repeat(32),label="skip",source="local:explicit-ad-skip",createdAt=1,tapMethod=TapMethod.GESTURE)
        val once=VisualPolicy.record(rule,"a",true,true,true,1,"explicit_label",local=true)
        org.junit.Assert.assertFalse(once.active())
        org.junit.Assert.assertTrue(once.lastResult.startsWith("本地检查通过"))
        val twice=VisualPolicy.record(once,"b",true,true,true,2,"explicit_label",local=true)
        org.junit.Assert.assertTrue(twice.active())
        org.junit.Assert.assertTrue(twice.evidence.all {it.local})
        // The same launch never counts twice.
        org.junit.Assert.assertFalse(VisualPolicy.record(once,"a",true,true,true,3,"explicit_label",local=true).active())
    }
}
