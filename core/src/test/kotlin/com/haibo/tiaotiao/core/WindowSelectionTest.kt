package com.haibo.tiaotiao.core

import org.junit.Assert.*
import org.junit.Test

class WindowSelectionTest {
    @Test fun `unique application can be read without focus or active flags`() {
        assertEquals(7, WindowSelection.choose(listOf(
            WindowCandidate(1, false, true, true), WindowCandidate(7, true, false, false)), null))
    }
    @Test fun `current focused application overrides a stale event`() {
        assertEquals(8, WindowSelection.choose(listOf(
            WindowCandidate(7, true, false, false), WindowCandidate(8, true, true, true)), 7))
    }
    @Test fun `event identifies an application when focus flags are missing`() {
        assertEquals(7, WindowSelection.choose(listOf(
            WindowCandidate(7, true, false, false), WindowCandidate(8, true, false, false)), 7))
    }
    @Test fun `ambiguous applications and system windows are not guessed`() {
        assertNull(WindowSelection.choose(listOf(
            WindowCandidate(7, true, false, false), WindowCandidate(8, true, false, false)), 1))
        assertNull(WindowSelection.choose(listOf(WindowCandidate(1, false, true, true)), 1))
    }
}
