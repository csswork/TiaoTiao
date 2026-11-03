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
    @Test fun `repeated failures back off without permanently disabling relearning`() {
        var current=rule
        for(delay in listOf(15_000L,60_000L,300_000L,300_000L)) {
            val failed=VisualPolicy.pauseAutomatically(current,"自动检查未通过",1000)
            assertEquals(1000+delay,failed.retryAfter)
            current=requireNotNull(VisualPolicy.replaceProposal(failed,rule,failed.retryAfter))
        }
        assertEquals(4,current.automaticFailures)
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
