package com.haibo.tiaotiao.core

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString

class VisualPolicyTest {
    private val area = Area(.8f,.04f,.96f,.09f)
    private val screen = ScreenSnapshot("cn.damai", 1, 1080, 2400, emptyList(), 0, 1)
    private val rule = VisualRule(packageName = "cn.damai", versionCode = 1, scene = screen.fingerprint(), target = area,
        targetHash = "0".repeat(64), label = "skip", source = "test", createdAt = 0)
    private val ad = VisualSignals(true, 4, listOf(area), false)
    @Test fun `visual target maps to a small actionable control and excludes ad containers`() {
        val control=ScreenNode("0.1","cn.damai:id/skip","android.view.View","",true,area)
        val full=ScreenNode("0","cn.damai:id/ad","android.widget.FrameLayout","",true,Area(0f,0f,1f,1f))
        val s=screen.copy(nodes=listOf(full,control))
        assertEquals(control,VisualPolicy.clickableTarget(s,area))
        assertNull(VisualPolicy.clickableTarget(screen.copy(nodes=listOf(full)),area))
        assertNull(VisualPolicy.clickableTarget(s.copy(nodes=listOf(control,control.copy(path="0.2"))),area))
    }
    @Test fun `a candidate can be attempted without asking a person but needs fresh ad evidence`() {
        assertTrue(VisualPolicy.canAttempt(rule, screen, ad, rule.targetHash))
        assertFalse(VisualPolicy.canAttempt(rule, screen, ad.copy(advertisement = false, countdown = null), rule.targetHash))
        assertFalse(VisualPolicy.canAttempt(rule, screen, ad.copy(home = true), rule.targetHash))
        assertFalse(VisualPolicy.canAttempt(rule, screen.copy(versionCode = 2), ad, rule.targetHash))
        assertFalse(VisualPolicy.canAttempt(rule, screen.copy(complete = false), ad, rule.targetHash))
    }
    @Test fun `natural expiry and duplicate sessions do not activate a rule`() {
        var r = VisualPolicy.record(rule, "one", false, true, true, 1)
        assertFalse(r.active())
        r = VisualPolicy.record(r, "two", true, true, true, 2)
        r = VisualPolicy.record(r, "two", true, true, true, 3)
        assertFalse(r.active())
        r = VisualPolicy.record(r, "three", true, true, true, 4)
        assertTrue(r.active())
    }
    @Test fun `visual patch can locate an OCR unreadable button only with current ad context`() {
        val hash="01".repeat(32)
        val visual=rule.copy(targetHash=hash)
        val unreadable=VisualSignals(true,null,emptyList(),false)
        assertTrue(VisualPolicy.canAttempt(visual,screen,unreadable,hash))
        assertFalse(VisualPolicy.canAttempt(visual,screen,unreadable.copy(advertisement=false),hash))
        assertFalse(VisualPolicy.canAttempt(visual,screen,unreadable,"10".repeat(32)))
        assertFalse(VisualPolicy.canAttempt(rule,screen,unreadable,rule.targetHash))
    }
    @Test fun `failed verification and changed behavior stop automatic reuse`() {
        val r = VisualPolicy.record(rule, "one", true, false, true, 1)
        assertEquals(VisualRuleState.PAUSED, r.state)
        assertFalse(VisualPolicy.canAttempt(r, screen, ad, r.targetHash))
        val active = VisualPolicy.record(VisualPolicy.record(rule, "one", true, true, true, 1), "two", true, true, true, 2)
        assertFalse(active.copy(target = Area(.7f,.04f,.8f,.09f)).active())
    }
    @Test fun `sharing never transfers another devices automatic validation`() {
        val active = VisualPolicy.record(VisualPolicy.record(rule, "one", true, true, true, 1), "two", true, true, true, 2)
        val imported = VisualPolicy.import(ruleJson.encodeToString(VisualBundle(rules = listOf(active)))).single()
        assertEquals(VisualRuleState.CANDIDATE, imported.state)
        assertTrue(imported.evidence.isEmpty())
        assertFalse(imported.active())
        assertFalse(VisualPolicy.canAttempt(imported,screen,ad,imported.targetHash))
    }
}
