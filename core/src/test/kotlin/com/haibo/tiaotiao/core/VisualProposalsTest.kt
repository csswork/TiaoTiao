package com.haibo.tiaotiao.core

import org.junit.Assert.*
import org.junit.Test

class VisualProposalsTest {
    @Test fun `a target awaiting automatic retry stays in one task and does not block another position`() {
        val failed=VisualPolicy.pauseAutomatically(rule,"自动失败",1000)
        val fresh=rule.copy(id="new",contextHash="10".repeat(32))
        val task=VisualProposals.tasks(listOf(failed,fresh)).single()
        assertEquals("new",task.representative.id)
        assertEquals(2,task.variants.size)
        assertFalse(VisualProposals.sameTarget(rule,rule.copy(target=Area(.1f,.8f,.2f,.85f))))
    }
    private val hash = "01".repeat(32)
    private val area = Area(.88f, .04f, .97f, .08f)
    private val rule = VisualRule(id="first", packageName="org.example.reader", versionCode=1,
        scene=digest("portrait"), sceneVersion=2, target=area, targetHash=hash, contextHash=hash,
        label="skip", source="vision", createdAt=1, tapMethod=TapMethod.GESTURE)
    private fun changed(value: String, count: Int) = value.mapIndexed { index, c ->
        if(index < count) if(c=='1') '0' else '1' else c
    }.joinToString("")

    @Test fun `small frame and box changes describe a repeated sample`() {
        val jitter=rule.copy(id="jitter", target=Area(.885f,.043f,.975f,.083f),
            targetHash=changed(hash,3),contextHash=changed(hash,2))
        assertTrue(VisualProposals.repeatedSample(rule,jitter))
        assertFalse(VisualProposals.repeatedSample(rule,jitter.copy(contextHash=changed(hash,12))))
        assertFalse(VisualProposals.repeatedSample(rule,jitter.copy(targetHash=changed(hash,12))))
    }

    @Test fun `native and touch variants stay separate but share one pending task`() {
        val native=rule.copy(id="native",nodeTarget=TargetSelector("0.1","app:id/close","View","关闭",area),
            nodeScene=digest("nodes"),tapMethod=TapMethod.ACCESSIBILITY)
        assertFalse(VisualProposals.repeatedSample(rule,native))
        val task=VisualProposals.tasks(listOf(rule,native)).single()
        assertEquals(2,task.variants.size)
    }

    @Test fun `another creative remains executable independently without duplicating the task`() {
        val creative=rule.copy(id="creative",contextHash=changed(hash,32))
        assertFalse(VisualProposals.repeatedSample(rule,creative))
        assertEquals(2,VisualProposals.tasks(listOf(rule,creative)).single().variants.size)
        assertNotEquals(rule.behavior(),creative.behavior())
    }

    @Test fun `coarse and tight boxes around the same control share a task`() {
        val coarse=rule.copy(target=Area(.899f,.036f,.966f,.067f))
        val text=rule.copy(id="tight",target=Area(.90416664f,.064375f,.95f,.07375f))
        assertFalse(coarse.target.near(text.target))
        assertEquals(2,VisualProposals.tasks(listOf(coarse,text)).single().variants.size)
    }

    @Test fun `grouping cannot combine one successful session from two behaviors`() {
        val first=VisualPolicy.record(rule,"one",true,true,true,10)
        val second=VisualPolicy.record(rule.copy(id="second",contextHash=changed(hash,32),createdAt=2),"two",true,true,true,20)
        val task=VisualProposals.tasks(listOf(first,second)).single()
        assertEquals(1,task.representative.successfulSessions())
        assertFalse(task.representative.active())
        assertEquals(first.evidence,task.variants.first().evidence)
        assertEquals(second.evidence,task.variants.last().evidence)
    }

    @Test fun `task identity stays stable when a later sample has more proof`() {
        val later=VisualPolicy.record(rule.copy(id="later",createdAt=10,contextHash=changed(hash,32)),"one",true,true,true,20)
        val task=VisualProposals.tasks(listOf(later,rule)).single()
        assertEquals("first",task.id)
        assertEquals("later",task.representative.id)
    }

    @Test fun `apps versions orientation and distinct targets never share a task`() {
        val variants=listOf(rule,rule.copy(id="app",packageName="org.example.shop"),
            rule.copy(id="version",versionCode=2),rule.copy(id="landscape",scene=digest("landscape")),
            rule.copy(id="target",target=Area(.03f,.04f,.12f,.08f)))
        assertEquals(variants.size,VisualProposals.tasks(variants).size)
        variants.drop(1).forEach {assertFalse(VisualProposals.repeatedSample(rule,it))}
    }

    @Test fun `verified and paused variants retain their own status and management entry`() {
        val active=VisualPolicy.record(VisualPolicy.record(rule.copy(id="active"),"one",true,true,true,1),"two",true,true,true,2)
        val paused=rule.copy(id="paused",state=VisualRuleState.PAUSED,lastResult="用户停用")
        val tasks=VisualProposals.tasks(listOf(rule,active,paused))
        assertEquals(3,tasks.size)
        assertEquals(1,tasks.count {it.representative.active()})
        assertEquals(VisualRuleState.PAUSED,tasks.single {it.id=="paused"}.representative.state)
    }
}
