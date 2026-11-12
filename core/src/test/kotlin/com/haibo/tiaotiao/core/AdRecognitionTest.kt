package com.haibo.tiaotiao.core

import org.junit.Assert.*
import org.junit.Test

class AdRecognitionTest {
    private val area=Area(.8f,.03f,.97f,.09f)
    private val exit=ScreenNode("0.1","com.example.alpha:id/ad_exit","Button","",true,area)
    private val ad=ScreenNode("0.2","","TextView","广告",false,Area(.1f,.1f,.2f,.15f))
    private val screen=ScreenSnapshot("com.example.alpha",1,1080,2400,listOf(exit,ad),0,3,false)
    private fun response(choice: String="0.1")="""{"answers":{"splash":{"type":"noul","noul":0.99},"target":{"type":"choice","choice":"$choice","confidence":0.99,"probabilities":{"0.1":0.99,"none":0.01}}}}"""
    @Test fun `an unreadable new window keeps retrying after the previous application dispatched`() {
        val gate=SessionGate()
        gate.observe("com.example.alpha",0);gate.dispatched()
        assertFalse(gate.canAwait("com.example.alpha",100))
        assertTrue(gate.canAwait("org.other.beta",100))
        gate.observe("org.other.beta",100)
        assertTrue(gate.canAwait("org.other.beta",101))
        assertFalse(gate.canAwait("org.other.beta",10_101))
    }
    @Test fun `automatic learning eligibility is identical for arbitrary selected packages`() {
        listOf("com.example.alpha","org.other.beta","cn.damai").forEach {pkg ->
            assertTrue(AdRecognition.eligible(pkg,setOf(pkg),"com.example.self",true))
            assertFalse(AdRecognition.eligible(pkg,emptySet(),"com.example.self",true))
            assertFalse(AdRecognition.eligible(pkg,setOf(pkg),"com.example.self",false))
        }
        assertFalse(AdRecognition.eligible("com.example.self",setOf("com.example.self"),"com.example.self",true))
    }
    @Test fun `Jev can select an unlabeled close icon from a bounded partial tree`() {
        assertEquals(exit,JevProtocol.parse(response(),screen,true))
        assertNull(JevProtocol.parse(response(),screen.copy(complete=true)))
        assertTrue(JevProtocol.request(screen,"jev-latest",true).contains("ad_exit"))
        assertThrows(IllegalArgumentException::class.java) {JevProtocol.parse(response("invented"),screen,true)}
    }
    @Test fun `large pages and partial trees do not suppress current visual evidence`() {
        val nodes=screen.nodes+(1..100).map {ad.copy(path="0.3.$it",label="")}
        val s=screen.copy(nodes=nodes)
        val r=VisualRule(packageName=s.packageName,versionCode=1,scene=AdRecognition.scene(s),sceneVersion=2,
            target=area,targetHash="0".repeat(64),label="close_ad",source="test",createdAt=0,
            nodeTarget=TargetSelector(exit.path,exit.resourceId,exit.className,exit.label,area),nodeScene=AdRecognition.nodeScene(s))
        assertTrue(VisualPolicy.canAttempt(r,s,VisualSignals(false,null,emptyList(),false),r.targetHash))
        assertFalse(VisualPolicy.canAttempt(r,s.copy(nodes=listOf(exit)),VisualSignals(false,null,emptyList(),false),r.targetHash))
        // An app update keeps the rule: the same control on the same display is still attempted.
        assertTrue(VisualPolicy.canAttempt(r,s.copy(versionCode=2),VisualSignals(false,null,emptyList(),false),r.targetHash))
        assertFalse(VisualPolicy.canAttempt(r,s.copy(versionCode=2,width=s.width+1),VisualSignals(true,5,listOf(area),false),r.targetHash))
        assertFalse(VisualPolicy.canAttempt(r,s.copy(versionCode=2,packageName="org.example.other"),VisualSignals(true,5,listOf(area),false),r.targetHash))
        assertFalse(VisualPolicy.canAttempt(r,s,VisualSignals(true,5,listOf(area),true),r.targetHash))
    }
    @Test fun `dangerous or full-page controls cannot be offered as dismiss targets`() {
        val variants=listOf(exit.copy(label="购买"),exit.copy(label="跳过登录"),exit.copy(bounds=Area(0f,0f,1f,1f)))
        variants.forEach {assertTrue(AdRecognition.candidates(screen.copy(nodes=listOf(it))).isEmpty())}
        listOf("×","✕","关闭","close").forEach {assertEquals("关闭",SignalText.sanitize(it))}
        assertEquals("关闭",SignalText.sanitize("关闭 5秒"))
        assertEquals("登录",SignalText.sanitize("点击跳过登录"))
        assertTrue(AdRecognition.candidates(screen.copy(nodes=listOf(exit.copy(label=SignalText.sanitize("点击跳过登录"))))).isEmpty())
    }
    @Test fun `ad contexts are stable across irrelevant text nodes but separate versions and owners`() {
        assertEquals(AdRecognition.nodeScene(screen),AdRecognition.nodeScene(screen.copy(nodes=screen.nodes+ad.copy(path="0.9",label=""))))
        assertNotEquals(AdRecognition.scene(screen),AdRecognition.scene(screen.copy(packageName="org.other.beta")))
        assertNotEquals(AdRecognition.scene(screen),AdRecognition.scene(screen.copy(width=2400,height=1080)))
    }
    @Test fun `AI confirmed images can match without literal ad or skip text but need both context and target`() {
        val hash="01".repeat(32)
        val r=VisualRule(packageName=screen.packageName,versionCode=1,scene=AdRecognition.scene(screen),sceneVersion=2,
            target=area,targetHash=hash,label="close_ad",source="vision",createdAt=0,contextHash=hash)
        val signals=VisualSignals(false,null,emptyList(),false)
        assertTrue(VisualPolicy.canAttempt(r,screen,signals,hash,hash))
        assertFalse(VisualPolicy.canAttempt(r,screen,signals,hash,"10".repeat(32)))
        assertFalse(VisualPolicy.canAttempt(r,screen,signals,"10".repeat(32),hash))
        assertFalse(VisualPolicy.canAttempt(r,screen,signals,hash,"1".repeat(64)))
    }
    @Test fun `richer AI signals preserve old rule fingerprints`() {
        val enriched=screen.copy(nodes=listOf(exit.copy(label="关闭",legacyLabel=""),ad))
        assertEquals(screen.fingerprint(),enriched.fingerprint())
        assertNotEquals(AdRecognition.nodeScene(screen),AdRecognition.nodeScene(enriched))
        assertEquals("",SignalText.legacySanitize("点击下载应用"))
        assertEquals("下载",SignalText.sanitize("点击下载应用"))
    }
    @Test fun `semantic extraction never exempts a private sentence from image masking`() {
        listOf("登录账号 13812345678","支付给张三","下载用户的私人文件","跳过 13812345678秒").forEach {
            assertFalse(SignalText.isPublicControlText(it))
        }
        assertEquals("登录",SignalText.sanitize("登录账号 13812345678"))
        listOf("关闭 5秒","跳过 5秒","广告","AD","暂不").forEach {
            assertTrue(SignalText.isPublicControlText(it))
        }
    }
    @Test fun `old verified skip controls still work with close icons but reject new login evidence`() {
        val old=screen.copy(complete=true,nodes=listOf(exit.copy(label="跳过"),ad))
        val draft=RuleEngine.compile(old,old.nodes.first(),"legacy",0)
        val verified=RuleEngine.confirm(RuleEngine.recordTrial(draft,true,1),true,2)
        val expanded=old.copy(nodes=listOf(old.nodes.first().copy(label="关闭 | 跳过",legacyLabel="跳过"),ad))
        assertEquals(verified,RuleEngine.autoMatch(listOf(verified),expanded))
        val login=expanded.copy(nodes=listOf(expanded.nodes.first().copy(label="登录 | 跳过"),ad))
        assertNull(RuleEngine.autoMatch(listOf(verified),login))
    }
}
