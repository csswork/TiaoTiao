package com.haibo.tiaotiao.core

import org.junit.Assert.*
import org.junit.Test

class RuleEngineTest {
    private val target = ScreenNode("0.1", "com.example.app:id/skip", "android.widget.Button", "跳过", true, Area(.75f, .03f, .97f, .09f))
    private val screen = ScreenSnapshot("com.example.app", 12, 1080, 2400, listOf(
        ScreenNode("0", "", "android.widget.FrameLayout", "广告", false, Area(0f, 0f, 1f, 1f)), target), 100, 7)
    private fun draft() = RuleEngine.compile(screen, target, "jev-latest", 100)
    private fun verified() = RuleEngine.confirm(RuleEngine.recordTrial(draft(), true, 200), true, 300)

    @Test fun `pending and trialled rules never auto click`() {
        assertNull(RuleEngine.autoMatch(listOf(draft()), screen))
        assertNull(RuleEngine.autoMatch(listOf(RuleEngine.recordTrial(draft(), true, 200)), screen))
        assertNotNull(RuleEngine.autoMatch(listOf(verified()), screen))
    }
    @Test fun `approval requires a real accepted trial`() {
        assertThrows(IllegalArgumentException::class.java) { RuleEngine.confirm(draft(), true, 200) }
        assertThrows(IllegalArgumentException::class.java) { RuleEngine.recordTrial(draft(), false, 200) }
    }
    @Test fun `changing approved behavior invalidates approval`() {
        val rule = verified()
        assertFalse(rule.copy(revision = 2).isVerified())
        assertFalse(rule.copy(target = rule.target.copy(path = "0.2")).isVerified())
        val trial = RuleEngine.recordTrial(draft(), true, 200).copy(versionCode = 13)
        assertThrows(IllegalArgumentException::class.java) { RuleEngine.confirm(trial, true, 300) }
    }
    @Test fun `expired and future trial receipts cannot be approved`() {
        val trial = RuleEngine.recordTrial(draft(), true, 200)
        assertThrows(IllegalArgumentException::class.java) { RuleEngine.confirm(trial, true, 3_600_201) }
        assertThrows(IllegalArgumentException::class.java) { RuleEngine.confirm(trial, true, 100) }
    }
    @Test fun `version page and geometry must match before click`() {
        val rule = verified()
        assertNull(RuleEngine.locate(rule, screen.copy(versionCode = 13)))
        assertNull(RuleEngine.locate(rule, screen.copy(packageName = "com.other.app")))
        assertNull(RuleEngine.locate(rule, screen.copy(nodes = listOf(target))))
        assertNull(RuleEngine.locate(rule, screen.copy(nodes = screen.nodes.dropLast(1) + target.copy(bounds = Area(.1f,.5f,.3f,.6f)))))
    }
    @Test fun `countdown changes preserve canonical scene`() {
        val changed = screen.copy(nodes = screen.nodes.dropLast(1) + target.copy(label = "跳过 3"))
        assertEquals(screen.fingerprint(), changed.fingerprint())
        assertNotNull(RuleEngine.locate(verified(), changed))
    }
    @Test fun `login and onboarding skip never become ad targets`() {
        listOf("跳过登录", "跳过引导", "跳过教程", "点击跳过登录", "安装").forEach { label ->
            val node = target.copy(label = label)
            assertThrows(IllegalArgumentException::class.java) { RuleEngine.compile(screen.copy(nodes = listOf(node)), node, "test", 1) }
        }
    }
    @Test fun `ambiguous matching rules abstain`() {
        val first = verified()
        assertNull(RuleEngine.autoMatch(listOf(first, first.copy(id = "another")), screen))
    }
    @Test fun `a clickable container mixing skip and download is not a skip target`() {
        val grouped = target.copy(label = "跳过 | 下载")
        assertThrows(IllegalArgumentException::class.java) { RuleEngine.compile(screen.copy(nodes = listOf(grouped)), grouped, "test", 1) }
    }
    @Test fun `truncated accessibility trees cannot authorize a click`() {
        val partial = screen.copy(complete = false)
        assertTrue(partial.candidates().isEmpty())
        assertNull(RuleEngine.autoMatch(listOf(verified()), partial))
        assertThrows(IllegalArgumentException::class.java) { RuleEngine.compile(partial, target, "test", 1) }
    }
    @Test fun `round trip preserves local validation but import never executes it`() {
        val encoded = RuleCodec.encode(listOf(verified()), 400)
        assertTrue(RuleCodec.decode(encoded).rules.single().isVerified())
        val imported = RuleCodec.importRules(encoded).single()
        assertEquals(RuleStatus.IMPORTED, imported.status)
        assertNull(imported.trial)
        assertNull(RuleEngine.autoMatch(listOf(imported), screen))
    }
    @Test fun `tampered JSON unknown schemas duplicates and oversize are rejected`() {
        val rule = verified()
        val valid = RuleCodec.encode(listOf(rule), 400)
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.decode(valid.replace("\"versionCode\": 12", "\"versionCode\": 13")) }
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.decode(valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")) }
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.decode(RuleCodec.encode(listOf(rule, rule), 400)) }
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.decode(" ".repeat(RuleCodec.MAX_BYTES + 1)) }
    }
    @Test fun `page events cannot extend a session or dispatch twice`() {
        val gate = SessionGate(10_000)
        gate.observe("com.example.app", 0)
        gate.observe("com.example.app", 9_000)
        assertFalse(gate.isOpen("com.example.app", 10_001))
        gate.observe("com.other.app", 10_002)
        gate.observe("com.example.app", 10_003)
        assertTrue(gate.isOpen("com.example.app", 10_004))
        assertTrue(gate.claimRequest("same")); assertFalse(gate.claimRequest("same"))
        gate.dispatched()
        assertFalse(gate.isOpen("com.example.app", 10_005))
    }
    @Test fun `private text is removed before persistence or model input`() {
        listOf("13812345678", "验证码1234", "你好张三", "订单金额100").forEach { assertEquals("", SignalText.sanitize(it)) }
        assertEquals("跳过", SignalText.sanitize("跳过 3 秒"))
    }
    @Test fun `Jev never chooses an invented node or non skip control`() {
        fun response(id: String, probability: String = "0.98") = """{"answers":{"splash":{"type":"noul","noul":$probability},"target":{"type":"choice","choice":"$id","confidence":0.98,"probabilities":{"0.1":0.98,"none":0.02}}}}"""
        assertEquals(target, JevProtocol.parse(response("0.1"), screen))
        assertNull(JevProtocol.parse(response("0.1", "0.5"), screen))
        assertThrows(IllegalArgumentException::class.java) { JevProtocol.parse(response("0.999"), screen) }
        assertNull(JevProtocol.parse(response("0.1"), screen.copy(nodes = listOf(target.copy(label = "购买")))))
    }
}
