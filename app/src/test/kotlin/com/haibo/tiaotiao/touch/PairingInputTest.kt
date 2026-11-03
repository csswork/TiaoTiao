package com.haibo.tiaotiao.touch

import org.junit.Assert.*
import org.junit.Test

class PairingInputTest {
    @Test fun `code only uses a current discovered port and preserves leading zeros`() {
        val input=requireNotNull(PairingInput.parse(" 001234 ",34567))
        assertEquals(34567,input.port);assertEquals("001234",input.code)
    }
    @Test fun `notification can supply a pairing port when discovery is unavailable`() {
        val input=requireNotNull(PairingInput.parse("12345 123456",0))
        assertEquals(12345,input.port)
    }
    @Test fun `manual port is validated and never silently falls back`() {
        assertNull(PairingInput.parse("123456",34567,70000))
        assertEquals(23456,requireNotNull(PairingInput.parse("123456",34567,23456)).port)
    }
    @Test fun `malformed data cannot start pairing`() {
        listOf("", "12345", "1234567", "12345 secret", "abc 123456", "70000 123456", "one two 123456").forEach {
            assertNull(PairingInput.parse(it,34567))
        }
        assertNull(PairingInput.parse("123456",0))
    }
    @Test fun `diagnostic representation never contains the pairing code`() {
        assertFalse(requireNotNull(PairingInput.parse("123456",34567)).toString().contains("123456"))
    }
}
