package com.haibo.tiaotiao.touch

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TouchDispatchTest {
    @Test fun `system success never also dispatches an accessibility tap`()=runBlocking {
        var fallback=0
        assertEquals(TouchResult("Shizuku 系统触控",true),routeTouch(true,{TouchDispatch.COMPLETED},{fallback++;true}))
        assertEquals(0,fallback)
    }
    @Test fun `uncertain system failure cannot send a second backend`()=runBlocking {
        var fallback=0
        assertFalse(routeTouch(true,{TouchDispatch.FAILED},{fallback++;true}).completed)
        assertEquals(0,fallback)
    }
    @Test fun `only an unavailable uncalled system channel falls back`()=runBlocking {
        var fallback=0
        assertTrue(routeTouch(true,{TouchDispatch.UNAVAILABLE},{fallback++;true}).completed)
        assertEquals(1,fallback)
    }
    @Test fun `disabled system channel never invokes privileged code`()=runBlocking {
        var calls=0
        assertFalse(routeTouch(false,{calls++;TouchDispatch.COMPLETED},{false}).completed)
        assertEquals(0,calls)
    }
    private val dump="init=1440x3200 base=1080x2400 cur=1080x2400 app=1080x2332\nmCurrentFocus=Window{abc u0 org.example.reader/.AdActivity}"
    @Test fun `system guard checks the complete package logical dimensions and bounds`() {
        assertTrue(SystemTouchGuard.allows("org.example.reader",1000,165,1080,2400,dump))
        assertFalse(SystemTouchGuard.allows("org.example.read",1000,165,1080,2400,dump))
        assertFalse(SystemTouchGuard.allows("org.example.reader",1000,165,1440,3200,dump))
        assertFalse(SystemTouchGuard.allows("org.example.reader",1000,165,2400,1080,dump))
        assertFalse(SystemTouchGuard.allows("org.example.reader",1080,165,1080,2400,dump))
        assertFalse(SystemTouchGuard.allows("org.example.reader",1000,-1,1080,2400,dump))
        assertFalse(SystemTouchGuard.allows("org.example.reader",1000,165,1080,2400,"mCurrentFocus=null"))
    }
    @Test fun `system input command contains a bounded stationary 120ms touch without a shell script`() {
        assertEquals(listOf("/system/bin/input","touchscreen","swipe","1000","165","1000","165","120"),SystemTouchService.command(1000,165))
    }
}
