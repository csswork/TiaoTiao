package com.haibo.tiaotiao.core

import org.junit.Assert.*
import org.junit.Test

class VisualRecoveryTest {
    private val rule=VisualRule(id="saved",packageName="org.example.reader",versionCode=1,scene=digest("scene"),
        target=Area(.88f,.04f,.97f,.08f),targetHash="01".repeat(32),label="skip",source="vision",createdAt=1,tapMethod=TapMethod.GESTURE)
    @Test fun `automatic failure recovers only after cooldown and a fresh proposal`() {
        val failed=VisualPolicy.record(rule,"first",true,false,true,1000)
        assertEquals(16_000L,failed.retryAfter)
        assertFalse(VisualPolicy.canRelearn(failed,15_999))
        assertNull(VisualPolicy.replaceProposal(failed,rule.copy(id="new"),15_999))
        val recovered=requireNotNull(VisualPolicy.replaceProposal(failed,rule.copy(id="new"),16_000))
        assertEquals("saved",recovered.id);assertEquals(1,recovered.automaticFailures)
        assertEquals(VisualRuleState.CANDIDATE,recovered.state);assertTrue(recovered.evidence.isEmpty())
        assertFalse(recovered.active())
    }
    @Test fun `a proven scheme survives one negative review and pauses on the second`() {
        val proven=VisualPolicy.record(VisualPolicy.record(rule,"one",true,true,true,1),"two",true,true,true,2)
        assertTrue(proven.active())
        val struck=VisualPolicy.record(proven,"three",true,false,true,3)
        assertTrue(struck.active());assertEquals(1,struck.automaticFailures)
        // A success in between clears the strike; two negatives in a row pause it.
        assertEquals(0,VisualPolicy.record(struck,"four",true,true,true,4).automaticFailures)
        assertEquals(VisualRuleState.PAUSED,VisualPolicy.record(struck,"four",true,false,true,4).state)
        // One proof is not enough to earn the tolerance.
        assertEquals(VisualRuleState.PAUSED,VisualPolicy.record(VisualPolicy.record(rule,"one",true,true,true,1),"two",true,false,true,2).state)
    }
    @Test fun `literal skip schemes for one button fold into the best one whatever the creative`() {
        val local=rule.copy(source=VisualExecution.LOCAL_SOURCE)
        val other=local.copy(id="creative",createdAt=5,targetHash="10".repeat(32),contextHash="0011".repeat(16))
        assertTrue(VisualProposals.repeatedSample(local,other))
        val proven=VisualPolicy.record(VisualPolicy.record(other,"one",true,true,true,1),"two",true,true,true,2)
        val stopped=local.copy(id="user",state=VisualRuleState.PAUSED,lastResult="用户反馈点错，已停用")
        val model=rule.copy(id="model",targetHash="10".repeat(32))
        assertEquals(listOf("user","model","creative"),VisualProposals.consolidate(listOf(local,stopped,model,proven)).map {it.id})
    }
    @Test fun `repeated failures back off then stop relearning until the user acts`() {
        var current=rule
        for(delay in listOf(15_000L,60_000L)) {
            val failed=VisualPolicy.pauseAutomatically(current,"自动检查未通过",1000)
            assertEquals(1000+delay,failed.retryAfter)
            current=requireNotNull(VisualPolicy.replaceProposal(failed,rule,failed.retryAfter))
        }
        val stopped=VisualPolicy.pauseAutomatically(current,"自动检查未通过",1000)
        assertEquals(VisualPolicy.MAX_AUTOMATIC_FAILURES,stopped.automaticFailures)
        assertEquals(Long.MAX_VALUE,stopped.retryAfter)
        assertFalse(VisualPolicy.canRelearn(stopped,Long.MAX_VALUE-1))
        assertNull(VisualPolicy.replaceProposal(stopped,rule,Long.MAX_VALUE-1))
        assertFalse(VisualPolicy.userPaused(stopped))
        VisualPolicy.validate(stopped)
        assertTrue(VisualPolicy.stalled(stopped))
        // A new app version starts over with a clean failure count; the same version stays stopped.
        val updated=requireNotNull(VisualPolicy.replaceProposal(stopped,rule.copy(id="new",versionCode=2),2000))
        assertEquals("saved",updated.id);assertEquals(0,updated.automaticFailures);assertFalse(VisualPolicy.stalled(updated))
        // The detail page's relearn/adjust reset these fields and resume learning.
        assertTrue(VisualPolicy.canRelearn(stopped.copy(state=VisualRuleState.CANDIDATE,retryAfter=0,automaticFailures=0),1000))
    }
    @Test fun `an early success resets the consecutive failure count`() {
        val recovered=rule.copy(automaticFailures=2)
        assertEquals(0,VisualPolicy.record(recovered,"ok",true,true,true,1).automaticFailures)
        assertEquals(2,VisualPolicy.record(recovered,"late",false,true,true,1).automaticFailures)
    }
    @Test fun `natural ending is observed a bounded number of sessions`() {
        val unknown=rule.copy(evidence=listOf(AutoEvidence("first",rule.behavior(),1,false,true,true,"unknown")))
        assertTrue(VisualPolicy.observeNaturalEnding(unknown,null,0,0))
        assertTrue(VisualPolicy.observeNaturalEnding(unknown,null,0,VisualPolicy.MAX_BASELINE_OBSERVATIONS-1))
        assertFalse(VisualPolicy.observeNaturalEnding(unknown,null,0,VisualPolicy.MAX_BASELINE_OBSERVATIONS))
        assertFalse(VisualPolicy.observeNaturalEnding(unknown,5,0,0))
        assertFalse(VisualPolicy.observeNaturalEnding(unknown,null,3000,0))
        assertFalse(VisualPolicy.observeNaturalEnding(rule,null,0,0))
        assertFalse(VisualPolicy.observeNaturalEnding(unknown,null,0,0,explicitLabel=true))
        val labelled=VisualPolicy.record(rule,"tap",true,true,true,1,"explicit_label")
        assertEquals(1,labelled.successfulSessions())
    }
    @Test fun `user pause and verified rules are never overwritten by a model`() {
        val disabled=rule.copy(state=VisualRuleState.PAUSED,lastResult="用户反馈点错，已停用")
        assertNull(VisualPolicy.replaceProposal(disabled,rule,Long.MAX_VALUE))
        assertEquals(disabled,VisualPolicy.pauseAutomatically(disabled,"自动失败",1000))
        val active=VisualPolicy.record(VisualPolicy.record(rule,"one",true,true,true,1),"two",true,true,true,2)
        assertNull(VisualPolicy.replaceProposal(active,rule,1000))
    }
    @Test fun `old automatically paused records can be freshly recognized`() {
        val legacy=rule.copy(state=VisualRuleState.PAUSED,automaticFailures=2,lastResult="自动检查未通过，等待重新识别")
        assertNotNull(VisualPolicy.replaceProposal(legacy,rule,1000))
    }
    @Test fun `a learning revision invalidates previously accumulated evidence`() {
        val proven=VisualPolicy.record(rule,"one",true,true,true,1)
        assertEquals(1,proven.successfulSessions())
        assertEquals(0,proven.copy(learningRevision=1).successfulSessions())
        assertNotEquals(proven.behavior(),proven.copy(userAdjusted=true).behavior())
    }
}
